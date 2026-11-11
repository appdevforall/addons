package helptest

import org.appdevforall.shareyourapps.server.*
import java.io.File
import java.sql.DriverManager

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** HelpSql over JDBC, returning the same value types Android's cursor gives: String, Long, ByteArray, null. */
fun jdbc(path: String) = DriverManager.getConnection("jdbc:sqlite:$path").let { conn ->
    HelpSql { sql, args ->
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val cols = rs.metaData.columnCount
                val out = mutableListOf<List<Any?>>()
                while (rs.next()) out += (1..cols).map { c ->
                    when (val v = rs.getObject(c)) { is Int -> v.toLong(); else -> v }
                }
                out
            }
        }
    }
}

/** A check this machine can't run: reported, but not a failure. */
fun skip(name: String, why: String) = println("SKIP  $name ($why)")

/** The first of [paths] that exists: Gradle runs tests from the app folder, others may run them from the project root. */
fun projectFile(vararg paths: String): File =
    paths.map(::File).firstOrNull { it.exists() } ?: error("couldn't find any of: " + paths.joinToString() + " (from " + File("").absolutePath + ")")

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    // The SQLite driver loads a native library, built per platform; where it can't load, these checks can't run.
    val driverProblem = try { DriverManager.getConnection("jdbc:sqlite::memory:").close(); null } catch (e: Throwable) { "${e.javaClass.simpleName}: ${e.message}" }
    if (driverProblem != null) {
        skip("the help database checks", "the SQLite driver can't load here — $driverProblem")
        return failedNames.toList()
    }
    val help = HelpStore(jdbc(projectFile("src/main/assets/web_server_documentation.db", "app/src/main/assets/web_server_documentation.db").path))

    val t = help.tooltip("server.log")
    check("tooltip found by tag in the server's category", t != null && t.summary.startsWith("What the server has done"))
    check("its detail comes back", t!!.detail.contains("2 seconds"))
    check("its button, in order", t.buttons == listOf("Reading the access log" to "log.html"))
    check("an entry without buttons of its own gets its page's section", help.tooltip("server.port")!!.buttons == listOf("Serving the store" to "serving.html#start"))
    check("every Manage Server tag exists", listOf("overview", "status", "grant", "last_published", "port", "start", "stop", "run_mode", "log_size", "log", "copies", "share", "import", "copy", "copy_serve", "copy_pin", "copy_remove", "copy_expired").all { help.tooltip("server.$it") != null })
    check("every tag the screens use exists", listOf("server.hotspot_name", "server.hotspot_password", "server.hotspot_save", "server.hotspot_suggest",
        "share.cancel", "share.deny", "share.again", "share.done", "share.expiry", "share.allow",
        "import.cant", "import.code", "import.continue", "import.retry", "import.cancel", "import.serve_now", "import.done",
        "serve.close", "serve.done", "serve.show_join", "server.serve_wifi", "server.port", "server.run_mode", "server.log_size",
        "server.export_logs", "server.copy_expired", "server.copy_pin").all { help.tooltip(it) != null })
    // Tier 3 for every entry, and the ⓘ panel's rows.
    val dbPath = projectFile("src/main/assets/web_server_documentation.db", "app/src/main/assets/web_server_documentation.db").path
    val allTags = jdbc(dbPath).rows("SELECT tag FROM Tooltips", emptyArray()).map { it[0] as String }
    val unlinked = allTags.filter { help.tooltip(it)?.buttons.isNullOrEmpty() }
    check("every entry has a tier-3 button (${allTags.size} entries; without: $unlinked)", allTags.size >= 49 && unlinked.isEmpty())
    val broken = allTags.flatMap { t -> help.tooltip(t)!!.buttons.map { it.second } }.distinct().filter { uri ->
        val page = help.page(uri.substringBefore('#'))?.bytes?.toString(Charsets.UTF_8); val frag = uri.substringAfter('#', "")
        page == null || (frag.isNotEmpty() && "id=$frag" !in page && "id=\"$frag\"" !in page) }
    check("…and every button's page and section exist (broken: $broken)", broken.isEmpty())
    val rows = help.debug("share.deny").toMap()
    check("the ⓘ panel names the database and its version", rows["Database"]?.any { "version 1" in it } == true)
    check("…the tooltip's row, category and tag", rows["Tooltips"]?.let { r -> r.any { "rowid" in it } && r.any { "shareyourapps_server" in it } && r.any { "tag share.deny" in it } } == true)
    check("…its button's row and target", rows["TooltipButtons"]?.any { "sharing.html#allow" in it } == true)
    check("…and the page's content row", rows["Content: sharing.html"]?.any { "text/html, compression none" in it } == true)
    check("…and says so for a tag with no row", help.debug("no.such.tag").toMap()["Tooltips"]?.single()?.contains("no row") == true)
    check("the share screen's tags exist", listOf("code", "app", "allow", "expiry").all { help.tooltip("share.$it") != null } &&
        listOf("code", "name", "check").all { help.tooltip("import.$it") != null })
    check("unknown tag gives null", help.tooltip("server.nope") == null)

    val p = help.page("index.html")
    check("index.html is HTML", p != null && p.mimeType == "text/html" && String(p.bytes).contains("<title>Share Your Apps Server</title>"))
    check("pages link to each other", String(p!!.bytes).contains("href=\"log.html\"") && String(help.page("log.html")!!.bytes).contains("href=\"index.html\""))
    check("the x.html test page is there", String(help.page("x.html")!!.bytes).contains("load from the help database"))
    check("unknown page gives null", help.page("nope.html") == null)

    // Edge cases in a scratch database with the same schema.
    val scratch = File.createTempFile("help", ".db").apply { delete() }
    val schema = projectFile("../help/build_help_db.py", "help/build_help_db.py").readText().substringAfter("db.executescript(\"\"\"").substringBefore("\"\"\")")
    DriverManager.getConnection("jdbc:sqlite:${scratch.path}").use { c ->
        c.createStatement().executeUpdate("PRAGMA foreign_keys=OFF")
        schema.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { c.createStatement().executeUpdate(it) }
        c.createStatement().executeUpdate("INSERT INTO Languages VALUES (1,'en')")
        c.createStatement().executeUpdate("INSERT INTO ContentTypes VALUES (1,'text/html','none'),(2,'text/html','brotli')")
        val big = ByteArray(HelpStore.CHUNK_SIZE * 2 + 500) { (it % 251).toByte() }
        val ins = c.prepareStatement("INSERT INTO Content (path, content, contentTypeID, languageId, templateId) VALUES (?,?,?,1,?)")
        fun put(path: String, bytes: ByteArray, type: Int = 1, template: Int = 0) { ins.setString(1, path); ins.setBytes(2, bytes); ins.setInt(3, type); ins.setInt(4, template); ins.executeUpdate() }
        put("big.html", big.copyOfRange(0, HelpStore.CHUNK_SIZE))
        put("big.html-1", big.copyOfRange(HelpStore.CHUNK_SIZE, HelpStore.CHUNK_SIZE * 2))
        put("big.html-2", big.copyOfRange(HelpStore.CHUNK_SIZE * 2, big.size))
        put("exact.html", ByteArray(HelpStore.CHUNK_SIZE) { 7 })   // exactly one chunk long, no continuation row
        put("br.html", ByteArray(10), type = 2)
        put("tpl.html", ByteArray(10), template = 3)
        val s = HelpStore(jdbc(scratch.path))
        check("a page split across three chunks is reassembled", s.page("big.html")!!.bytes.contentEquals(big))
        check("a page exactly one chunk long, with no continuation, is read whole", s.page("exact.html")!!.bytes.size == HelpStore.CHUNK_SIZE)
        check("a Brotli row is refused clearly", runCatching { s.page("br.html") }.exceptionOrNull()?.message?.contains("brotli") == true)
        check("a templated row is refused clearly", runCatching { s.page("tpl.html") }.exceptionOrNull()?.message?.contains("template") == true)
    }
    scratch.delete()
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class HelpChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
