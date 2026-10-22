package com.zarc.vault.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity

/**
 * A debug only hook that lets the development tooling reload the screen
 * without touching the device.
 *
 * The watcher (`tools/watch-ui.ps1`) sends the broadcast after it installed a
 * new build; the activity is recreated with the new code and the state of the
 * gallery is kept, because the view model survives a configuration change.
 *
 * The receiver is registered in the manifest only for the debug build type, so
 * it is not part of the shipping application.
 */
class ReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RELOAD) return
        val activity = context as? ComponentActivity ?: return
        activity.runOnUiThread { activity.recreate() }
    }

    companion object {
        /** The action of the reload broadcast. */
        const val ACTION_RELOAD = "com.zarc.vault.action.RELOAD"
    }
}
