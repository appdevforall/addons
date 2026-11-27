package com.donyarmyengineering.deliveryfees

import android.content.Context
import android.database.Cursor
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.appdevforall.sqlitejs.SqliteJs

/** An order from the grocery client's shop. */
@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customer: String,
    val subtotal: Double,
    @ColumnInfo(name = "distance_km") val distanceKm: Double,
    val weekend: Boolean,
)

/** An order and the delivery fee that the JavaScript rule gives it. */
data class OrderWithFee(
    val id: Long,
    val customer: String,
    val subtotal: Double,
    @ColumnInfo(name = "distance_km") val distanceKm: Double,
    val weekend: Boolean,
    val fee: Double,
)

@Dao
interface OrderDao {
    @Insert
    suspend fun insert(orders: List<Order>)

    @Query("SELECT count(*) FROM orders")
    suspend fun count(): Int

    // Room checks each query against its own SQLite when it builds the app, and that SQLite has
    // no JavaScript functions, so @SkipQueryVerification turns the check off for this query.
    /** Every order with its fee, from the JavaScript function delivery_fee. */
    @SkipQueryVerification
    @Query("SELECT *, delivery_fee(subtotal, distance_km, weekend) AS fee FROM orders ORDER BY id")
    suspend fun withFees(): List<OrderWithFee>
}

@Database(entities = [Order::class], version = 1, exportSchema = false)
abstract class OrderDatabase : RoomDatabase() {
    abstract fun orders(): OrderDao

    companion object {
        private const val NAME = "orders.db"
        private const val RULE = "delivery_fee"
        private const val RULE_TIME_LIMIT_MS = 2_000L

        @Volatile
        private var instance: OrderDatabase? = null

        /**
         * The app's database, opened with sqlite-js on every connection, so its queries can call
         * the delivery-fee rule stored in it. The first call does disk work, so call it off the
         * main thread.
         */
        fun get(context: Context): OrderDatabase =
            instance ?: synchronized(this) {
                instance ?: open(context.applicationContext).also { instance = it }
            }

        private fun open(context: Context): OrderDatabase {
            // Creates the js_functions table, where the rule is stored, before Room opens.
            withConnection(context, context.getDatabasePath(NAME).path) { db ->
                db.execute("SELECT js_init_table()")
            }
            val factory = RequerySQLiteOpenHelperFactory(
                listOf(
                    RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
                        // Loads the rule stored in the database when each connection opens,
                        // which runs its code: only for a database the app made itself.
                        config.customExtensions.add(
                            SQLiteCustomExtension(
                                SqliteJs.libraryPath(context),
                                SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE,
                            ),
                        )
                        config
                    },
                ),
            )
            return Room.databaseBuilder(context, OrderDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()
        }

        /**
         * Replaces the delivery-fee rule with [code], the text of rules/delivery-fee.js, while the
         * app runs. It first tries the rule on [samples] in a scratch database, so a rule that
         * doesn't load, throws, takes too long or returns something other than a fee never
         * reaches the app's database: the old rule stays, and the exception says why.
         */
        suspend fun replaceRule(context: Context, code: String, samples: List<Order>) {
            val app = context.applicationContext
            withTimeLimit {
                withConnection(app, ":memory:") { db ->
                    db.execute("SELECT js_create_scalar(?, ?)", RULE, code)
                    samples.forEach { order ->
                        val args = arrayOf<Any>(order.subtotal, order.distanceKm, if (order.weekend) 1 else 0)
                        db.rawQuery("SELECT $RULE(?, ?, ?)", args).use { c ->
                            c.moveToFirst()
                            val isNumber = c.getType(0) == Cursor.FIELD_TYPE_INTEGER ||
                                c.getType(0) == Cursor.FIELD_TYPE_FLOAT
                            val fee = if (isNumber) c.getDouble(0) else Double.NaN
                            require(fee.isFinite() && fee >= 0) {
                                "For ${order.customer}'s order it returned ${c.getString(0)}, " +
                                    "not a fee of 0 or more."
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) {
                // A connection keeps the rule it loaded when it opened, so close Room's
                // connections. The next get() opens new ones, which load the new rule.
                synchronized(this@Companion) {
                    instance?.close()
                    instance = null
                }
                withConnection(app, app.getDatabasePath(NAME).path) { db ->
                    db.execute("SELECT js_init_table()")
                    db.execute("SELECT js_create_scalar(?, ?)", RULE, code)
                }
            }
        }

        // A connection with sqlite-js that runs none of the functions stored in the database
        // (SqliteJs.ENTRY_POINT), so it can store a new version of one.
        private fun <T> withConnection(context: Context, path: String, block: (SQLiteDatabase) -> T): T {
            val flags = SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
            val config = SQLiteDatabaseConfiguration(path, flags)
            config.customExtensions.add(
                SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT),
            )
            return SQLiteDatabase.openDatabase(config, null, null).use(block)
        }

        private fun SQLiteDatabase.execute(sql: String, vararg args: Any) {
            rawQuery(sql, args).use { it.moveToFirst() }
        }

        /**
         * Runs [query], which calls the rule, and stops the rule if it runs for longer than
         * RULE_TIME_LIMIT_MS: a rule with an endless loop must not freeze the app.
         */
        suspend fun <T> withTimeLimit(query: suspend () -> T): T =
            try {
                withTimeout(RULE_TIME_LIMIT_MS) { cancellable(query) }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(
                    "The rule ran for more than ${RULE_TIME_LIMIT_MS / 1000} s, so it was stopped. " +
                        "Does it loop forever?",
                )
            }
    }
}

/**
 * Runs [query], which may run JavaScript, so that cancelling the calling coroutine also stops the
 * JavaScript. Neither Room nor Android can stop a JavaScript function that never returns;
 * SqliteJs.cancelAll() stops all the JavaScript running at that moment, which then fails with an
 * "interrupted" error. Until the cancelled query ends, usually within milliseconds, JavaScript in
 * other queries is stopped too. (From the JavaScript in SQLite template.)
 */
suspend fun <T> cancellable(query: suspend () -> T): T = coroutineScope {
    // The query's result or failure is kept, not thrown, so that a query stopped by the cancel
    // ends this as cancelled rather than as failed. coroutineScope still waits for the query to
    // stop, which frees its connection.
    val running = async(Dispatchers.IO) { runCatching { query() } }
    try {
        running.await().getOrThrow()
    } catch (e: CancellationException) {
        // cancelAll() stops only JavaScript that has started, and the query may not have reached
        // its JavaScript yet, so it is called again until the query ends.
        withContext(NonCancellable) {
            while (!running.isCompleted) {
                SqliteJs.cancelAll()
                delay(CANCEL_RETRY_MS)
            }
        }
        throw e
    }
}

private const val CANCEL_RETRY_MS = 10L
