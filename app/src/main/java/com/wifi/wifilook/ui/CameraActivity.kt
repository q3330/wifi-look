package com.wifi.wifilook.ui

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.wifi.wifilook.R
import com.wifi.wifilook.media.MediaGallerySaver
import com.wifi.wifilook.media.RotationFilter
import com.wifi.wifilook.media.VideoRecorder
import com.wifi.wifilook.net.DeviceProtocol
import com.wifi.wifilook.net.FrameAssembler
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraActivity : Activity() {

    companion object {
        const val EXTRA_DEVICE_IP = "device_ip"
        private const val REQ_LEGACY_STORAGE = 200
        private const val CIRCLE_OVERSCAN = 1.02f
    }

    private lateinit var preview: ImageView
    private lateinit var btnFlash: TextView
    private lateinit var btnMirror: TextView
    private lateinit var btnRotate: TextView
    private lateinit var btnSnap: Button
    private lateinit var btnRec: Button
    private lateinit var recIndicator: View
    private lateinit var recTime: TextView
    private lateinit var tvDisconnect: TextView
    private lateinit var tvCameraHint: TextView
    private lateinit var cfg: SharedPreferences

    private val running = AtomicBoolean(false)
    private val uiLoopActive = AtomicBoolean(false)
    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val rotFilter = RotationFilter()

    private lateinit var recorder: VideoRecorder

    @Volatile
    private var deviceIp: String? = null

    @Volatile
    private var activeNetwork: android.net.Network? = null

    @Volatile
    private var activeVideoSock: DatagramSocket? = null

    @Volatile
    private var ctrlSock: DatagramSocket? = null

    @Volatile
    private var pwmSeq = 0

    @Volatile
    private var flashOn = true

    @Volatile
    private var mirrorOn = false

    @Volatile
    private var rotateOn = false

    @Volatile
    private var lastAccel = 0

    @Volatile
    private var displayAngle = 0f

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var framesCount = 0L

    @Volatile
    private var lastKeepalive = 0L

    @Volatile
    private var rawJpegMode = false

    private var circleRect: Rect? = null
    private var matrixDirty = true
    private var lastVW = -1
    private var lastVH = -1
    private var lastBW = -1
    private var lastBH = -1
    private var lastAngleQ = Int.MIN_VALUE
    private var lastUiTickMs = 0L

    private val uiLoopRunnable = Runnable { uiLoop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        deviceIp = intent.getStringExtra(EXTRA_DEVICE_IP)
        cfg = getSharedPreferences("wifilook_cfg", MODE_PRIVATE)

        preview = findViewById(R.id.preview)
        btnFlash = findViewById(R.id.btnFlash)
        btnMirror = findViewById(R.id.btnMirror)
        btnRotate = findViewById(R.id.btnRotate)
        btnSnap = findViewById(R.id.btnSnap)
        btnRec = findViewById(R.id.btnRec)
        recIndicator = findViewById(R.id.recIndicator)
        recTime = findViewById(R.id.recTime)
        tvDisconnect = findViewById(R.id.tvDisconnect)
        tvCameraHint = findViewById(R.id.tvCameraHint)

        preview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = circleRect
                if (r != null && r.width() > 0 && r.height() > 0) {
                    outline.setOval(r.left, r.top, r.right, r.bottom)
                } else {
                    outline.setRect(0, 0, maxOf(0, view.width), maxOf(0, view.height))
                }
            }
        }

        mirrorOn = cfg.getBoolean("mirror", false)
        rotateOn = cfg.getBoolean("rotate", false)
        applyMirrorUi()
        applyRotateUi()
        applyFlashUi()

        recorder = VideoRecorder(
            cacheDir = cacheDir,
            frameSource = { latestFrame.get() },
            onFinished = { file, saved, error ->
                if (saved) {
                    val path = MediaGallerySaver.saveVideo(this, file)
                    runOnUiThread {
                        updateRecUi()
                        showHint(if (path != null) "录像已保存至相册 $path" else "录像保存失败")
                    }
                } else {
                    runOnUiThread {
                        updateRecUi()
                        showHint(error ?: "录像已取消")
                    }
                }
            }
        )

        btnFlash.setOnClickListener {
            flashOn = !flashOn
            applyFlashUi()
            sendFlashCommand(flashOn)
        }
        btnMirror.setOnClickListener {
            mirrorOn = !mirrorOn
            cfg.edit().putBoolean("mirror", mirrorOn).apply()
            applyMirrorUi()
        }
        btnRotate.setOnClickListener {
            rotateOn = !rotateOn
            cfg.edit().putBoolean("rotate", rotateOn).apply()
            rotFilter.reset()
            applyRotateUi()
        }
        btnSnap.setOnClickListener { handleSnap() }
        btnRec.setOnClickListener { handleRecord() }

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_LEGACY_STORAGE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (running.compareAndSet(false, true)) {
            Thread({ streamWorker() }, "camera-stream").start()
        }
        if (uiLoopActive.compareAndSet(false, true)) {
            preview.post(uiLoopRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        running.set(false)
        uiLoopActive.set(false)
        preview.removeCallbacks(uiLoopRunnable)
        try {
            activeVideoSock?.close()
        } catch (_: Exception) {
        }
        sendFlashOffOnExit()
    }

    override fun onStop() {
        super.onStop()
        if (recorder.isRecording) {
            recorder.abort()
            updateRecUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        latestFrame.set(null)
        preview.setImageBitmap(null)
    }

    private fun updateToggleStyle(btn: TextView, on: Boolean) {
        btn.setBackgroundResource(if (on) R.drawable.bg_pill_ctrl_on else R.drawable.bg_pill_ctrl)
        btn.setTextColor(if (on) Color.WHITE else Color.parseColor("#C9D1D9"))
    }

    private fun applyFlashUi() {
        updateToggleStyle(btnFlash, flashOn)
    }

    private fun applyMirrorUi() {
        preview.scaleX = if (mirrorOn) -1f else 1f
        updateToggleStyle(btnMirror, mirrorOn)
    }

    private fun applyRotateUi() {
        updateToggleStyle(btnRotate, rotateOn)
        if (!rotateOn) {
            displayAngle = 0f
        }
        matrixDirty = true
    }

    private fun handleSnap() {
        val bmp = latestFrame.get()
        if (bmp == null) {
            showHint("暂无画面，无法拍照")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            showHint("请先授予存储权限后再拍照")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_LEGACY_STORAGE)
            return
        }
        Thread({
            val savedPath = MediaGallerySaver.savePhoto(this, bmp, mirrorOn)
            runOnUiThread {
                showHint(if (savedPath != null) "照片已保存至相册 $savedPath" else "照片保存失败")
            }
        }, "save-photo").start()
    }

    private fun handleRecord() {
        if (recorder.isRecording) {
            recorder.stopAndSave()
            showHint("正在保存录像…")
            updateRecUi()
            return
        }
        if (deviceIp == null || framesCount == 0L) {
            showHint("设备未传回画面，无法录像")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            showHint("请先授予存储权限后再录像")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_LEGACY_STORAGE)
            return
        }
        recorder.start()
        showHint("开始录像…")
        updateRecUi()
    }

    private fun updateRecUi() {
        val rec = recorder.isRecording
        recIndicator.visibility = if (rec) View.VISIBLE else View.GONE
        btnRec.text = if (rec) "停止" else "录像"
        btnSnap.isEnabled = !rec
        btnSnap.alpha = if (rec) 0.4f else 1.0f
    }

    private fun showHint(msg: String) {
        tvCameraHint.text = msg
    }

    private fun sendFlashCommand(on: Boolean) {
        val ip = deviceIp ?: return
        val sock = ctrlSock ?: return
        val valInt = if (on) 1 else 0
        Thread({
            try {
                val addr = InetAddress.getByName(ip)
                for (i in 0 until 2) {
                    val seq = synchronized(this) { ++pwmSeq }
                    val cmd = DeviceProtocol.buildCtrlCmd(DeviceProtocol.CMD_SET_PWM, valInt, seq)
                    sock.send(DatagramPacket(cmd, cmd.size, addr, DeviceProtocol.CTRL_PORT))
                    if (i == 0) Thread.sleep(60L)
                }
            } catch (_: Throwable) {
            }
        }, "flash-cmd").start()
    }

    private fun sendFlashOffOnExit() {
        val ip = deviceIp ?: return
        val net = activeNetwork
        Thread({
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null)
                net?.bindSocket(sock)
                val addr = InetAddress.getByName(ip)
                for (i in 0 until 2) {
                    val seq = synchronized(this) { ++pwmSeq }
                    val cmd = DeviceProtocol.buildCtrlCmd(DeviceProtocol.CMD_SET_PWM, 0, seq)
                    sock.send(DatagramPacket(cmd, cmd.size, addr, DeviceProtocol.CTRL_PORT))
                    if (i == 0) Thread.sleep(50L)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }
        }, "flash-off-exit").start()
    }

    private fun uiLoop() {
        if (!running.get() || !uiLoopActive.get()) return
        val bmp = latestFrame.get()
        if (bmp != null && !bmp.isRecycled) {
            preview.setImageBitmap(bmp)
            updateDisplayMatrix(bmp)
        }
        val now = System.currentTimeMillis()
        if (now - lastUiTickMs >= 500L) {
            lastUiTickMs = now
            if (recorder.isRecording) {
                val sec = recorder.elapsedMs() / 1000L
                recTime.text = String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L)
            }
            val lost = framesCount > 0L && lastFrameAt > 0L && (now - lastFrameAt > 3000L)
            if (lost) {
                tvDisconnect.text = "连接中断"
                tvDisconnect.visibility = View.VISIBLE
                if (recorder.isRecording) {
                    recorder.stopAndSave()
                    updateRecUi()
                }
            } else if (framesCount > 0L) {
                tvDisconnect.visibility = View.GONE
            }
        }
        preview.postDelayed(uiLoopRunnable, 33L)
    }

    private fun updateDisplayMatrix(bitmap: Bitmap) {
        val vw = preview.width
        val vh = preview.height
        val bw = bitmap.width
        val bh = bitmap.height
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return

        if (rotateOn) {
            displayAngle = rotFilter.update(lastAccel)
        }

        val angleQ = Math.round(displayAngle * 10f)
        if (!matrixDirty &&
            vw == lastVW && vh == lastVH &&
            bw == lastBW && bh == lastBH &&
            angleQ == lastAngleQ
        ) {
            return
        }

        matrixDirty = false
        lastVW = vw
        lastVH = vh
        lastBW = bw
        lastBH = bh
        lastAngleQ = angleQ

        val circleSize = minOf(vw, vh)
        val circleTop = maxOf(0, (vh - circleSize) / 2)
        val left = (vw - circleSize) / 2
        val rect = Rect(left, circleTop, left + circleSize, circleTop + circleSize)
        if (rect != circleRect) {
            circleRect = rect
            preview.clipToOutline = true
            preview.invalidateOutline()
        }

        val angle = angleQ / 10f
        val matrix = Matrix()
        val scale = maxOf(circleSize.toFloat() / bw, circleSize.toFloat() / bh) * CIRCLE_OVERSCAN
        matrix.setScale(scale, scale)
        val cx = (bw * scale) / 2f
        val cy = (bh * scale) / 2f
        matrix.postRotate(angle, cx, cy)
        matrix.postTranslate(rect.exactCenterX() - cx, rect.exactCenterY() - cy)
        preview.imageMatrix = matrix
    }

    private fun streamWorker() {
        var videoSock: DatagramSocket? = null
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = DeviceProtocol.pickWifiNetwork(cm)
            if (wifi == null) {
                runOnUiThread {
                    tvDisconnect.text = "未连接设备 Wi-Fi"
                    tvDisconnect.visibility = View.VISIBLE
                }
                return
            }
            activeNetwork = wifi
            try {
                cm.bindProcessToNetwork(wifi)
            } catch (_: Exception) {
            }

            videoSock = DatagramSocket(null)
            activeVideoSock = videoSock
            wifi.bindSocket(videoSock)
            videoSock.soTimeout = 1200
            videoSock.receiveBufferSize = 1024 * 1024

            val buf = ByteArray(65536)
            val assembler = FrameAssembler()
            val candidates = ArrayList<String>().apply {
                deviceIp?.let { add(it) }
                for (c in DeviceProtocol.buildCandidateIps(cm, wifi)) {
                    if (!contains(c)) add(c)
                }
            }

            var activeIp: String? = deviceIp
            if (activeIp != null) {
                setupCtrlSocket(wifi)
                sendStartCmd(videoSock, activeIp, 2)
                sendFlashCommand(flashOn)
            }

            while (running.get()) {
                val now = System.currentTimeMillis()
                if (activeIp == null) {
                    for (ip in candidates) {
                        sendStartCmd(videoSock, ip, 2)
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        videoSock.receive(pkt)
                        if (DeviceProtocol.looksLikeFramePacket(buf, pkt.length)) {
                            activeIp = pkt.address.hostAddress
                            deviceIp = activeIp
                            setupCtrlSocket(wifi)
                            sendFlashCommand(flashOn)
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                    continue
                }

                if (now - lastKeepalive > 800L) {
                    sendStartCmd(videoSock, activeIp, 1)
                }

                val pkt = DatagramPacket(buf, buf.size)
                try {
                    videoSock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val len = pkt.length
                if ((!rawJpegMode || framesCount == 0L) &&
                    len > 100 &&
                    (buf[0].toInt() and 0xFF) == 0xFF &&
                    (buf[1].toInt() and 0xFF) == 0xD8
                ) {
                    val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                    if (bmp != null) {
                        rawJpegMode = true
                        framesCount++
                        latestFrame.set(bmp)
                        lastFrameAt = System.currentTimeMillis()
                    }
                    continue
                }

                val frameBytes = assembler.feed(buf, len)
                lastAccel = assembler.accelerometer
                if (frameBytes != null) {
                    val bmp = if (assembler.format == DeviceProtocol.FORMAT_YUYV) {
                        DeviceProtocol.decodeYuyv(frameBytes, assembler.width, assembler.height)
                    } else {
                        BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                    }
                    if (bmp != null) {
                        framesCount++
                        latestFrame.set(bmp)
                        lastFrameAt = System.currentTimeMillis()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            activeVideoSock = null
            try {
                videoSock?.close()
            } catch (_: Exception) {
            }
            try {
                ctrlSock?.close()
            } catch (_: Exception) {
            }
            ctrlSock = null
        }
    }

    private fun setupCtrlSocket(wifi: android.net.Network) {
        try {
            ctrlSock?.close()
        } catch (_: Exception) {
        }
        try {
            val s = DatagramSocket(null)
            wifi.bindSocket(s)
            s.receiveBufferSize = 65536
            ctrlSock = s
        } catch (_: Exception) {
        }
    }

    private fun sendStartCmd(sock: DatagramSocket, ip: String, count: Int) {
        try {
            val addr = InetAddress.getByName(ip)
            repeat(count) {
                sock.send(DatagramPacket(DeviceProtocol.START_CMD, DeviceProtocol.START_CMD.size, addr, DeviceProtocol.VIDEO_PORT))
            }
            lastKeepalive = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }
}
