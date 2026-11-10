package com.itsaky.androidide.plugins.aiagentlocal.device

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/**
 * A plain, empty activity for the instrumented Part 1 tests (declared only in the androidTest manifest). While it is resumed the process is
 * in `/top-app`, which the engine's foreground check (exit 97) needs. It keeps the screen on for as long as it exists.
 */
class TestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** Starts the activity and returns it once resumed; the caller finishes it in `finally` with [finishAndWait]. */
        fun start(instrumentation: Instrumentation): TestHostActivity {
            val intent = Intent().setClassName(instrumentation.context.packageName, TestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val a = instrumentation.startActivitySync(intent)
            return a as? TestHostActivity ?: throw AssertionError("the test activity did not start (got $a)")
        }

        fun finishAndWait(instrumentation: Instrumentation, activity: Activity) {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
