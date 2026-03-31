package org.qbittorrent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qbittorrent.android.databinding.ActivityMainBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Intents that arrived before the WebUI was ready are queued here
    // and dispatched once waitForWebUi() succeeds.
    private var pendingMagnet: String? = null
    private var pendingTorrentUri: Uri? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startQBittorrentService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupBackHandler()
        requestPermissionsIfNeeded()
        storeIncomingIntent(intent)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        storeIncomingIntent(intent)
        // WebUI is already up — dispatch straight away
        if (binding.loadingContainer.visibility == android.view.View.GONE) {
            dispatchPendingIntents()
        }
    }

    // Store rather than immediately dispatch so we don't race with startup.
    private fun storeIncomingIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data ?: return
        when {
            data.scheme == "magnet"                    -> pendingMagnet = data.toString()
            intent.type == "application/x-bittorrent" -> pendingTorrentUri = data
        }
    }

    private fun dispatchPendingIntents() {
        pendingMagnet?.also { pendingMagnet = null; addMagnetLink(it) }
        pendingTorrentUri?.also { pendingTorrentUri = null; addTorrentFile(it) }
    }

    /** POST magnet link to the qbt API — the /#/add?... URL is not a real route. */
    private fun addMagnetLink(magnetUri: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("${QBittorrentService.WEB_UI_BASE_URL}/api/v2/torrents/add")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.bufferedWriter().use { it.write("urls=${Uri.encode(magnetUri)}") }
                val code = conn.responseCode
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    val msg = if (code == 200) R.string.magnet_added else R.string.add_failed
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to add magnet", e)
            }
        }
    }

    /** Read the .torrent URI content and POST it as multipart form data. */
    private fun addTorrentFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch
                val boundary = "qbt_${System.currentTimeMillis()}"
                val conn = URL("${QBittorrentService.WEB_UI_BASE_URL}/api/v2/torrents/add")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { out ->
                    fun w(s: String) = out.write(s.toByteArray())
                    w("--$boundary\r\n")
                    w("Content-Disposition: form-data; name=\"torrents\"; filename=\"upload.torrent\"\r\n")
                    w("Content-Type: application/x-bittorrent\r\n\r\n")
                    out.write(bytes)
                    w("\r\n--$boundary--\r\n")
                }
                val code = conn.responseCode
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    val msg = if (code == 200) R.string.torrent_added else R.string.add_failed
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to add torrent", e)
            }
        }
    }

    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString qBittorrentAndroid/${BuildConfig.VERSION_NAME}"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return when (url.scheme) {
                    "magnet", "http", "https" -> {
                        if (url.host == "127.0.0.1" || url.host == "localhost") false
                        else { startActivity(Intent(Intent.ACTION_VIEW, url)); true }
                    }
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.loadingContainer.visibility = android.view.View.GONE
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                if (failingUrl.startsWith(QBittorrentService.WEB_UI_BASE_URL)) {
                    scheduleWebUiRetry()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
                binding.progressBar.progress = newProgress
            }
        }
    }

    /** Replaces the deprecated override fun onBackPressed(). */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun requestPermissionsIfNeeded() {
        requestBatteryOptimizationExemption()
        requestStoragePermission()
        requestNotificationPermissionAndStart()
    }

    /**
     * Without battery optimization exemption, Android can throttle or kill
     * the background service after the screen turns off. Essential for a
     * download client.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    // Some ROMs don't support the direct intent; silently skip
                }
            }
        }
    }

    /**
     * On API 30+, MANAGE_EXTERNAL_STORAGE requires an explicit redirect to
     * the system settings page — the manifest declaration alone does nothing.
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startQBittorrentService()
    }

    private fun startQBittorrentService() {
        val serviceIntent = Intent(this, QBittorrentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        waitForWebUiAndLoad()
    }

    private fun waitForWebUiAndLoad() {
        binding.loadingContainer.visibility = android.view.View.VISIBLE
        binding.statusText.text = getString(R.string.starting_service)

        lifecycleScope.launch {
            if (waitForWebUi()) {
                binding.webView.loadUrl(QBittorrentService.WEB_UI_BASE_URL)
                dispatchPendingIntents()
            } else {
                binding.statusText.text = getString(R.string.failed_to_start)
                Toast.makeText(this@MainActivity, R.string.failed_to_start, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun waitForWebUi(): Boolean = withContext(Dispatchers.IO) {
        repeat(60) { attempt ->
            if (attempt > 0) delay(1000)
            try {
                val conn = URL(QBittorrentService.WEB_UI_BASE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return@withContext true
            } catch (_: IOException) {}
        }
        false
    }

    private fun scheduleWebUiRetry() {
        lifecycleScope.launch {
            delay(2000)
            binding.webView.loadUrl(QBittorrentService.WEB_UI_BASE_URL)
        }
    }
}
