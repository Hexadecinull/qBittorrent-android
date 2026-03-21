package org.qbittorrent.android

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import java.io.File
import java.io.FileWriter

class QBittorrentService : LifecycleService() {

    private var qbtProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logTag = "QBittorrentService"

    companion object {
        const val WEB_UI_PORT = 8080
        const val WEB_UI_BASE_URL = "http://127.0.0.1:$WEB_UI_PORT"

        const val ACTION_STOP = "org.qbittorrent.android.action.STOP"
        const val ACTION_START = "org.qbittorrent.android.action.START"

        private const val WAKELOCK_TAG = "qBittorrent:DownloadWakeLock"
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopQBittorrent()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        NotificationHelper.createNotificationChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, NotificationHelper.State.STARTING)
        )

        if (qbtProcess == null || !isProcessAlive(qbtProcess)) {
            Thread {
                launchQBittorrent()
            }.also { it.isDaemon = true }.start()
        }

        return START_STICKY
    }

    private fun launchQBittorrent() {
        try {
            val nativeBinary = getNativeBinaryPath()
            if (!File(nativeBinary).exists()) {
                Log.e(logTag, "Native binary not found at: $nativeBinary")
                updateNotification(NotificationHelper.State.ERROR)
                return
            }

            val profileDir = setupProfileDirectory()
            writeQBittorrentConfig(profileDir)

            val cmd = mutableListOf(
                nativeBinary,
                "--profile=${profileDir.absolutePath}",
                "--webui-port=$WEB_UI_PORT",
            )

            val env = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(profileDir)
                .also { pb ->
                    pb.environment().apply {
                        put("HOME", profileDir.absolutePath)
                        put("TMPDIR", cacheDir.absolutePath)
                        put("QT_QPA_PLATFORM", "offscreen")
                    }
                }
                .start()

            qbtProcess = env

            updateNotification(NotificationHelper.State.RUNNING)

            env.inputStream.bufferedReader().forEachLine { line ->
                Log.d(logTag, line)
            }

            val exitCode = env.waitFor()
            Log.i(logTag, "qBittorrent exited with code $exitCode")

            if (exitCode != 0) {
                updateNotification(NotificationHelper.State.ERROR)
            }

        } catch (e: Exception) {
            Log.e(logTag, "Failed to launch qBittorrent", e)
            updateNotification(NotificationHelper.State.ERROR)
        }
    }

    private fun getNativeBinaryPath(): String {
        return "${applicationInfo.nativeLibraryDir}/libqbittorrent_nox.so"
    }

    private fun setupProfileDirectory(): File {
        val profileDir = File(filesDir, "qbt-profile")
        profileDir.mkdirs()
        File(profileDir, "qBittorrent").mkdirs()
        File(profileDir, "qBittorrent/logs").mkdirs()
        return profileDir
    }

    private fun writeQBittorrentConfig(profileDir: File) {
        val configDir = File(profileDir, "qBittorrent")
        configDir.mkdirs()
        val configFile = File(configDir, "qBittorrent.conf")

        if (configFile.exists()) return

        val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.absolutePath
            ?: "${filesDir.absolutePath}/downloads"

        File(downloadDir).mkdirs()

        val config = """
[BitTorrent]
Session\DefaultSavePath=$downloadDir
Session\TempPath=$downloadDir/incomplete
Session\TempPathEnabled=true

[LegalNotice]
Accepted=true

[Meta]
MigrationVersion=6

[Network]
Proxy\OnlyForTorrents=false

[Preferences]
Advanced\trackerPort=9000
General\Locale=
WebUI\Address=127.0.0.1
WebUI\AlternativeUIEnabled=false
WebUI\AuthSubnetWhitelistEnabled=false
WebUI\Enabled=true
WebUI\HTTPS\Enabled=false
WebUI\LocalHostAuth=false
WebUI\Port=$WEB_UI_PORT
WebUI\ServerDomains=*
WebUI\UseUPnP=false
        """.trimIndent()

        FileWriter(configFile).use { it.write(config) }
    }

    private fun isProcessAlive(process: Process?): Boolean {
        process ?: return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun updateNotification(state: NotificationHelper.State) {
        val notification = NotificationHelper.buildNotification(this, state)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun stopQBittorrent() {
        qbtProcess?.let { process ->
            process.destroy()
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            }
        }
        qbtProcess = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        stopQBittorrent()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
