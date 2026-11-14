package com.wifi.wifilook.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.wifi.wifilook.R
import com.wifi.wifilook.net.AutoConnector
import com.wifi.wifilook.net.DeviceProtocol

class ConnectActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 100
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvHint: TextView
    private lateinit var progressConnect: ProgressBar
    private lateinit var btnConnect: Button

    private lateinit var connector: AutoConnector
    private var connectedIp: String? = null
    private var isConnecting = false
    private var lastBackPressMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        tvStatus = findViewById(R.id.tvStatus)
        tvSsid = findViewById(R.id.tvSsid)
        tvIp = findViewById(R.id.tvIp)
        tvBattery = findViewById(R.id.tvBattery)
        tvHint = findViewById(R.id.tvHint)
        progressConnect = findViewById(R.id.progressConnect)
        btnConnect = findViewById(R.id.btnConnect)

        connector = AutoConnector(this)

        btnConnect.setOnClickListener {
            val ip = connectedIp
            if (ip != null) {
                openCamera(ip)
            } else if (!isConnecting) {
                beginConnectFlow()
            }
        }

        tvHint.setOnClickListener {
            if (connectedIp == null && !isConnecting) {
                try {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isConnecting) {
            quickProbeCurrentWifi()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isConnecting) {
            connector.stop()
            setConnectingState(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connector.stop()
        connector.releaseNetworkCallback()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs <= 2000L) {
            exitAppCleanly()
        } else {
            lastBackPressMs = now
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitAppCleanly() {
        connector.stop()
        connector.releaseNetworkCallback()
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    private fun quickProbeCurrentWifi() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = DeviceProtocol.pickWifiNetwork(cm)
        val ssid = connector.currentSsid()
        tvSsid.text = ssid
        if (wifi == null) {
            showDisconnected()
            return
        }
        Thread({
            val ip = DeviceProtocol.probeDevice(cm, wifi, 600)
            val bat = if (ip != null) DeviceProtocol.queryBatteryPercent(wifi, ip) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ip != null) {
                    showConnected(connector.currentSsid(), ip, bat)
                } else {
                    showDisconnected()
                }
            }
        }, "quick-probe").start()
    }

    private fun beginConnectFlow() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        startAutoConnect()
    }

    private fun missingPermissions(): List<String> {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (missingPermissions().isEmpty()) {
                startAutoConnect()
            } else {
                tvHint.text = "需要蓝牙与位置权限以自动发现并连接设备热点（或点击此处去系统设置手动连接 Wi-Fi）"
            }
        }
    }

    private fun startAutoConnect() {
        setConnectingState(true)
        tvStatus.text = "连接中…"
        tvStatus.setTextColor(Color.parseColor("#E3B341"))

        connector.start(object : AutoConnector.Callback {
            override fun onProgress(message: String) {
                if (isFinishing || isDestroyed) return
                tvHint.text = message
            }

            override fun onConnected(ssid: String, deviceIp: String, battery: Int?) {
                if (isFinishing || isDestroyed) return
                setConnectingState(false)
                showConnected(ssid, deviceIp, battery)
                openCamera(deviceIp)
            }

            override fun onNeedManualWifi(message: String) {
                if (isFinishing || isDestroyed) return
                setConnectingState(false)
                showDisconnected()
                tvHint.text = "$message（点击此处可打开 Wi-Fi 设置）"
            }
        })
    }

    private fun setConnectingState(connecting: Boolean) {
        isConnecting = connecting
        progressConnect.visibility = if (connecting) View.VISIBLE else View.GONE
        btnConnect.isEnabled = !connecting
        btnConnect.alpha = if (connecting) 0.6f else 1.0f
        if (connecting) {
            btnConnect.text = "正在连接…"
        }
    }

    private fun showConnected(ssid: String, ip: String, battery: Int?) {
        connectedIp = ip
        tvStatus.text = "已连接"
        tvStatus.setTextColor(Color.parseColor("#3FB950"))
        tvSsid.text = ssid
        tvIp.text = ip
        tvBattery.text = if (battery != null) "$battery%" else "— —"
        tvHint.text = "设备已就绪，点击下方按钮进入相机画面"
        btnConnect.text = "进入相机画面"
        btnConnect.isEnabled = true
        btnConnect.alpha = 1.0f
    }

    private fun showDisconnected() {
        connectedIp = null
        tvStatus.text = "未连接"
        tvStatus.setTextColor(Color.parseColor("#F85149"))
        tvIp.text = "— —"
        tvBattery.text = "— —"
        if (!isConnecting) {
            tvHint.text = "请开启设备电源，点击下方按钮自动连接"
            btnConnect.text = "连接设备"
            btnConnect.isEnabled = true
            btnConnect.alpha = 1.0f
        }
    }

    private fun openCamera(ip: String) {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_DEVICE_IP, ip)
        }
        startActivity(intent)
    }
}
