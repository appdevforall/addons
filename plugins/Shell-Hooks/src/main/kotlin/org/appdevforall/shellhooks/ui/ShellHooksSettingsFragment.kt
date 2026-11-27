package org.appdevforall.shellhooks.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.shellhooks.R
import org.appdevforall.shellhooks.ShellHooksHelp
import org.appdevforall.shellhooks.ShellHooksPlugin
import org.appdevforall.shellhooks.ShellHooksSettings
import org.appdevforall.shellhooks.engine.Hook
import org.appdevforall.shellhooks.engine.TimeoutSetting
import java.io.File

/**
 * The Preferences screen: the master switch, the hooks folder and its samples, the timeout, one
 * switch per hook, the project-hooks switch, and the projects whose scripts the user trusted.
 */
class ShellHooksSettingsFragment : Fragment() {
    private var tooltips: IdeTooltipService? = null

    // The trusted list's latest reload; an older one still reading is cancelled, so it can't show
    // a project that a later Revoke already removed.
    private var trustedLoad: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tooltips =
            runCatching {
                PluginFragmentHelper.getServiceRegistry(ShellHooksPlugin.PLUGIN_ID)?.get(IdeTooltipService::class.java)
            }.getOrNull()
    }

    // Inflates against this plugin's resources, in a Context that follows the IDE's day/night mode.
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        PluginFragmentHelper.getPluginInflater(ShellHooksPlugin.PLUGIN_ID, super.onGetLayoutInflater(savedInstanceState))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_shell_hooks_settings, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val plugin = ShellHooksPlugin.getInstance() ?: return
        val settings = plugin.settings

        view.findViewById<View>(R.id.shBack).apply {
            help(this, ShellHooksHelp.TAG_BACK)
            setOnClickListener { leaveScreen() }
        }
        listOf(R.id.shTitle, R.id.shEnable, R.id.shEnableSummary).forEach { help(view.findViewById(it), ShellHooksHelp.TAG_SETTINGS) }
        listOf(R.id.shFolderLabel, R.id.shFolderHelp, R.id.shCreateSamples).forEach { help(view.findViewById(it), ShellHooksHelp.TAG_FOLDER) }
        listOf(R.id.shTimeoutLabel, R.id.shTimeout).forEach { help(view.findViewById(it), ShellHooksHelp.TAG_TIMEOUT) }
        help(view.findViewById(R.id.shHooksLabel), ShellHooksHelp.TAG_HOOKS)
        listOf(R.id.shProjectHooks, R.id.shProjectHooksSummary, R.id.shTrustedLabel)
            .forEach { help(view.findViewById(it), ShellHooksHelp.TAG_PROJECT_HOOKS) }

        view.findViewById<SwitchMaterial>(R.id.shEnable).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, on -> settings.enabled = on }
        }
        view.findViewById<SwitchMaterial>(R.id.shProjectHooks).apply {
            isChecked = settings.projectHooksEnabled
            setOnCheckedChangeListener { _, on -> settings.projectHooksEnabled = on }
        }

        view.findViewById<TextView>(R.id.shFolderPath).text = plugin.paths.globalHooks.path
        val samplesResult = view.findViewById<TextView>(R.id.shSamplesResult)
        view.findViewById<Button>(R.id.shCreateSamples).setOnClickListener {
            samplesResult.visibility = View.VISIBLE
            samplesResult.text =
                try {
                    plugin.writeSamples().let { count -> resources.getQuantityString(R.plurals.sh_samples_created, count, count) }
                } catch (e: Exception) {
                    getString(R.string.sh_samples_failed, e.message ?: e.javaClass.simpleName)
                }
        }

        bindTimeout(view.findViewById(R.id.shTimeout), settings)
        bindHooks(view.findViewById(R.id.shHookList), settings)
        val trustedList = view.findViewById<LinearLayout>(R.id.shTrustedList)
        loadTrusted(trustedList, plugin)
    }

    private fun bindTimeout(
        field: EditText,
        settings: ShellHooksSettings,
    ) {
        field.setText(settings.timeoutSeconds.toString())
        field.hint = getString(R.string.sh_timeout_hint, ShellHooksSettings.MIN_TIMEOUT_SECONDS, ShellHooksSettings.MAX_TIMEOUT_SECONDS)
        field.addTextChangedListener(
            object : TextWatcher {
                // Stores only a valid value; anything else is flagged on the field and not stored,
                // so the stored timeout is always the one the field showed last while valid.
                override fun afterTextChanged(s: Editable?) {
                    val seconds = TimeoutSetting.parse(s?.toString().orEmpty())
                    if (seconds != null) {
                        settings.timeoutSeconds = seconds
                        field.error = null
                    } else {
                        field.error =
                            getString(
                                R.string.sh_timeout_invalid,
                                TimeoutSetting.MIN_SECONDS,
                                TimeoutSetting.MAX_SECONDS,
                                settings.timeoutSeconds,
                            )
                    }
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit
            },
        )
    }

    private fun bindHooks(
        list: LinearLayout,
        settings: ShellHooksSettings,
    ) {
        list.removeAllViews()
        for (hook in Hook.entries) {
            val row = layoutInflater.inflate(R.layout.item_hook_switch, list, false)
            row.findViewById<SwitchMaterial>(R.id.hookSwitch).apply {
                text = "${hook.title} (${hook.id})"
                isChecked = settings.isHookEnabled(hook)
                setOnCheckedChangeListener { _, on -> settings.setHookEnabled(hook, on) }
                help(this, ShellHooksHelp.TAG_HOOKS)
            }
            row.findViewById<TextView>(R.id.hookArgs).text = hook.argsDoc
            list.addView(row)
        }
    }

    private fun loadTrusted(
        list: LinearLayout,
        plugin: ShellHooksPlugin,
    ) {
        trustedLoad?.cancel()
        trustedLoad = viewLifecycleOwner.lifecycleScope.launch { bindTrusted(list, plugin) }
    }

    /**
     * Lists the projects with trusted scripts. The trust store is read off the main thread: listing
     * and counting resolve each project's canonical path, which can wait on storage.
     */
    private suspend fun bindTrusted(
        list: LinearLayout,
        plugin: ShellHooksPlugin,
    ) {
        val projects =
            withContext(Dispatchers.IO) {
                plugin.trust.trustedProjects().map { path -> path to plugin.trust.trustedCount(File(path)) }
            }
        list.removeAllViews()
        if (projects.isEmpty()) {
            list.addView(
                (layoutInflater.inflate(R.layout.item_trusted_project, list, false)).apply {
                    findViewById<TextView>(R.id.trustedPath).text = getString(R.string.sh_trusted_none)
                    findViewById<View>(R.id.trustedCount).visibility = View.GONE
                    findViewById<View>(R.id.trustedRevoke).visibility = View.GONE
                },
            )
            return
        }
        for ((path, count) in projects) {
            val row = layoutInflater.inflate(R.layout.item_trusted_project, list, false)
            // A project path is the project's to choose, so it is revealed.
            row.findViewById<TextView>(R.id.trustedPath).text = RevealedNames.show(row.context, path)
            row.findViewById<TextView>(R.id.trustedCount).text = resources.getQuantityString(R.plurals.sh_trusted_count, count, count)
            row.findViewById<Button>(R.id.trustedRevoke).apply {
                help(this, ShellHooksHelp.TAG_PROJECT_HOOKS)
                setOnClickListener { button ->
                    // Off the main thread: revoking resolves the project's canonical path and writes
                    // to disk before returning. Disabled meanwhile, so a second tap does nothing.
                    button.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO + NonCancellable) { plugin.trust.revoke(File(path)) }
                        loadTrusted(list, plugin)
                    }
                }
            }
            list.addView(row)
        }
    }

    private fun help(
        view: View,
        tag: String,
    ) {
        view.setOnLongClickListener { anchor ->
            val service = tooltips ?: return@setOnLongClickListener false
            service.showTooltip(anchor, ShellHooksPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    // The host mounts a settings pane on its own back stack or as the whole activity.
    private fun leaveScreen() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            requireActivity().finish()
        }
    }
}
