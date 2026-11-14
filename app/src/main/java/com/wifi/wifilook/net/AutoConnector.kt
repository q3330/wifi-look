package com.wifi.wifilook.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper

class AutoConnector(private val context: Context) {

    interface Callback {
        fun onProgress(message: String)
        fun onConnected(ssid: String, deviceIp: String, battery: Int?)
        fun onNeedManualWifi(message: String)
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var active = false

    fun currentSsid(): String {
        return try {
            @Suppress("DEPRECATION")
            val raw = wifiManager.connectionInfo?.ssid ?: return "— —"
            val cleaned = raw.replace("\"", "")
            if (cleaned.isEmpty() || cleaned == "<unknown ssid>") "— —" else cleaned
        } catch (_: Throwable) {
            "— —"
        }
    }

    fun start(callback: Callback) {
        stop()
        active = true
        Thread({
            if (!active) return@Thread
            mainHandler.post { callback.onProgress("正在检测当前 Wi-Fi 设备…") }
            val existingWifi = DeviceProtocol.pickWifiNetwork(cm)
            if (existingWifi != null) {
                try {
                    cm.bindProcessToNetwork(existingWifi)
                } catch (_: Exception) {
                }
                val ip = DeviceProtocol.probeDevice(cm, existingWifi, 900)
                if (ip != null && active) {
                    val bat = DeviceProtocol.queryBatteryPercent(existingWifi, ip)
                    val ssid = currentSsid()
                    active = false
                    mainHandler.post { callback.onConnected(ssid, ip, bat) }
                    return@Thread
                }
            }

            if (!active) return@Thread
            mainHandler.post { callback.onProgress("正在通过蓝牙搜索附近设备…") }
            mainHandler.post { startBleScan(callback) }
        }, "auto-connect-probe").start()
    }

    fun stop() {
        active = false
        stopBleScan()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun releaseNetworkCallback() {
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(callback: Callback) {
        if (!active) return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            active = false
            callback.onNeedManualWifi("蓝牙未开启：请开启蓝牙，或直接在系统 Wi-Fi 设置中连接设备热点")
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            active = false
            callback.onNeedManualWifi("无法启动蓝牙扫描，请在系统 Wi-Fi 设置中手动连接设备热点")
            return
        }
        scanner = leScanner

        val timeoutRunnable = Runnable {
            if (!active) return@Runnable
            stop()
            callback.onNeedManualWifi("未搜索到设备热点：请确认设备已开机，或前往系统 Wi-Fi 设置手动连接")
        }
        mainHandler.postDelayed(timeoutRunnable, 15_000L)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!active) return
                val devName = result.device?.name ?: return
                val bytes = result.scanRecord?.bytes ?: return
                val hex = toHex(bytes)
                val split = hex.split("09ff")
                if (split.size <= 1 || split[1].length < 16 || hex.length < 13) return
                val payload = split[1]
                val headerTag = hex.substring(9, 13)
                if ("6699" == payload.substring(0, 4) && "201a" == headerTag) {
                    val bssid = formatMac(payload.substring(4, 16))
                    stopBleScan()
                    mainHandler.removeCallbacks(timeoutRunnable)
                    callback.onProgress("已发现设备 $devName，正在连接热点…")
                    connectToWifi(devName, bssid, callback)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!active) return
                stop()
                mainHandler.post {
                    callback.onNeedManualWifi("蓝牙扫描失败（错误码 $errorCode），请前往系统 Wi-Fi 设置手动连接")
                }
            }
        }
        scanCallback = cb
        try {
            leScanner.startScan(cb)
        } catch (t: Throwable) {
            stop()
            callback.onNeedManualWifi("蓝牙扫描权限不足：${t.message ?: "请检查权限"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val sc = scanCallback
        val le = scanner
        scanCallback = null
        scanner = null
        if (sc != null && le != null) {
            try {
                le.stopScan(sc)
            } catch (_: Throwable) {
            }
        }
    }

    private fun connectToWifi(ssid: String, bssid: String, callback: Callback) {
        if (Build.VERSION.SDK_INT >= 29) {
            releaseNetworkCallback()
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setBssid(MacAddress.fromString(bssid))
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!active) return
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (_: Exception) {
                    }
                    Thread({
                        val ip = DeviceProtocol.probeDevice(cm, network, 3000)
                        val bat = if (ip != null) DeviceProtocol.queryBatteryPercent(network, ip) else null
                        active = false
                        mainHandler.post {
                            if (ip != null) {
                                callback.onConnected(ssid, ip, bat)
                            } else {
                                callback.onNeedManualWifi("已连上热点 $ssid，但设备视频端口未响应，请确认设备状态")
                            }
                        }
                    }, "wifi-probe").start()
                }

                override fun onUnavailable() {
                    if (!active) return
                    active = false
                    mainHandler.post {
                        callback.onNeedManualWifi("热点 $ssid 连接失败或已取消，请在系统 Wi-Fi 设置中手动连接")
                    }
                }
            }
            networkCallback = netCb
            try {
                cm.requestNetwork(request, netCb)
            } catch (t: Throwable) {
                active = false
                mainHandler.post {
                    callback.onNeedManualWifi("发起 Wi-Fi 连接失败（${t.message ?: "权限受限"}），请在系统 Wi-Fi 设置中手动连接 $ssid")
                }
            }
        } else {
            @Suppress("DEPRECATION")
            Thread({
                try {
                    val conf = WifiConfiguration().apply {
                        SSID = "\"$ssid\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    val netId = wifiManager.addNetwork(conf)
                    if (netId != -1) {
                        wifiManager.enableNetwork(netId, true)
                    }
                    Thread.sleep(2500L)
                    val wifiNet = DeviceProtocol.pickWifiNetwork(cm)
                    if (wifiNet != null) {
                        val ip = DeviceProtocol.probeDevice(cm, wifiNet, 3000)
                        if (ip != null && active) {
                            val bat = DeviceProtocol.queryBatteryPercent(wifiNet, ip)
                            active = false
                            mainHandler.post { callback.onConnected(ssid, ip, bat) }
                            return@Thread
                        }
                    }
                } catch (_: Throwable) {
                }
                if (active) {
                    active = false
                    mainHandler.post {
                        callback.onNeedManualWifi("自动连接失败，请在系统 Wi-Fi 设置中手动连接 $ssid")
                    }
                }
            }, "legacy-wifi-connect").start()
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun formatMac(raw12: String): String {
        val sb = StringBuilder(17)
        for (i in 0 until 12 step 2) {
            if (i > 0) sb.append(':')
            sb.append(raw12[i]).append(raw12[i + 1])
        }
        return sb.toString()
    }
}
