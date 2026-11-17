package org.appdevforall.shareyourapps.server

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools MCP clients can call. Each runs on one of [McpHttp]'s connection threads and reads what
 * the main process already knows: [ServerStatus], [Hotspot.info], the saved settings and the imported
 * copy's description. server_start and server_stop send HostService the same requests the server
 * screen's Start and Stop do; the share tools go through [ShareControl], which can't approve a copy.
 *
 * Never reported: the hotspot's password, the PIN, the plugin's token, the cookie secret.
 * No tool changes the store's contents, deletes a copy, or changes the hotspot's settings.
 */
object McpTools {

    /** Reason HostService logs when server_stop stops the server. */
    const val STOP_REASON = "stopped by an MCP client"
    private const val DEFAULT_START_WAIT_S = 10
    /** The app's context, kept by [all], so import_status can check the network now, not when the import ended. */
    @Volatile private var appCtx: Context? = null

    fun all(context: Context): List<McpTool> {
        val app = context.applicationContext
        appCtx = app
        // Step C's sound tools, share_play_sound and import_listen, are in McpSoundTools.
        return listOf(serverStatus(app), storeInfo(app), serverStart(app), serverStop(app),
            shareStart(app), shareStatus(app), shareStop(app),
            importStartTool(app), importStatusTool(app), importCancelTool(app)) + McpSoundTools.all(app)
    }

    // ---------------------------------------------------------------- server_status

    private fun serverStatus(ctx: Context) = McpTool(
        name = "server_status",
        title = "Server status",
        description = "Whether Share Your Apps Server is serving on this phone, and what: this phone's own store or an imported copy, " +
            "at which address, on which kind of network (Wi-Fi, the phone's Mobile hotspot, or the app's own hotspot, with its name). " +
            "state is one of serving, starting, paused, stopped, failed. The hotspot's password is never included.",
    ) { _ -> status(ctx) }

    fun status(ctx: Context): JSONObject {
        val prefs = ctx.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        val text = ServerStatus.text
        val url = ServerStatus.url
        val out = JSONObject()
            .put("state", stateOf(text, url))
            .put("status", text)
            .put("url", url ?: JSONObject.NULL)
            .put("serving", if (url == null) JSONObject.NULL else if (ServerStatus.servingCopy) "copy" else "own")
            .put("port_setting", prefs.getInt(HostService.EXTRA_PORT, Store.DEFAULT_PORT))
            .put("run_mode", if (prefs.getString(HostService.PREF_RUN_MODE, "always") == "screenOff") "pause_when_screen_off" else "always")
        if (url != null) out.put("network", network())
        return out
    }

    /** The kind of network being served on. The hotspot's name only, never its password. */
    private fun network(): JSONObject {
        val h = Hotspot.info
        return when {
            h != null -> JSONObject()
                .put("kind", if (h.kind == Hotspot.Kind.WIFI_DIRECT) "app_hotspot_wifi_direct" else "app_hotspot_local_only")
                .put("ssid", h.ssid)
                .put("address", h.address.hostAddress ?: JSONObject.NULL)
            ServerStatus.onPhoneHotspot -> JSONObject().put("kind", "phone_mobile_hotspot")
            else -> JSONObject().put("kind", "wifi")
        }
    }

    /** The status line, as one word a client can act on. Plain Kotlin, for tests. */
    fun stateOf(text: String, url: String?): String = when {
        url != null -> "serving"
        text.startsWith("Failed") -> "failed"
        text.startsWith("Paused") -> "paused"
        text.startsWith("Stopped") -> "stopped"
        text.contains("tarting") -> "starting"   // Starting…, Restarting…, Not on Wi-Fi — starting a hotspot…
        else -> "stopped"
    }

    // ---------------------------------------------------------------- store_info

    private fun storeInfo(ctx: Context) = McpTool(
        name = "store_info",
        title = "Store information",
        description = "What this phone has to serve: its own published store (the folder last published from the Share Your Apps plugin, and its name), " +
            "and the imported copy of someone else's store, if there is one (its name, size and expiry). Also which one was served last.",
    ) { _ -> storeInfo(ctx, Unit) }

    @Suppress("UNUSED_PARAMETER")
    private fun storeInfo(ctx: Context, unit: Unit): JSONObject {
        val prefs = ctx.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        val own = JSONObject()
        val dir = prefs.getString(HostService.EXTRA_DIR, null)
        if (dir == null) {
            own.put("published", false)
        } else {
            own.put("published", true)
                .put("directory", dir)
                .put("name", prefs.getString(HostService.PREF_STORE_NAME, null) ?: JSONObject.NULL)
            val storage = Store.hasStorageAccess(ctx)
            own.put("storage_access", storage)
            if (storage && Store.validName(dir)) own.put("exists", Store.dir(dir).isDirectory)
        }
        val copy = try { ImportJob.currentCopy(ctx) } catch (e: Exception) { null }
        val copyJson: Any = if (copy == null) JSONObject.NULL else JSONObject()
            .put("name", copy.storeName)
            .put("store_id", copy.storeId)
            .put("created", runCatching { copy.created.toString() }.getOrNull() ?: JSONObject.NULL)
            .put("expires", runCatching { copy.expires?.toString() }.getOrNull() ?: JSONObject.NULL)
            .put("files", runCatching { copy.files.size }.getOrDefault(0))
            .put("bytes", runCatching { copy.totalBytes }.getOrDefault(0L))
        return JSONObject()
            .put("own_store", own)
            .put("imported_copy", copyJson)
            .put("last_served", prefs.getString(HostService.PREF_SOURCE, "own"))
            .put("serving", if (ServerStatus.url == null) JSONObject.NULL else if (ServerStatus.servingCopy) "copy" else "own")
    }

    // ---------------------------------------------------------------- arguments and refusals

    private fun schema(vararg props: Pair<String, JSONObject>): JSONObject = JSONObject().put("type", "object")
        .put("properties", JSONObject().also { p -> props.forEach { (k, v) -> p.put(k, v) } })
        .put("additionalProperties", false)

    private fun waitProp(what: String): JSONObject = JSONObject().put("type", "integer").put("minimum", 0)
        .put("maximum", ShareControl.MAX_WAIT_S).put("description", what)

    /** wait_seconds: a number from 0 to [ShareControl.MAX_WAIT_S], or [default] when not sent. Internal, for tests. */
    internal fun waitArg(args: JSONObject, default: Int): Int {
        if (!args.has("wait_seconds") || args.isNull("wait_seconds")) return default
        val v = args.opt("wait_seconds") as? Number
            ?: throw McpToolError("wait_seconds must be a number of seconds, from 0 to ${ShareControl.MAX_WAIT_S}.")
        return v.toInt().coerceIn(0, ShareControl.MAX_WAIT_S)
    }

    /** server_start's store: "own", "copy", or null when not sent (then whichever Start would serve). Internal, for tests. */
    internal fun storeArg(args: JSONObject): String? = when (val s = args.opt("store")) {
        null, JSONObject.NULL -> null
        "own", "copy" -> s as String
        else -> throw McpToolError("store must be \"own\" or \"copy\".")
    }

    /** Something only a person can do: the client is told what, and why. */
    private fun personNeeded(what: String) =
        McpToolError("A person is needed: $what", JSONObject().put("state", "person_needed").put("person_needed", what))

    /**
     * Sends HostService [intent], as the server screen's buttons do, then waits up to [waitSeconds] for the
     * status to change and settle where [done] says. Returns whether it settled in time.
     */
    private fun sendAndWait(ctx: Context, intent: Intent, waitSeconds: Int, done: (String) -> Boolean): Boolean {
        val changes = java.util.concurrent.atomic.AtomicInteger(-1)   // observe() calls the listener once at once
        val lock = Object()
        val listener: () -> Unit = { changes.incrementAndGet(); synchronized(lock) { lock.notifyAll() } }
        ServerStatus.observe(listener)
        try {
            try {
                ctx.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ may refuse a foreground start while the app isn't on screen.
                throw personNeeded("Android didn't let the app start its server service from the background " +
                    "(${e.javaClass.simpleName}). Ask a person to open Share Your Apps Server, then try again.")
            }
            val deadline = System.currentTimeMillis() + waitSeconds * 1000L
            while (true) {
                if (changes.get() > 0 && done(stateOf(ServerStatus.text, ServerStatus.url))) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                synchronized(lock) { lock.wait(minOf(left, 250L)) }
            }
        } finally {
            ServerStatus.remove(listener)
        }
    }

    // ---------------------------------------------------------------- server_start

    private fun serverStart(ctx: Context) = McpTool(
        name = "server_start",
        title = "Start the server",
        description = "Starts serving, as the server screen's Start button does; restarts it if it's already serving. " +
            "store \"own\" serves this phone's own published store, \"copy\" the imported copy; without it, whichever Start would serve. " +
            "Waits up to wait_seconds (default $DEFAULT_START_WAIT_S) for it to be serving, then reports as server_status does. " +
            "Reports person_needed when a person must act first, such as granting a permission.",
        inputSchema = schema(
            "store" to JSONObject().put("type", "string").put("enum", JSONArray().put("own").put("copy"))
                .put("description", "\"own\": this phone's published store. \"copy\": the imported copy."),
            "wait_seconds" to waitProp("How long to wait for the server to be serving, 0 to ${ShareControl.MAX_WAIT_S}. Default $DEFAULT_START_WAIT_S."),
        ),
        readOnly = false,
    ) { args -> startServer(ctx, args) }

    private fun startServer(ctx: Context, args: JSONObject): JSONObject {
        val asked = storeArg(args)
        val wait = waitArg(args, DEFAULT_START_WAIT_S)
        val prefs = ctx.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        val ownDir = prefs.getString(HostService.EXTRA_DIR, null)
        val copy = try { ImportJob.currentCopy(ctx) } catch (e: Exception) { null }
        // Without a choice, the same as Start: the copy if it was served last, or if there's no store of this phone's own.
        val which = asked ?: if (copy != null && (prefs.getString(HostService.PREF_SOURCE, "own") == "copy" || ownDir == null)) "copy" else "own"
        if (which == "copy" && copy == null) {
            throw McpToolError("There's no imported copy on this phone to serve.", JSONObject().put("state", "no_copy"))
        }
        if (which == "own") {
            if (ownDir == null) throw McpToolError("Nothing has been published on this phone yet: publish from the Share Your Apps plugin in Code on the Go first.",
                JSONObject().put("state", "nothing_published"))
            if (!Store.hasStorageAccess(ctx)) throw personNeeded("storage access hasn't been granted. Ask a person to open Share Your Apps Server and tap Grant storage access.")
        }
        val port = prefs.getInt(HostService.EXTRA_PORT, Store.DEFAULT_PORT)
        if (port == McpHttp.DEFAULT_PORT) throw personNeeded("the store's port is set to ${McpHttp.DEFAULT_PORT}, which MCP control uses. Ask a person to choose another port on the server screen.")
        // A copy is never replaced under a running server, so nothing starts while one is arriving.
        if (ImportJob.snapshot.busy) throw McpToolError("A store is being imported on this phone. Wait for the import to finish.",
            JSONObject().put("state", "importing"))
        if (Store.needsHotspot() && !Store.hasHotspotPermission(ctx)) {
            val perm = if (android.os.Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"
            throw personNeeded("this phone isn't on Wi-Fi, so the server must start a hotspot, which needs the $perm permission. " +
                "Ask a person to open Share Your Apps Server and tap Start, or to join a Wi-Fi network first.")
        }
        val intent = Intent(ctx, HostService::class.java)
        if (which == "copy") intent.action = HostService.ACTION_SERVE_COPY
        // The log names MCP control, not Manage Server, as the reason for the restart.
        else intent.setAction(HostService.ACTION_START).putExtra(HostService.EXTRA_DIR, ownDir)
            .putExtra(HostService.EXTRA_REASON, "restarted by an MCP client")
        val settled = sendAndWait(ctx, intent, wait) { it != "starting" }
        val out = status(ctx).put("requested", which)
        if (out.optString("state") == "failed") {
            val why = ServerStatus.text.removePrefix("Failed: ")
            if (why.contains("permission")) out.put("person_needed", why)
            throw McpToolError("The server didn't start: $why", out)
        }
        if (!settled) out.put("next", "Still starting. Call server_status to see when it's serving.")
        return out
    }

    // ---------------------------------------------------------------- server_stop

    private fun serverStop(ctx: Context) = McpTool(
        name = "server_stop",
        title = "Stop the server",
        description = "Stops serving, as the server screen's Stop button does, and turns off the app's hotspot if it started one. " +
            "Any share or download under way ends. The log records that an MCP client stopped it. " +
            "Waits up to wait_seconds (default $DEFAULT_START_WAIT_S) for it to stop.",
        inputSchema = schema("wait_seconds" to waitProp("How long to wait for the server to stop, 0 to ${ShareControl.MAX_WAIT_S}. Default $DEFAULT_START_WAIT_S.")),
        readOnly = false,
    ) { args -> stopServer(ctx, args) }

    private fun stopServer(ctx: Context, args: JSONObject): JSONObject {
        val wait = waitArg(args, DEFAULT_START_WAIT_S)
        val before = stateOf(ServerStatus.text, ServerStatus.url)
        if (before == "stopped" || before == "failed") return status(ctx).put("stopped", false).put("note", "The server wasn't running.")
        val intent = Intent(ctx, HostService::class.java).setAction(HostService.ACTION_STOP).putExtra(HostService.EXTRA_REASON, STOP_REASON)
        val settled = sendAndWait(ctx, intent, wait) { it == "stopped" || it == "failed" }
        val out = status(ctx).put("stopped", settled)
        if (!settled) out.put("next", "Still stopping. Call server_status to see when it has stopped.")
        return out
    }

    // ---------------------------------------------------------------- share_start, share_status, share_stop

    private fun shareStart(ctx: Context) = McpTool(
        name = "share_start",
        title = "Share this store",
        description = "Opens a share of this phone's own store, for another phone nearby to import: returns its code and link. " +
            "The other phone imports with them; then a person on this phone must check four digits and tap Allow, which no tool can do. " +
            "A notification asks them. If a share is already under way, it's returned with already_open true, never replaced. " +
            "Needs the server serving this phone's own store: an imported copy can't be shared.",
        readOnly = false,
    ) { _ -> ShareControl.start(ctx) }

    private fun shareStatus(ctx: Context) = McpTool(
        name = "share_status",
        title = "Share status",
        description = "The share's state: preparing, waiting_for_phone, phone_connecting, waiting_for_allow (a person must tap Allow), " +
            "sending, transfer_failed, done, denied, expired, closed (with the reason) or not_serving. " +
            "With wait_seconds, waits up to that long for the state to change (changed tells whether it did).",
        inputSchema = schema("wait_seconds" to waitProp("How long to wait for a change, 0 to ${ShareControl.MAX_WAIT_S}. Default 0.")),
    ) { args -> ShareControl.status(ctx, waitArg(args, 0)) }

    private fun shareStop(ctx: Context) = McpTool(
        name = "share_stop",
        title = "Stop sharing",
        description = "Closes the share started with share_start: its code stops working. A copy already allowed and being sent is stopped too.",
        readOnly = false,
    ) { _ -> ShareControl.stop(ctx) }

    // ---------------------------------------------------------------- import_start, import_status, import_cancel

    /** The import screen's limit for the name the other phone's owner sees. */
    private const val MAX_PHONE_NAME = 40

    private fun importStartTool(ctx: Context) = McpTool(
        name = "import_start",
        title = "Import a store",
        description = "Starts importing a copy of another phone's store, from its share code (LS-XXXX-XXXX) or its share link " +
            "(shareyourapps-share://…): give exactly one. Both phones must be on the same Wi-Fi network or hotspot. Returns at once; " +
            "follow it with import_status. A person on the other phone must check the check code and tap Allow, which no tool can do. " +
            "The copy replaces an imported copy of a different store without asking; replacing a newer copy of the same store with an " +
            "older one needs a person on this phone (import_status reports person_needed). This phone's own store is never touched. " +
            "Refused while this phone is serving, starting or paused (call server_stop first), or while an import is running.",
        inputSchema = schema(
            "code" to JSONObject().put("type", "string").put("description", "The share code shown on the other phone, such as LS-AB12-CD34."),
            "link" to JSONObject().put("type", "string").put("description", "The share link from the other phone's QR code: shareyourapps-share://<address>:<port>/<code>."),
            "phone_name" to JSONObject().put("type", "string").put("maxLength", MAX_PHONE_NAME)
                .put("description", "The name the other phone's owner sees, at most $MAX_PHONE_NAME characters. Default: this phone's name."),
        ),
        readOnly = false,
    ) { args -> startImport(ctx, args) }

    private fun startImport(ctx: Context, args: JSONObject): JSONObject {
        val (code, address) = importTarget(args)
        val name = phoneNameArg(args) ?: ImportActivity.defaultPhoneName(ctx)
        // A phone serving its own store can't import one (Appendix E.1). Starting and paused count too: either brings a server back.
        val server = stateOf(ServerStatus.text, ServerStatus.url)
        if (server == "serving" || server == "starting" || server == "paused") {
            throw McpToolError("This phone is serving a store, or about to, and can't import one while it does. Stop the server first (server_stop).",
                JSONObject().put("state", "server_running").put("server", status(ctx)))
        }
        if (ImportJob.snapshot.busy) throw McpToolError("An import is already running. Follow it with import_status, or end it with import_cancel.",
            JSONObject().put("state", "importing").put("import", importStatus()))
        if (SoundImport.busy) throw McpToolError("import_listen is still listening or joining. Wait for it, or stop it with import_cancel.",
            JSONObject().put("state", "listening").put("listen", SoundImport.status()))
        val why = ImportJob.start(ctx, code, name, address, byMcp = true)
        if (why != null) {
            if (why.startsWith("person needed: ")) throw personNeeded(why.removePrefix("person needed: "))
            throw McpToolError(why, JSONObject().put("state", "importing").put("import", importStatus()))
        }
        return importStatus().put("phone_name", name)
    }

    /** import_start's code or link, exactly one: the normalized code, and the owner's address when a link gives it. Internal, for tests. */
    internal fun importTarget(args: JSONObject): Pair<String, java.net.InetSocketAddress?> {
        fun str(key: String): String? = when (val v = args.opt(key)) {
            null, JSONObject.NULL -> null
            is String -> v.trim().takeIf { it.isNotEmpty() }
            else -> throw McpToolError("$key must be a string.")
        }
        val code = str("code")
        val link = str("link")
        if ((code == null) == (link == null)) throw McpToolError("Give either code or link: exactly one of them.")
        if (code != null) {
            val normalized = ShareCodes.normalize(code)
                ?: throw McpToolError("That isn't a share code: LS, then two groups of four letters and digits, such as LS-AB12-CD34.")
            return normalized to null
        }
        val (address, port, linkCode) = ShareLink.parse(link!!)
            ?: throw McpToolError("That isn't a share link: it looks like shareyourapps-share://192.168.1.5:6175/<code>.")
        return linkCode to java.net.InetSocketAddress(address, port)
    }

    /** import_start's phone_name: 1 to [MAX_PHONE_NAME] characters, or null when not sent. Internal, for tests. */
    internal fun phoneNameArg(args: JSONObject): String? {
        val v = args.opt("phone_name")
        if (v == null || v == JSONObject.NULL) return null
        val s = (v as? String)?.trim() ?: throw McpToolError("phone_name must be a string.")
        if (s.isEmpty()) throw McpToolError("phone_name can't be empty: leave it out to use this phone's name.")
        if (s.length > MAX_PHONE_NAME) throw McpToolError("phone_name can be at most $MAX_PHONE_NAME characters.")
        return s
    }

    /** The import's state, for import_status and the other import tools. */
    fun importStatus(): JSONObject {
        val s = ImportJob.snapshot
        val q = ImportJob.question
        val out = JSONObject()
            .put("phase", s.phase.name.lowercase())
            .put("busy", s.busy)
            .put("message", s.message)
            .put("started_by", if (s.phase == ImportJob.Phase.IDLE) JSONObject.NULL else if (ImportJob.startedByMcp) "mcp" else "person")
            .put("check_code", s.checkCode ?: JSONObject.NULL)
            .put("store", s.storeName ?: JSONObject.NULL)
        if (s.phase == ImportJob.Phase.RECEIVING || s.phase == ImportJob.Phase.DONE) {
            out.put("received", s.received).put("total", s.total).put("files", s.files)
        }
        if (s.phase == ImportJob.Phase.DONE) {
            // Checked now, not when the import ended: Wi-Fi may have been turned off or changed since. And whether the
            // copy is still on this phone: it may have been removed, expired or replaced since.
            val owners = appCtx?.let { ImportJob.onOwnersNetworkNow(it) } ?: s.onOwnersNetwork
            val still = appCtx?.let { c -> runCatching { ImportJob.currentCopy(c)?.storeId == s.storeId }.getOrDefault(true) } ?: true
            out.put("expires", s.expires?.toString() ?: JSONObject.NULL).put("on_owners_network", owners).put("copy_on_phone", still)
            if (owners) out.put("note", "This phone is on the other phone's network. To serve the copy, leave it first: join another Wi-Fi network, or turn Wi-Fi off so this phone starts its own hotspot.")
        }
        if (s.canRetry) out.put("can_retry", true)
            .put("retry", "The transfer failed partway. A person can tap Try again on the import screen while the other phone's share is open.")
        if (q != null && s.busy) {
            out.put("state", "person_needed")
                .put("person_needed", "${q.title} ${q.message} A person must answer on this phone within 2 minutes; a notification opens the question.")
                .put("question", JSONObject().put("title", q.title).put("message", q.message))
        }
        // import_listen's part, once it has been used: listening, checking, joining, import_started, nothing_heard ...
        if (SoundImport.state.phase != SoundImport.Phase.IDLE) out.put("listen", SoundImport.status())
        return out
    }

    /** Waits up to [seconds] for the import's phase or question to change; progress alone doesn't count. Returns whether it changed. */
    private fun waitForImport(seconds: Int): Boolean {
        if (seconds <= 0) return false
        // import_listen's phase counts too: the loop below looks every 250 ms.
        val key = { Triple(ImportJob.snapshot.phase, ImportJob.question, SoundImport.state.phase) }
        val start = key()
        val lock = Object()
        val listener: () -> Unit = { synchronized(lock) { lock.notifyAll() } }
        ImportJob.observe(listener)
        try {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (true) {
                if (key() != start) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                synchronized(lock) { lock.wait(minOf(left, 250L)) }
            }
        } finally {
            ImportJob.remove(listener)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun importStatusTool(ctx: Context) = McpTool(
        name = "import_status",
        title = "Import status",
        description = "The import's phase: idle, finding, connecting, waiting (for the other phone's owner to tap Allow), receiving, done, " +
            "failed, denied or cancelled, with a message, who started it (mcp or person), the check code the other phone's screen " +
            "should show, the store's name and progress once receiving, and its expiry once done. person_needed when a person on this " +
            "phone must answer a question. With wait_seconds, waits up to that long for the phase to change (changed tells whether it did).",
        inputSchema = schema("wait_seconds" to waitProp("How long to wait for a change, 0 to ${ShareControl.MAX_WAIT_S}. Default 0.")),
    ) { args ->
        val wait = waitArg(args, 0)
        val changed = waitForImport(wait)
        val out = importStatus()
        if (wait > 0) out.put("changed", changed)
        out
    }

    @Suppress("UNUSED_PARAMETER")
    private fun importCancelTool(ctx: Context) = McpTool(
        name = "import_cancel",
        title = "Cancel the import",
        description = "Cancels the import under way, as the import screen's Cancel does: nothing is kept, and the copy already on this phone stays. " +
            "Reports cancelled false when no import is running.",
        readOnly = false,
    ) { _ ->
        // Listening, checking or joining for import_listen stops too; an import it already started is cancelled below.
        val stoppedListening = SoundImport.stop(ctx)
        if (!ImportJob.snapshot.busy) {
            if (stoppedListening) importStatus().put("cancelled", true).put("note", "Stopped listening. No import had started.")
            else importStatus().put("cancelled", false).put("note", "No import is running.")
        } else {
            ImportJob.cancel()
            importStatus().put("cancelled", true).put("note", "Cancelling. Call import_status to see it end.")
        }
    }
}
