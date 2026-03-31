package org.qbittorrent.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences("qbt_prefs", Context.MODE_PRIVATE)

        // First-install default: write true so autostart works out of the box.
        // The user can disable it from Settings.
        if (!prefs.contains("start_on_boot")) {
            prefs.edit().putBoolean("start_on_boot", true).apply()
        }
        if (!prefs.getBoolean("start_on_boot", true)) return

        val serviceIntent = Intent(context, QBittorrentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
