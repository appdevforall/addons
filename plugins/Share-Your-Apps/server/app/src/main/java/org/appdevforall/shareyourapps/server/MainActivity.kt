package org.appdevforall.shareyourapps.server

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.appdevforall.shareyourapps.server.HelpUi.help

/**
 * A minimal Manage Server screen: start and stop the server, set the port, and look at the
 * access log. The directory to serve always comes from the plugin; Start will serve the last one
 * published, so there's nothing here to go stale.
 */
class MainActivity : Activity() {

    private val pal by lazy { Palette.of(this) }

    companion object {
        private const val SHARE_CAPTION = "Give someone nearby a copy to serve from their own phone. They can serve it with their own PIN. They can't change it or pass it on."
        private const val IMPORT_CAPTION = "Serve a copy of someone else's store, with their permission. You'll need to be near their phone, on the same network."
        /** Asking for the microphone after MCP control is turned on, for import_listen. */
        private const val REQUEST_MCP_MIC = 4
    }

    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var addressRow: LinearLayout
    private lateinit var hotspotInfo: TextView
    private lateinit var lastPublished: TextView
    private lateinit var portField: EditText
    private lateinit var grant: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var logView: TextView
    private lateinit var logPan: HorizontalScrollView
    /** Refreshes the MCP switch and its state line; set in onCreate, observed while the screen is showing. */
    private var mcpListener: () -> Unit = {}
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshLog(); ticker.postDelayed(this, 2000) }
    }
    private val statusListener: () -> Unit = { showStatus(); refreshLog(); refreshLastPublished() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        status = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        address = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 4, 0, pad / 4)
            setTextColor(pal.link)
        }
        // The address, with a copy button on its right, as the Share screen's codes have. The address itself isn't
        // selectable: its long-press shows its help.
        addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val size = (48 * resources.displayMetrics.density).toInt()
            addView(android.widget.ImageButton(this@MainActivity).apply {
                setImageDrawable(CopyIcon(pal.ink))
                val inset = (12 * resources.displayMetrics.density).toInt(); setPadding(inset, inset, inset, inset)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val ripple = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                setBackgroundResource(ripple.resourceId)
                contentDescription = "Copy the address"
                setOnClickListener { copyAddress() }
            }, LinearLayout.LayoutParams(size, size))
        }
        hotspotInfo = TextView(this).apply { textSize = 15f; setTextIsSelectable(true); setPadding(0, 0, 0, pad / 4) }
        grant = Button(this).apply { text = "Grant storage access"; setOnClickListener { requestStorage() } }
        lastPublished = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(HostService.EXTRA_PORT, Store.DEFAULT_PORT).toString())
        }
        startButton = Button(this).apply { text = "Start"; setOnClickListener { startServer() } }
        stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, HostService::class.java).setAction(HostService.ACTION_STOP))
            }
        }
        // When to run. Saved immediately; the service checks it each time the screen turns off.
        val alwaysId = View.generateViewId()
        val screenId = View.generateViewId()
        val runMode = RadioGroup(this).apply {
            addView(RadioButton(this@MainActivity).help("server.run_mode").apply { id = alwaysId; text = "Run always" })
            addView(RadioButton(this@MainActivity).help("server.run_mode").apply { id = screenId; text = "Pause when the screen turns off" })
            check(if (prefs.getString(HostService.PREF_RUN_MODE, "always") == "screenOff") screenId else alwaysId)
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putString(HostService.PREF_RUN_MODE, if (id == screenId) "screenOff" else "always").apply()
            }
        }

        // The log's size limit, saved as it's typed when it's a whole number from 0 to 100. Anything else isn't
        // saved, and says so plainly — in red, under the field and beside it — with the valid range and the limit kept.
        val logHint = "0 turns logging off. Takes effect the next time the server starts."
        val logNote = TextView(this).apply { textSize = 12f; setTextColor(pal.muted); text = logHint }
        val logSize = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(HostService.PREF_LOG_MB, HostService.DEFAULT_LOG_MB).toString())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val text = e?.toString().orEmpty()
                    val mb = LogSize.mb(text)
                    if (mb != null) {
                        prefs.edit().putInt(HostService.PREF_LOG_MB, mb).apply()
                        error = null
                        logNote.setTextColor(pal.muted); logNote.text = logHint
                    } else {
                        val problem = LogSize.problem(text)
                        val kept = prefs.getInt(HostService.PREF_LOG_MB, HostService.DEFAULT_LOG_MB)
                        error = problem
                        logNote.setTextColor(pal.bad); logNote.text = "$problem Until then, the limit stays at $kept MB."
                    }
                }
            })
        }

        // Newest lines first, so the latest is always in view without scrolling. Long lines
        // scroll sideways rather than wrapping: the pane around the text does the scrolling, and
        // the text doesn't scroll itself, which is what used to snap it back to the left edge.
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logPan = HorizontalScrollView(this).apply { addView(logView) }

        // Long-press help on headings and buttons; text fields keep their own long-press.
        val helpButton = Button(this).apply { text = "Help"; setOnClickListener { HelpActivity.open(this@MainActivity, "index.html") } }
        column.addView(TextView(this).apply { text = "Long-press a heading or button for help."; textSize = 12f; alpha = 0.7f; setPadding(pad / 4, 0, 0, pad / 2) })

        column.addView(panel("Status", "server.status",
            status.help("server.status"),
            addressRow.also { address.help("server.status") },
            hotspotInfo.help("server.hotspot"),
            Button(this).apply {
                text = "Show QR codes for buyers"
                setOnClickListener { startActivity(Intent(this@MainActivity, ServeActivity::class.java)) }
            }.help("server.serve"),
            grant.help("server.grant"),
            lastPublished.help("server.last_published"),
        ))

        // MCP control, off by default: apps on this phone may start and stop the server, share and import.
        // Turning it on asks first. The token is never shown, only copied, and marked sensitive.
        val mcpSwitch = android.widget.Switch(this).apply { text = "MCP control"; textSize = 16f }
        val mcpState = TextView(this).apply { textSize = 13f; setTextColor(pal.muted) }
        val mcpTokenRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "Copy token"
                setOnClickListener {
                    val clip = ClipData.newPlainText("MCP token", McpService.token(this@MainActivity))
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this@MainActivity, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.help("server.mcp_token"))
            addView(Button(this@MainActivity).apply {
                text = "New token"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Make a new token?")
                        .setMessage("Apps using the old token will be refused until they're given the new one.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("New token") { _, _ -> McpService.newToken(this@MainActivity) }
                        .show()
                }
            }.help("server.mcp_token"))
        }
        var settingMcpSwitch = false
        fun showMcp() {
            val on = McpService.enabled(this@MainActivity)
            settingMcpSwitch = true; mcpSwitch.isChecked = on; settingMcpSwitch = false
            mcpState.text = if (on) McpStatus.text else "Off. Turn on to let apps on this phone control the server."
            mcpTokenRow.visibility = if (on) View.VISIBLE else View.GONE
        }
        mcpListener = { showMcp() }
        mcpSwitch.setOnCheckedChangeListener { _, on ->
            if (settingMcpSwitch) return@setOnCheckedChangeListener
            if (!on) { McpService.setEnabled(this@MainActivity, false); showMcp(); return@setOnCheckedChangeListener }
            // Not on until the person has read what it allows.
            settingMcpSwitch = true; mcpSwitch.isChecked = false; settingMcpSwitch = false
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Turn on MCP control?")
                .setMessage("Apps on this phone will be able to start and stop the server, share this store and import a store.\n\n" +
                    "They can't change the store or approve a copy. You still tap Allow.\n\n" +
                    "This also lets the app use the microphone to hear the sound a sharing phone plays. It only listens while " +
                    "it's receiving a handshake, and Android shows its microphone indicator while it does.\n\n" +
                    "Each app needs the token: use Copy token, below the switch.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn on") { _, _ ->
                    McpService.setEnabled(this@MainActivity, true); showMcp()
                    // The microphone, for import_listen: asked now, while the person is here. Refused: MCP is on anyway.
                    if (!SoundIo.canListen(this@MainActivity))
                        this@MainActivity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MCP_MIC)
                }
                .show()
        }

        column.addView(panel("Server", "server.overview",
            // Port and its field on one line.
            LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply { text = "Port"; setPadding(0, 0, pad / 2, 0) }.help("server.port"))
                addView(portField.apply { setEms(5) }, LinearLayout.LayoutParams(-2, -2))
            },
            LinearLayout(this).apply {
                addView(startButton.help("server.start")); addView(stopButton.help("server.stop")); addView(helpButton.help("server.overview"))
            },
            mcpSwitch.help("server.mcp"),
            mcpState.help("server.mcp"),
            mcpTokenRow,
        ))

        // Sharing a copy of the store with another seller (design, Appendix E; screen 1).
        shareButton = Button(this).apply { text = "Share this store…"; setOnClickListener { ShareActivity.open(this@MainActivity) } }
        shareCaption = TextView(this).apply { text = SHARE_CAPTION; textSize = 15f; setPadding(0, 0, 0, pad / 4) }
        column.addView(panel("Copies of this store", "server.copies",
            shareCaption.help("server.copies"),
            shareButton.help("server.share"),
            TextView(this).apply { text = "You can set an expiration date."; textSize = 13f; alpha = 0.75f },
        ))

        // The imported copy, when there is one (design, Appendix E; screen 7). Rebuilt on every refresh.
        copyHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(copyHolder)

        // Importing someone else's store (design, Appendix E; screens 4 and 4b).
        importCaption = TextView(this).apply { text = IMPORT_CAPTION; textSize = 15f; setPadding(0, 0, 0, pad / 4) }
        importButton = Button(this).apply { text = "Import a store…"; setOnClickListener { startActivity(Intent(this@MainActivity, ImportActivity::class.java)) } }
        column.addView(panel("Import a store", "server.import",
            importCaption.help("server.import"),
            importButton.help("server.import"),
        ))


        column.addView(panel("When to run", "server.run_mode", runMode))

        column.addView(panel("Wi-Fi Direct hotspot", "server.p2p_settings", p2pSettings()))

        column.addView(panel("Logs", "server.log",
            label("Log size limit (MB)").help("server.log_size"),
            logSize,
            logNote,
            label("Access log — newest first, updates every 2 seconds").help("server.log"),
            LinearLayout(this).apply {
                addView(Button(this@MainActivity).apply {
                    text = "View logs"; setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
                }.help("server.view_logs"))
                addView(Button(this@MainActivity).help("server.export_logs").apply {
                    text = "Export logs"
                    setOnClickListener {
                        val names = LogProvider.files(this@MainActivity).map { it.name }
                        if (names.isEmpty()) status.text = "There are no log files yet." else LogProvider.share(this@MainActivity, names)
                    }
                }.help("server.export_logs"))
            },
            logPan,
            // Where the logs are: private storage, which other apps and file managers can't open.
            label("Log folder").help("server.log_folder"),
            TextView(this).apply {
                text = Store.logDir(this@MainActivity).absolutePath
                typeface = Typeface.MONOSPACE; textSize = 12f
                // Tap to select the whole path.
                setTextIsSelectable(true); setSelectAllOnFocus(true)
            },
            TextView(this).apply {
                text = "This folder is private to this app, because the logs record buyers' addresses. Other apps and file managers can't open it; use Export logs to send the logs elsewhere."
                textSize = 12f; alpha = 0.7f
            },
        ))
        // A slightly darker page, so the raised panels stand up from it.
        // Which build this is, at a glance: useful when two phones must match.
        column.addView(TextView(this).apply {
            val built = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(BuildConfig.BUILD_TIME))
            text = "Share Your Apps Server ${BuildConfig.VERSION_NAME} · built $built"
            textSize = 12f; alpha = 0.7f; gravity = android.view.Gravity.CENTER; setPadding(0, pad / 2, 0, pad)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(pal.screen); addView(column) }.also { SystemBars.fit(it) })

        // Only when missing: asking again opens the system's permission screen briefly even when it's granted,
        // which pauses and resumes this screen, so onResume ran twice (and started MCP control twice).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        grant.visibility = if (Store.hasStorageAccess(this)) View.GONE else View.VISIBLE
        ServerStatus.observe(statusListener) // runs the listener once now, which refreshes everything
        // MCP control starts when the app is opened with the switch on (nothing starts it at boot).
        McpService.start(this)
        McpStatus.observe(mcpListener)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ServerStatus.remove(statusListener)
        McpStatus.remove(mcpListener)
        ticker.removeCallbacks(tick)
    }

    /**
     * One topic in a raised panel, with a fake 3D bevel: a face shading from white at the top to
     * light grey at the bottom, a thin inner highlight, and a soft shadow offset below and to
     * the right.
     */
    private fun panel(title: String, helpTag: String, vararg views: View): View {
        val dp = resources.displayMetrics.density
        val lift = (5 * dp).toInt()
        val radius = 12 * dp
        val shadow = GradientDrawable().apply { setColor(pal.shadow); cornerRadius = radius }
        // The lower edge of the bevel: a darker rim showing just below and right of the face.
        val rim = GradientDrawable().apply { setColor(pal.rim); cornerRadius = radius }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.panelTop, pal.panelBottom)).apply {
            cornerRadius = radius; setStroke((1 * dp).toInt(), pal.stroke)
        }
        val highlight = GradientDrawable().apply { setColor(0x00000000); cornerRadius = radius - dp; setStroke((1.5f * dp).toInt(), pal.highlight) }
        val edge = (2 * dp).toInt()
        val bg = LayerDrawable(arrayOf(shadow, rim, face, highlight)).apply {
            setLayerInset(0, lift, lift, 0, 0)                                  // shadow: down and right
            setLayerInset(1, edge, edge, lift - edge, lift - edge)              // the rim, peeking out below the face
            setLayerInset(2, 0, 0, lift, lift)                                  // the face
            setLayerInset(3, (1 * dp).toInt(), (1 * dp).toInt(), lift + (1 * dp).toInt(), lift + (1 * dp).toInt())
        }
        val pad = (14 * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(pad, (12 * dp).toInt(), pad + lift, pad + lift)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * dp).toInt() }
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.ink)
                setPadding(0, 0, 0, (4 * dp).toInt())
            }.help(helpTag))
            views.forEach { addView(it) }
        }
    }

    /**
     * The Wi-Fi Direct hotspot's name and password. The limits show as you type, the trade-off of
     * changing them is stated up front, and nothing changes until Save.
     */
    private fun p2pSettings(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ink = pal.ink; val muted = pal.muted; val bad = pal.bad; val good = pal.good
        fun note(size: Float = 12f) = TextView(this).apply { textSize = size; setTextColor(muted) }

        box.addView(note(13f).apply {
            setTextColor(ink)
            text = "When this phone isn't on Wi-Fi, the server starts a hotspot that buyers join. It uses this name and password. " +
                "If you change either one, phones that joined before won't reconnect by themselves: buyers will need to join again, " +
                "using the new details on the Serve screen. Changes take effect the next time the hotspot starts."
        })

        val room = HotspotSettings.MAX_NAME_BYTES - HotspotSettings.PREFIX.length
        box.addView(TextView(this).apply { text = "Network name"; setPadding(0, 12, 0, 0); setTypeface(typeface, Typeface.BOLD) }.help("server.hotspot_name"))
        val suffix = EditText(this).apply { setSingleLine(); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = HotspotSettings.PREFIX; typeface = Typeface.MONOSPACE; textSize = 15f })
            addView(suffix)
        })
        val nameLimit = note()
        box.addView(nameLimit)

        box.addView(TextView(this).apply { text = "Password"; setPadding(0, 12, 0, 0); setTypeface(typeface, Typeface.BOLD) }.help("server.hotspot_password"))
        // The hotspot's password is the app's own, shown to buyers anyway, so it's visible here.
        val password = EditText(this).apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(password)
        val passLimit = note()
        box.addView(passLimit)

        val result = note(13f).apply { setPadding(0, 8, 0, 0) }

        fun showLimits() {
            val used = HotspotSettings.name(suffix.text.toString()).toByteArray(Charsets.UTF_8).size - HotspotSettings.PREFIX.length
            val nameProblem = HotspotSettings.nameProblem(suffix.text.toString())
            nameLimit.setTextColor(if (nameProblem != null) bad else muted)
            nameLimit.text = when {
                used > room -> "Too long: $used of $room used. Shorten it by ${used - room}."
                else -> "$used of $room used after ${HotspotSettings.PREFIX}. Letters, digits, spaces and punctuation count 1 each; accented letters count 2, and emoji 4."
            } + if (nameProblem != null && used <= room) " " + nameProblem else ""
            val n = password.text.length
            val passProblem = HotspotSettings.passwordProblem(password.text.toString())
            passLimit.setTextColor(if (passProblem != null) bad else muted)
            passLimit.text = "$n characters. It needs 8 to 63: letters, digits, spaces and ordinary punctuation, no accented letters or emoji." +
                if (passProblem != null && n in 8..63) " $passProblem" else ""
        }
        fun showUnsaved() {
            val (savedName, savedPass) = Hotspot.credentials(this)
            val changed = HotspotSettings.name(suffix.text.toString()) != savedName || password.text.toString() != savedPass
            if (changed) { result.setTextColor(ink); result.text = "Not saved yet: the hotspot still uses its current name and password." }
            else if (result.text.startsWith("Not saved yet")) result.text = ""
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { showLimits(); showUnsaved() }
        }
        val (name, pass) = Hotspot.credentials(this)
        suffix.setText(name.removePrefix(HotspotSettings.PREFIX))
        password.setText(pass)
        suffix.addTextChangedListener(watcher)
        password.addTextChangedListener(watcher)
        showLimits()

        // Side by side, each taking half the row; a label wraps rather than pushing the other off the edge.
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).help("server.hotspot_save").apply {
                text = "Save name & password"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val problem = HotspotSettings.nameProblem(suffix.text.toString()) ?: HotspotSettings.passwordProblem(password.text.toString())
                    if (problem != null) { result.setTextColor(bad); result.text = "Not saved. $problem"; return@setOnClickListener }
                    val newName = HotspotSettings.name(suffix.text.toString())
                    val (savedName, savedPass) = Hotspot.credentials(this@MainActivity)
                    if (newName == savedName && password.text.toString() == savedPass) {
                        result.setTextColor(muted); result.text = "Nothing to save: that's already the name and password in use."
                        return@setOnClickListener
                    }
                    Hotspot.saveCredentials(this@MainActivity, newName, password.text.toString())
                    result.setTextColor(good)
                    result.text = "Saved. The hotspot will be “$newName” with the new password from the next time it starts — " +
                        "tap Stop, then Start, to switch now. Buyers will need to join again with the new details."
                }
            }.help("server.p2p_settings"))
            addView(Button(this@MainActivity).help("server.hotspot_suggest").apply {
                text = "Suggest a password"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    password.setText(HotspotSettings.randomPassword())
                    result.setTextColor(ink)
                    result.text = "A new 16-character password is in the box above, but nothing has changed yet. " +
                        "Tap Save name & password to switch the hotspot to it; until you do, it keeps its current password."
                }
            }.help("server.p2p_settings"))
        })
        box.addView(result)
        return box
    }

    private lateinit var shareButton: Button
    private lateinit var copyHolder: LinearLayout
    private var shownCopy: String? = null

    /** Screen 7: the imported copy — read-only, its expiry, serving it with the seller's own PIN, and removing it. */
    private fun refreshCopy() {
        if (!::copyHolder.isInitialized) return
        val imported = ImportJob.importedDir(this)
        // An expired copy is deleted as soon as it's due (Appendix E.5), unless it's being served:
        // then the server process lets downloads under way finish first.
        val serving = ServerStatus.servingCopy && ServerStatus.url != null
        if (!serving) CopyExpiry.enforce(imported, ExpiryClock(CopyExpiry.clockFile(filesDir)))
        val copy = ImportJob.currentCopy(this)
        val notice = CopyExpiry.notice(imported)
        val key = "${copy?.storeId}|${copy?.created}|$serving|${notice?.storeName}"
        if (key == shownCopy) return
        shownCopy = key
        copyHolder.removeAllViews()
        val pad = (16 * resources.displayMetrics.density).toInt()
        // Screen 8: a copy that expired, until the seller dismisses it.
        if (notice != null) copyHolder.addView(panel("A store has expired", "server.copy_expired",
            // The same words however the copy came to expire: nothing says why.
            TextView(this).apply {
                text = "Your copy of ${notice.storeName} has expired, and has been deleted from this phone. " +
                    "Buyers can no longer open it. To serve it again, ask its owner for a new copy."
                textSize = 15f
            },
            Button(this).help("server.copy_expired").apply { text = "OK"; setOnClickListener { CopyExpiry.dismiss(imported); shownCopy = null; refreshCopy() } },
        ))
        if (copy == null) return
        val prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE)
        val expires = copy.expires?.let {
            val days = java.time.Duration.between(java.time.Instant.now(), it).toDays()
            "Expires " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date.from(it)) +
                if (days >= 1) " · in $days ${if (days == 1L) "day" else "days"}" else " · today"
        } ?: "Never expires"
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6)); setEms(5)
            setText(prefs.getString(HostService.PREF_COPY_PIN, "").orEmpty())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    prefs.edit().putString(HostService.PREF_COPY_PIN, e?.toString()?.takeIf { it.isNotEmpty() }).apply()
                }
            })
        }
        copyHolder.addView(panel(copy.storeName, "server.copy",
            TextView(this).apply { text = "Read-only · $expires"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }.help("server.copy"),
            TextView(this).apply {
                text = if (serving) "Serving it now." else "An imported copy of someone else's store. You can serve it; it can't be changed or passed on."
                textSize = 14f; setPadding(0, pad / 4, 0, pad / 4)
            },
            Button(this).apply {
                text = if (serving) "Serve it again, with this PIN" else "Serve this store"
                setOnClickListener { startForegroundService(Intent(this@MainActivity, HostService::class.java).setAction(HostService.ACTION_SERVE_COPY)) }
            }.help("server.copy_serve"),
            TextView(this).apply { text = "Set a PIN or leave blank for no PIN"; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 2, 0, 0) }.help("server.copy_pin"),
            LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(pin, LinearLayout.LayoutParams(-2, -2))
                addView(TextView(this@MainActivity).apply { text = "Up to 6 digits. Buyers enter it before reaching the store."; textSize = 13f; setPadding(pad / 2, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
            },
            Button(this).apply {
                text = "Remove this store"; setTextColor(pal.danger)
                setOnClickListener { removeCopy(copy) }
            }.help("server.copy_remove"),
        ))
    }

    private fun removeCopy(copy: SharePackage.Description) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove ${copy.storeName}?")
            .setMessage("It's deleted from this phone. To serve it again, ask its owner for a new copy.")
            .setPositiveButton("Remove") { _, _ ->
                if (ServerStatus.servingCopy && ServerStatus.url != null)
                    startForegroundService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
                val dir = ImportJob.importedDir(this)
                java.io.File(dir, copy.storeId).deleteRecursively(); java.io.File(dir, "${copy.storeId}.json").delete()
                shownCopy = null; refreshCopy()
            }
            .setNegativeButton("Keep it", null)
            .show()
    }

    /** A phone serving its own store can't import one (design, Appendix E.1; screen 4b). */
    private lateinit var shareCaption: TextView
    private lateinit var importCaption: TextView
    private lateinit var importButton: Button

    /**
     * The sharing panels say whether their buttons can be used, and why not, before anyone taps:
     * sharing needs this phone's own store to be served; importing needs no store served at all,
     * so a copy is never replaced under a running server (design, Appendix E.1).
     */
    private fun refreshSharingPanels() {
        if (!::shareButton.isInitialized) return
        val serving = ServerStatus.url != null
        shareButton.isEnabled = serving && !ServerStatus.servingCopy
        shareCaption.text = when {
            !serving -> "Start serving your store to share it."
            ServerStatus.servingCopy -> "This phone is serving a copy of someone else's store. Copies can't be passed on."
            else -> SHARE_CAPTION
        }
        importButton.isEnabled = !serving
        importCaption.text = if (serving) "This phone is serving a store. To import one, stop serving it first, with Stop above, or import on another phone."
            else IMPORT_CAPTION
    }

    /**
     * Start is for a stopped server and Stop for a running one, so the screen shows which it is.
     * Between a tap on Start and the server being up, Start stays enabled: ServerStatus has no
     * "starting" state.
     */
    private fun refreshServerButtons() {
        if (!::startButton.isInitialized) return
        val serving = ServerStatus.url != null
        startButton.isEnabled = !serving
        stopButton.isEnabled = serving
        startButton.fill(pal.startFill)
        stopButton.fill(pal.stopFill)

    }

    /**
     * A solid fill while the button is enabled, with white text on it. A disabled button is grey, like the app's other
     * disabled buttons, with the text faded. Call it after setting [Button.isEnabled].
     */
    private fun Button.fill(on: Int) {
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (isEnabled) on else pal.fillOff)
        val textStates = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled))
        setTextColor(android.content.res.ColorStateList(textStates, intArrayOf(pal.onFill, pal.onFillOff)))
    }

    private fun showStatus() {
        refreshServerButtons()
        // Copies can't be passed on, so sharing needs this phone's own store.
        refreshSharingPanels()
        refreshCopy()
        status.text = ServerStatus.text
        val h = ServerStatus.hotspot
        hotspotInfo.visibility = if (h == null) View.GONE else View.VISIBLE
        hotspotInfo.text = h.orEmpty()
        val url = ServerStatus.url
        addressRow.visibility = if (url == null) View.GONE else View.VISIBLE
        if (url != null) address.text = url
    }

    /** What Start will serve: this phone's own last published store, or its imported copy (the same rule as HostService). */
    private fun refreshLastPublished() {
        val prefs = getSharedPreferences(HostService.PREFS, MODE_PRIVATE)
        val last = prefs.getString(HostService.EXTRA_DIR, null)
        val copy = ImportJob.currentCopy(this)
        val line = when {
            copy != null && (last == null || prefs.getString(HostService.PREF_SOURCE, "own") == "copy") ->
                "Start will serve the imported copy of ${copy.storeName}, below."
            last != null -> "Start will serve the last store published: $last"
            else -> "Nothing published yet — publish from the Share Your Apps plugin, or import a store, below."
        }
        // "Start" in a hairline box, naming the button above.
        lastPublished.text = if (line.startsWith("Start ")) android.text.SpannableString(line).apply {
            setSpan(BoxSpan(resources.displayMetrics.density), 0, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else line
    }

    /** Copies the store's address. Android 13 and later confirm a copy themselves; older versions get a message. */
    private fun copyAddress() {
        val url = ServerStatus.url ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Share Your Apps address", url))
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, "Address copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startServer() {
        val port = portField.text.toString().toIntOrNull()
        when {
            port == null || port !in 1024..65535 -> status.text = "The port must be between 1024 and 65535."
            // Kept for MCP control, which listens on 127.0.0.1:6176 (and share discovery uses UDP 6176).
            port == McpHttp.DEFAULT_PORT -> status.text = "Port ${McpHttp.DEFAULT_PORT} is kept for MCP control. Choose another port, such as ${Store.DEFAULT_PORT}."
            !Store.hasStorageAccess(this) -> status.text = "Grant storage access first."
            // Not on Wi-Fi: a hotspot will be needed, and starting one needs a permission.
            Store.needsHotspot() && !Store.hasHotspotPermission(this) -> requestPermissions(Store.hotspotPermissions(), 3)
            else -> startForegroundService(
                Intent(this, HostService::class.java).setAction(HostService.ACTION_START)
                    .putExtra(HostService.EXTRA_PORT, port)
            )
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        grant.visibility = if (Store.hasStorageAccess(this)) View.GONE else View.VISIBLE
        if (requestCode == 3) {
            if (Store.hasHotspotPermission(this)) startServer()
            else status.text = "Without that permission, the server can't start a hotspot. Connect to Wi-Fi instead, or tap Start to be asked again."
        }
        // Allowed or refused: MCP control starts again, with or without the microphone type, and its line says which.
        if (requestCode == REQUEST_MCP_MIC) McpService.start(this)
    }

    /**
     * Updates the pane only when the log has changed, keeps the sideways scroll position, and
     * leaves it alone while you have text selected, so reading and selecting aren't interrupted.
     */
    private fun refreshLog() {
        if (logView.hasSelection()) return
        val f = File(Store.logDir(this), StoreLog.FILE_NAME)
        val next = if (f.exists()) f.readLines().takeLast(200).asReversed().joinToString("\n") else "(no log yet)"
        if (next == logView.text.toString()) return
        val x = logPan.scrollX
        logView.text = next
        logPan.post { logPan.scrollTo(x, 0) }
    }
}
