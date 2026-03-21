package org.qbittorrent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qbittorrent.android.databinding.ActivityMainBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

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
        requestNotificationPermissionAndStart()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data ?: return
        val scheme = data.scheme ?: return

        val webUiUrl = when {
            scheme == "magnet" -> buildWebUiAddUrl("magnet_link", data.toString())
            intent.type == "application/x-bittorrent" -> null
            else -> null
        }
        if (webUiUrl != null) {
            binding.webView.loadUrl(webUiUrl)
        }
    }

    private fun buildWebUiAddUrl(param: String, value: String): String {
        return "${QBittorrentService.WEB_UI_BASE_URL}/#/add?$param=${Uri.encode(value)}"
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
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                return when (url.scheme) {
                    "magnet", "http", "https" -> {
                        if (url.host == "127.0.0.1" || url.host == "localhost") {
                            false
                        } else {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                            true
                        }
                    }
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.loadingContainer.visibility = android.view.View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                if (failingUrl == QBittorrentService.WEB_UI_BASE_URL ||
                    failingUrl.startsWith(QBittorrentService.WEB_UI_BASE_URL)
                ) {
                    scheduleWebUiRetry()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
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

        scope.launch {
            val ready = waitForWebUi()
            if (ready) {
                binding.webView.loadUrl(QBittorrentService.WEB_UI_BASE_URL)
            } else {
                binding.statusText.text = getString(R.string.failed_to_start)
                Toast.makeText(
                    this@MainActivity,
                    R.string.failed_to_start,
                    Toast.LENGTH_LONG
                ).show()
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
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return@withContext true
            } catch (_: IOException) {
            }
        }
        false
    }

    private fun scheduleWebUiRetry() {
        scope.launch {
            delay(2000)
            binding.webView.loadUrl(QBittorrentService.WEB_UI_BASE_URL)
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }
}
