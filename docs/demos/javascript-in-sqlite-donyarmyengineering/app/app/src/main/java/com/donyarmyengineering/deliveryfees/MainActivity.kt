package com.donyarmyengineering.deliveryfees

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.donyarmyengineering.deliveryfees.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    private val prefs by lazy { getSharedPreferences("rule", MODE_PRIVATE) }

    // Android's file picker. The app keeps permission to read the chosen file, so Reload rule
    // reads it again without asking.
    private val chooseRule = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString(KEY_RULE_FILE, uri.toString()).apply()
            reloadRule()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep content clear of the system bars (edge-to-edge is enforced on API 35+)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.chooseRule.setOnClickListener { chooseRule.launch(arrayOf("*/*")) }
        binding.reloadRule.setOnClickListener { reloadRule() }
        binding.showNotices.setOnClickListener { showNotices() }
        if (prefs.getString(KEY_RULE_FILE, null) == null) {
            binding.ruleStatus.setText(R.string.choose_first)
        }
        // The rule buttons are off until the first fees are shown, as during a reload.
        startBusy()
        lifecycleScope.launch {
            try {
                showFees()
            } finally {
                endBusy()
            }
        }
    }

    // Lists the orders with the fee from the rule stored in the database.
    private suspend fun showFees() {
        _binding?.results?.setText(R.string.working)
        try {
            val rows = withContext(Dispatchers.IO) {
                val orders = OrderDatabase.get(applicationContext).orders()
                if (orders.count() == 0) orders.insert(SAMPLE_ORDERS)
                OrderDatabase.withTimeLimit { orders.withFees() }
            }
            _binding?.results?.text = table(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val noRule = e.message.orEmpty().contains("no such function")
            _binding?.results?.text =
                if (noRule) getString(R.string.no_rule) else getString(R.string.error, e.message)
        }
    }

    // Reads the rule file again, and replaces the rule in the database if it works. The rule
    // buttons stay off until the new fees are shown and nothing else is running, so two reloads
    // started from this screen can't overlap and finish in the wrong order. (A reload started
    // before the screen turned isn't counted; its 2-second limit ends it soon.)
    private fun reloadRule() {
        val file = prefs.getString(KEY_RULE_FILE, null)?.let(Uri::parse)
        if (file == null) {
            binding.ruleStatus.setText(R.string.choose_first)
            return
        }
        binding.ruleStatus.setText(R.string.working)
        startBusy()
        lifecycleScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                try {
                    val code = withContext(Dispatchers.IO) {
                        checkNotNull(contentResolver.openInputStream(file)) { "Can't open $file" }
                            .bufferedReader().use { it.readText() }
                    }
                    OrderDatabase.replaceRule(applicationContext, code, SAMPLE_ORDERS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _binding?.ruleStatus?.text = getString(R.string.rule_rejected, e.message)
                    return@launch
                }
                // The rule is stored now, so nothing from here on reports it as not changed.
                val ms = SystemClock.elapsedRealtime() - started
                _binding?.ruleStatus?.text = getString(R.string.rule_loaded, displayName(file), ms)
                showFees()
            } finally {
                endBusy()
            }
        }
    }

    // How many loads of the fees or the rule are running. The rule buttons are off while any
    // is. Only the main thread changes it.
    private var busy = 0

    private fun startBusy() {
        busy++
        setRuleButtonsEnabled(false)
    }

    private fun endBusy() {
        busy--
        if (busy == 0) setRuleButtonsEnabled(true)
    }

    private fun setRuleButtonsEnabled(enabled: Boolean) {
        _binding?.reloadRule?.isEnabled = enabled
        _binding?.chooseRule?.isEnabled = enabled
    }

    // A fee the rule gave no number for shows as "?", not as 0.00.
    private fun table(rows: List<OrderWithFee>): String =
        rows.joinToString("\n", prefix = getString(R.string.table_header) + "\n") { o ->
            String.format(
                Locale.US, "%-6s %7.2f %5.1f %-3s %6s",
                o.customer, o.subtotal, o.distanceKm, if (o.weekend) "yes" else "no",
                o.fee?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
            )
        }

    // The file's name, or the last part of its address when the name can't be read.
    private fun displayName(file: Uri): String =
        runCatching {
            contentResolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: file.lastPathSegment.orEmpty()

    private fun showNotices() {
        val text = assets.open("NOTICES.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this)
            .setTitle(R.string.notices)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    companion object {
        private const val KEY_RULE_FILE = "rule_file"

        private val SAMPLE_ORDERS = listOf(
            Order(customer = "Ana", subtotal = 32.50, distanceKm = 8.0, weekend = false),
            Order(customer = "Ben", subtotal = 54.20, distanceKm = 3.0, weekend = true),
            Order(customer = "Chloe", subtotal = 18.75, distanceKm = 12.5, weekend = true),
            Order(customer = "Dev", subtotal = 47.10, distanceKm = 2.0, weekend = false),
            Order(customer = "Eli", subtotal = 41.00, distanceKm = 6.0, weekend = true),
        )
    }
}
