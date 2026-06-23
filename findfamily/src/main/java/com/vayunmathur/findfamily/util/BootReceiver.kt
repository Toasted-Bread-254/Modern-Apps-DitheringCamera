package com.vayunmathur.findfamily.util
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Only start when fine location is granted; the sharing-enabled check
            // (and the actual start/stop) is handled by syncServiceState.
            if (!LocationServiceController.hasFineLocationPermission(context)) return
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    LocationServiceController.syncServiceState(appContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
