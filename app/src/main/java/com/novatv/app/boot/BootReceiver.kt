package com.novatv.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.novatv.app.MainActivity
import com.novatv.app.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Start on device boot" (Settings → General). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.app.settings.current().bool("general.boot_start")) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
