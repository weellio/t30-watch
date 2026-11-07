package com.t30watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.t30watch.data.settings.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restarts the background connection after the phone reboots (or the app is updated), if it's switched on. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: AppSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (settings.current().keepConnected) WatchService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
