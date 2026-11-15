package com.wifi.wifilook.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
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
        private const val PREFS_NAME = "wifi_look_prefs"
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvPrecheckSummary: TextView
    private lateinit var tvStateBluetooth: TextView
    private lateinit var tvStateLocation: TextView
    private lateinit var tvStateWifi: TextView
    private lateinit var tvPrecheckTip: TextView
    private lateinit var itemBluetooth: View
    private lateinit var itemLocation: View
    private lateinit var itemWifi: View
    private lateinit var progressConnect: ProgressBar
    private lateinit var btnConnect: Button

    private lateinit var connector: AutoConnector
    private var connectedIp: String? = null
    private var isConnecting = false
    private var isPrecheckReady = false
    private var lastBackPressMs = 0L
    private var precheckReceiverRegistered = false
    private var privacyDialog: AlertDialog? = null

    private val precheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updatePrecheckStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        tvStatus = findViewById(R.id.tvStatus)
        tvSsid = findViewById(R.id.tvSsid)
        tvIp = findViewById(R.id.tvIp)
        tvBattery = findViewById(R.id.tvBattery)
        tvHint = findViewById(R.id.tvHint)
        tvPrecheckSummary = findViewById(R.id.tvPrecheckSummary)
        tvStateBluetooth = findViewById(R.id.tvStateBluetooth)
        tvStateLocation = findViewById(R.id.tvStateLocation)
        tvStateWifi = findViewById(R.id.tvStateWifi)
        tvPrecheckTip = findViewById(R.id.tvPrecheckTip)
        itemBluetooth = findViewById(R.id.itemBluetooth)
        itemLocation = findViewById(R.id.itemLocation)
        itemWifi = findViewById(R.id.itemWifi)
        progressConnect = findViewById(R.id.progressConnect)
        btnConnect = findViewById(R.id.btnConnect)

        connector = AutoConnector(this)

        findViewById<View>(R.id.ivAppLogo).setOnClickListener {
            showPrivacyPolicyDialog(isReview = isPrivacyAccepted())
        }

        itemBluetooth.setOnClickListener { openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        itemLocation.setOnClickListener { openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        itemWifi.setOnClickListener { openSystemSettings(Settings.ACTION_WIFI_SETTINGS) }

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
        updatePrecheckStatus()
        if (!isPrivacyAccepted()) {
            showPrivacyPolicyDialog()
            return
        }
        registerPrecheckReceiver()
        if (!isConnecting) {
            quickProbeCurrentWifi()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterPrecheckReceiver()
        if (isConnecting) {
            connector.stop()
            setConnectingState(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        privacyDialog?.dismiss()
        privacyDialog = null
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
            val bat = if (ip != null) DeviceProtocol.queryBatteryStatus(wifi, ip) else null
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
        val missingToggles = ArrayList<String>()
        if (!isBluetoothEnabled()) missingToggles.add("蓝牙")
        if (!isLocationEnabled()) missingToggles.add("位置")
        if (!isWifiEnabled()) missingToggles.add("WLAN")
        if (missingToggles.isNotEmpty()) {
            val msg = "请先开启：${missingToggles.joinToString("、")}"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            updatePrecheckStatus()
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        startAutoConnect()
    }

    private fun openSystemSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Throwable) {
        }
    }

    private fun registerPrecheckReceiver() {
        if (precheckReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        try {
            registerReceiver(precheckReceiver, filter)
            precheckReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterPrecheckReceiver() {
        if (!precheckReceiverRegistered) return
        try {
            unregisterReceiver(precheckReceiver)
        } catch (_: Throwable) {
        }
        precheckReceiverRegistered = false
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            val bm = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return false
            if (Build.VERSION.SDK_INT >= 28) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isWifiEnabled(): Boolean {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun updatePrecheckStatus() {
        val btOn = isBluetoothEnabled()
        val locOn = isLocationEnabled()
        val wifiOn = isWifiEnabled()

        applyPrecheckItem(tvStateBluetooth, btOn)
        applyPrecheckItem(tvStateLocation, locOn)
        applyPrecheckItem(tvStateWifi, wifiOn)

        val missing = ArrayList<String>()
        if (!btOn) missing.add("蓝牙")
        if (!locOn) missing.add("位置")
        if (!wifiOn) missing.add("WLAN")

        isPrecheckReady = missing.isEmpty()
        if (isPrecheckReady) {
            tvPrecheckSummary.text = "全部就绪"
            tvPrecheckSummary.setTextColor(Color.parseColor("#3FB950"))
            tvPrecheckTip.text = "蓝牙、位置、WLAN 已开启，可正常连接设备"
            tvPrecheckTip.setTextColor(Color.parseColor("#8B949E"))
        } else {
            tvPrecheckSummary.text = "待开启 ${missing.size} 项"
            tvPrecheckSummary.setTextColor(Color.parseColor("#E3B341"))
            tvPrecheckTip.text = "请先开启 ${missing.joinToString("、")}（点击对应项可快速前往设置）"
            tvPrecheckTip.setTextColor(Color.parseColor("#E3B341"))
        }
        updateConnectButtonState()
    }

    private fun updateConnectButtonState() {
        when {
            connectedIp != null -> {
                btnConnect.isEnabled = true
                btnConnect.setTextColor(Color.WHITE)
                btnConnect.alpha = 1.0f
            }
            isConnecting -> {
                btnConnect.isEnabled = false
                btnConnect.setTextColor(Color.WHITE)
                btnConnect.alpha = 0.6f
            }
            !isPrecheckReady -> {
                btnConnect.isEnabled = false
                btnConnect.setTextColor(Color.parseColor("#8B949E"))
                btnConnect.alpha = 1.0f
            }
            else -> {
                btnConnect.isEnabled = true
                btnConnect.setTextColor(Color.WHITE)
                btnConnect.alpha = 1.0f
            }
        }
    }

    private fun applyPrecheckItem(tv: TextView, on: Boolean) {
        if (on) {
            tv.text = "已开启"
            tv.setTextColor(Color.parseColor("#3FB950"))
        } else {
            tv.text = "未开启"
            tv.setTextColor(Color.parseColor("#F85149"))
        }
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

            override fun onConnected(ssid: String, deviceIp: String, battery: DeviceProtocol.BatteryInfo?) {
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
        if (connecting) {
            btnConnect.text = "正在连接…"
        }
        updateConnectButtonState()
    }

    private fun showConnected(ssid: String, ip: String, battery: DeviceProtocol.BatteryInfo?) {
        connectedIp = ip
        tvStatus.text = "已连接"
        tvStatus.setTextColor(Color.parseColor("#3FB950"))
        tvSsid.text = ssid
        tvIp.text = ip
        tvBattery.text = when {
            battery == null -> "— —"
            battery.full -> "${battery.percent}%（已充满）"
            battery.charging -> "${battery.percent}%（充电中）"
            else -> "${battery.percent}%"
        }
        tvHint.text = "设备已就绪，点击下方按钮进入相机画面"
        btnConnect.text = "进入相机画面"
        updateConnectButtonState()
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
        }
        updateConnectButtonState()
    }

    private fun isPrivacyAccepted(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_PRIVACY_ACCEPTED, false)
    }

    private fun showPrivacyPolicyDialog(isReview: Boolean = false) {
        if (privacyDialog?.isShowing == true) return
        val message = """
            欢迎使用 Wifi Look！在您开始使用前，请仔细阅读并同意以下隐私协议与安全提示：

            1. 隐私保护与权限说明
            本应用为纯本地局域网相机客户端，不连接互联网、不收集或上传任何个人隐私数据。仅在您主动使用时申请以下最小权限：
            • 网络与 WLAN：用于连接局域网相机热点及本地 UDP 音视频/控制通信。
            • 蓝牙与位置：仅用于 BLE 搜索附近局域网设备及读取当前 Wi-Fi SSID，不会记录或上传位置信息。
            • 相册存储（低版本系统）：仅用于保存您主动拍摄的照片与录像。

            2. 安全提示与免责声明
            • 本应用仅作为局域网无线相机的辅助显示工具，非医疗器械，不用于任何医疗诊断或治疗。
            • 使用配套硬件时请注意设备温度、电量及操作安全；局域网视频流以本地明文传输，请在私密、可信的环境中连接使用。
            • 本软件依据 GPL-3.0 开源协议提供（Copyright © 2026 q3330），按“现状”分发且不提供任何明示或暗示的担保。因硬件质量、环境因素或不当操作造成的任何损失，软件作者不承担责任。
        """.trimIndent()

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("隐私协议与安全提示")
            .setMessage(message)

        if (isReview) {
            builder.setCancelable(true)
                .setPositiveButton("我知道了") { dialog, _ ->
                    dialog.dismiss()
                    privacyDialog = null
                }
        } else {
            builder.setCancelable(false)
                .setPositiveButton("同意并继续") { dialog, _ ->
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PRIVACY_ACCEPTED, true)
                        .apply()
                    dialog.dismiss()
                    privacyDialog = null
                    updatePrecheckStatus()
                    registerPrecheckReceiver()
                    if (!isConnecting) {
                        quickProbeCurrentWifi()
                    }
                }
                .setNegativeButton("不同意并退出") { dialog, _ ->
                    dialog.dismiss()
                    privacyDialog = null
                    exitAppCleanly()
                }
        }

        privacyDialog = builder.show()
    }

    private fun openCamera(ip: String) {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_DEVICE_IP, ip)
        }
        startActivity(intent)
    }
}
