package org.qbittorrent.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.qbittorrent.android.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val prefs by lazy {
        getSharedPreferences("qbt_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
    }

    override fun onResume() {
        super.onResume()
        // Re-bind state each time screen is visible (user may have changed
        // system settings and come back)
        bindStartOnBoot()
        bindBatteryOptimization()
        bindStoragePermission()
        bindServiceControls()
        bindOpenWebUi()
        bindVersionInfo()
    }

    private fun bindStartOnBoot() {
        binding.switchStartOnBoot.isChecked = prefs.getBoolean("start_on_boot", true)
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("start_on_boot", checked).apply()
        }
    }

    private fun bindBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            binding.btnBatteryOptimization.text = getString(
                if (exempt) R.string.battery_optimization_exempt
                else R.string.battery_optimization_request
            )
            binding.btnBatteryOptimization.isEnabled = !exempt
            binding.btnBatteryOptimization.setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                }
            }
        } else {
            binding.cardBattery.visibility = android.view.View.GONE
        }
    }

    private fun bindStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            binding.btnStoragePermission.text = getString(
                if (granted) R.string.storage_permission_granted
                else R.string.storage_permission_request
            )
            binding.btnStoragePermission.isEnabled = !granted
            binding.btnStoragePermission.setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            binding.cardStorage.visibility = android.view.View.GONE
        }
    }

    private fun bindServiceControls() {
        binding.btnStopService.setOnClickListener {
            startService(Intent(this, QBittorrentService::class.java).apply {
                action = QBittorrentService.ACTION_STOP
            })
            Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show()
        }

        binding.btnRestartService.setOnClickListener {
            // Stop then re-start
            startService(Intent(this, QBittorrentService::class.java).apply {
                action = QBittorrentService.ACTION_STOP
            })
            val startIntent = Intent(this, QBittorrentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            Toast.makeText(this, R.string.service_restarting, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindOpenWebUi() {
        binding.btnOpenWebUiBrowser.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(QBittorrentService.WEB_UI_BASE_URL))
            )
        }

        binding.tvWebUiAddress.text = getString(
            R.string.webui_address_format,
            QBittorrentService.WEB_UI_PORT
        )
    }

    private fun bindVersionInfo() {
        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
