package com.iamtungingit.wam.ui.common

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.iamtungingit.wam.app.WamApp

/** Small UI helpers shared by activities/fragments. */
object Ui {

    /** Edge-to-edge insets handling (targetSdk 35 enforces edge-to-edge on Android 15+). */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun fmtBytes(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)

    /**
     * Runs [block] on the background executor.
     *
     * Fixed root cause of the "black screen with no explanation" reports: the old
     * version swallowed every exception into a logcat line the user can never see,
     * so a single thrown IOException anywhere in a launch chain left the screen on
     * the dark loading view forever. Pass [onError] from user-facing flows (runtime
     * launch, packaging) so failures become a visible, diagnosable error screen.
     */
    fun runIO(onError: ((Throwable) -> Unit)? = null, block: () -> Unit) {
        WamApp.instance.ioExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.e("WAM", "background task failed", t)
                val cb = onError ?: return@execute
                onMain { cb(t) }
            }
        }
    }

    fun onMain(block: () -> Unit) {
        WamApp.instance.mainHandler.post { try { block() } catch (e: Exception) { android.util.Log.e("WAM", "ui task failed", e) } }
    }
}
