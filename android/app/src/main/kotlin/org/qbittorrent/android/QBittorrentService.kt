package org.qbittorrent.android

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

    @Volatile private var watchdogThread: Thread? = null
    @Volatile private var stopping = false

    companion object {
        const val WEB_UI_PORT = 8080
        const val WEB_UI_BASE_URL = "http://127.0.0.1:$WEB_UI_PORT"

        const val ACTION_STOP  = "org.qbittorrent.android.action.STOP"
        const val ACTION_START = "org.qbittorrent.android.action.START"

        private const val WAKELOCK_TAG          = "qBittorrent:DownloadWakeLock"
        private const val MAX_RESTARTS          = 5
        private const val RESTART_DELAY_BASE_MS = 2_000L
        private const val RESTART_DELAY_MAX_MS  = 60_000L
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopQBittorrent()
            updateNotification(NotificationHelper.State.STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }

        NotificationHelper.createNotificationChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, NotificationHelper.State.STARTING)
        )

        if (watchdogThread?.isAlive != true) {
            stopping = false
            watchdogThread = Thread(::runWatchdog, "qbt-watchdog").also {
                it.isDaemon = true
                it.start()
            }
        }

        return START_STICKY
    }

    /**
     * Restarts qBittorrent on unexpected exit with exponential back-off,
     * up to MAX_RESTARTS attempts.
     */
    private fun runWatchdog() {
        var restarts = 0
        var delayMs = RESTART_DELAY_BASE_MS

        while (!stopping) {
            val cleanExit = launchQBittorrent()

            if (stopping) break

            if (cleanExit) {
                Log.i(logTag, "qBittorrent exited cleanly")
                break
            }

            restarts++
            if (restarts > MAX_RESTARTS) {
                Log.e(logTag, "qBittorrent crashed $MAX_RESTARTS times — giving up")
                updateNotification(NotificationHelper.State.ERROR)
                break
            }

            Log.w(logTag, "qBittorrent crashed — restart $restarts/$MAX_RESTARTS in ${delayMs}ms")
            updateNotification(NotificationHelper.State.STARTING)

            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { break }
            delayMs = minOf(delayMs * 2, RESTART_DELAY_MAX_MS)
        }
    }

    /** Launches qBittorrent and blocks until it exits. Returns true on clean exit. */
    private fun launchQBittorrent(): Boolean {
        return try {
            val nativeBinary = getNativeBinaryPath()
            if (!File(nativeBinary).exists()) {
                Log.e(logTag, "Native binary not found: $nativeBinary")
                updateNotification(NotificationHelper.State.ERROR)
                return false
            }

            val profileDir = setupProfileDirectory()
            writeQBittorrentConfig(profileDir)

            val process = ProcessBuilder(
                nativeBinary,
                "--profile=${profileDir.absolutePath}",
                "--webui-port=$WEB_UI_PORT"
            )
                .redirectErrorStream(true)
                .directory(profileDir)
                .apply {
                    environment().apply {
                        put("HOME",           profileDir.absolutePath)
                        put("TMPDIR",         cacheDir.absolutePath)
                        put("QT_QPA_PLATFORM","offscreen")
                    }
                }
                .start()

            qbtProcess = process
            updateNotification(NotificationHelper.State.RUNNING)

            // Drain stdout/stderr so the pipe buffer never fills and blocks qbt
            process.inputStream.bufferedReader().forEachLine { Log.d(logTag, it) }

            val exitCode = process.waitFor()
            Log.i(logTag, "qBittorrent exited with code $exitCode")
            qbtProcess = null
            exitCode == 0

        } catch (e: Exception) {
            Log.e(logTag, "Failed to launch qBittorrent", e)
            qbtProcess = null
            false
        }
    }

    private fun getNativeBinaryPath() =
        "${applicationInfo.nativeLibraryDir}/libqbittorrent_nox.so"

    private fun setupProfileDirectory(): File {
        val dir = File(filesDir, "qbt-profile")
        dir.mkdirs()
        File(dir, "qBittorrent/logs").mkdirs()
        return dir
    }

    private fun writeQBittorrentConfig(profileDir: File) {
        val configFile = File(profileDir, "qBittorrent/qBittorrent.conf")
        if (configFile.exists()) return

        val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.absolutePath
            ?: "${filesDir.absolutePath}/downloads"
        File(downloadDir).mkdirs()

        configFile.writeText("""
[BitTorrent]
Session\DefaultSavePath=$downloadDir
Session\TempPath=$downloadDir/incomplete
Session\TempPathEnabled=true

[LegalNotice]
Accepted=true

[Meta]
MigrationVersion=6

[Preferences]
WebUI\Address=127.0.0.1
WebUI\Enabled=true
WebUI\HTTPS\Enabled=false
WebUI\LocalHostAuth=false
WebUI\Port=$WEB_UI_PORT
WebUI\ServerDomains=*
WebUI\UseUPnP=false
        """.trimIndent())
    }

    private fun updateNotification(state: NotificationHelper.State) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this, state))
    }

    private fun stopQBittorrent() {
        watchdogThread?.interrupt()
        qbtProcess?.let { p ->
            p.destroy()
            try { p.waitFor() } catch (_: InterruptedException) { p.destroyForcibly() }
        }
        qbtProcess = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            // No timeout — the foreground service notification signals ongoing
            // work to Android. A fixed timeout (e.g. 12 h) silently drops the
            // lock mid-session on long seeding runs.
            acquire()
        }
    }

    override fun onDestroy() {
        stopping = true
        stopQBittorrent()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
