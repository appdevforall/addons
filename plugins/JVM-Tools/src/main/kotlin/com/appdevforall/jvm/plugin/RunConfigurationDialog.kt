package com.appdevforall.jvm.plugin

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.itsaky.androidide.plugins.base.PluginWindows

internal object RunConfigurationDialog {

    private const val TITLE = "Run configuration"

    fun show(
        activity: Activity,
        mainClasses: List<MainClass>,
        selected: RunConfiguration,
        onHelp: (View) -> Unit,
        onSave: (RunConfiguration) -> Unit,
    ) {
        if (mainClasses.isEmpty()) {
            PluginWindows.showDialog(
                MaterialAlertDialogBuilder(activity)
                    .setTitle(TITLE)
                    .setMessage(
                        "No main class found. Add a Java class with public static void main(String[] args), " +
                            "or a Kotlin file with a top-level fun main()."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            )
            return
        }

        val classes = RadioGroup(activity)
        mainClasses.forEachIndexed { index, main ->
            classes.addView(MaterialRadioButton(activity).apply {
                id = index + 1
                text = main.name
            })
        }
        classes.check(mainClasses.indexOfFirst { it.name == selected.mainClass }.coerceAtLeast(0) + 1)

        val argumentsLayout = TextInputLayout(activity).apply {
            hint = "Program arguments"
            helperText = "Separate arguments with spaces. Quote an argument that contains spaces."
            setOnLongClickListener { view ->
                onHelp(view)
                true
            }
        }
        val arguments = TextInputEditText(argumentsLayout.context).apply {
            setText(selected.arguments)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        argumentsLayout.addView(arguments)

        val padding = dp(activity, 24)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(activity, 8), padding, 0)
            addView(MaterialTextView(activity).apply {
                text = "Main class"
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setOnLongClickListener { view ->
                    onHelp(view)
                    true
                }
            })
            addView(classes)
            addView(
                argumentsLayout,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(activity, 16) },
            )
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(TITLE)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = arguments.text?.toString().orEmpty()
                try {
                    ProgramArguments.parse(text)
                } catch (e: IllegalArgumentException) {
                    argumentsLayout.error = e.message
                    return@setOnClickListener
                }
                onSave(RunConfiguration(mainClasses[classes.checkedRadioButtonId - 1].name, text))
                dialog.dismiss()
            }
        }
        PluginWindows.showDialog(dialog)
    }

    private fun dp(activity: Activity, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}
