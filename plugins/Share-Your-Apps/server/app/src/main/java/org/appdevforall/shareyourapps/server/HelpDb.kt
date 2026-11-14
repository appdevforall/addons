package org.appdevforall.shareyourapps.server

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The bundled help database. SQLite can't open a file inside the APK, so the asset is copied
 * to the app's databases directory — once per app version, so an update brings new help — and
 * then only ever opened read-only.
 */
object HelpDb {

    private const val NAME = "web_server_documentation.db"
    private const val PREF_VERSION = "helpDbVersion"
    private var store: HelpStore? = null

    @Synchronized
    fun store(ctx: Context): HelpStore {
        store?.let { return it }
        val app = ctx.applicationContext
        val file = app.getDatabasePath(NAME)
        val prefs = app.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        if (!file.exists() || prefs.getLong(PREF_VERSION, -1) != version) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            app.assets.open(NAME).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(file)) throw HelpException("couldn't install the help database")
            prefs.edit().putLong(PREF_VERSION, version).apply()
        }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return HelpStore { sql, args ->
            db.rawQuery(sql, args).use { c ->
                val rows = mutableListOf<List<Any?>>()
                while (c.moveToNext()) rows += (0 until c.columnCount).map { i -> value(c, i) }
                rows
            }
        }.also { store = it }
    }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
        else -> c.getString(i)
    }
}
