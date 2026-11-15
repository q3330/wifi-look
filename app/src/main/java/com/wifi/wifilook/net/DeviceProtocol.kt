package com.wifi.wifilook.net

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object DeviceProtocol {
    data class BatteryInfo(
        val percent: Int,
        val charging: Boolean,
        val full: Boolean
    )

    const val VIDEO_PORT = 8032
    const val CTRL_PORT = 50000
    const val CMD_GET_PWM = 0x1015
    const val CMD_SET_PWM = 4118
    const val CMD_GET_BATTERY = 0x1017
    const val CMD_SET_LEVEL = 4180
    const val CMD_GET_LEVEL = 0x1055
    const val FORMAT_YUYV = 4

    val START_CMD: ByteArray = ByteArray(24).apply {
        this[0] = 0x99.toByte()
        this[1] = 0x99.toByte()
        this[2] = 0x01
        this[3] = 0x00
    }

    private val DEFAULT_CANDIDATES = listOf(
        "192.168.0.10",
        "192.168.1.1",
        "192.168.4.1",
        "192.168.10.123"
    )

    fun buildCtrlCmd(cmd: Int, value: Int, seq: Int): ByteArray = ByteArray(24).apply {
        this[0] = 0x99.toByte()
        this[1] = 0x99.toByte()
        this[2] = (cmd and 0xFF).toByte()
        this[3] = ((cmd ushr 8) and 0xFF).toByte()
        this[4] = (seq and 0xFF).toByte()
        this[5] = ((seq ushr 8) and 0xFF).toByte()
        this[6] = ((seq ushr 16) and 0xFF).toByte()
        this[7] = ((seq ushr 24) and 0xFF).toByte()
        this[8] = (value and 0xFF).toByte()
        this[9] = ((value ushr 8) and 0xFF).toByte()
        this[10] = ((value ushr 16) and 0xFF).toByte()
        this[11] = ((value ushr 24) and 0xFF).toByte()
    }

    fun pickWifiNetwork(cm: ConnectivityManager): Network? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network
            }
        }
        return null
    }

    fun buildCandidateIps(cm: ConnectivityManager, network: Network?): List<String> {
        val result = ArrayList<String>()
        if (network != null) {
            try {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    for (route in lp.routes) {
                        val gw = route.gateway
                        if (gw != null && !gw.isAnyLocalAddress) {
                            val host = gw.hostAddress
                            if (!host.isNullOrEmpty() && !result.contains(host)) {
                                result.add(host)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        for (ip in DEFAULT_CANDIDATES) {
            if (!result.contains(ip)) {
                result.add(ip)
            }
        }
        return result
    }

    fun looksLikeFramePacket(buf: ByteArray, length: Int): Boolean {
        if (length >= 24 && (buf[0].toInt() and 0xFF) == 102 && (buf[1].toInt() and 0xFF) == 1) {
            return true
        }
        return length > 100 && (buf[0].toInt() and 0xFF) == 0xFF && (buf[1].toInt() and 0xFF) == 0xD8
    }

    fun probeDevice(cm: ConnectivityManager, network: Network, timeoutMs: Int = 900): String? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket(null)
            network.bindSocket(sock)
            sock.soTimeout = 300
            val candidates = buildCandidateIps(cm, network)
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                for (ip in candidates) {
                    try {
                        val addr = InetAddress.getByName(ip)
                        sock.send(DatagramPacket(START_CMD, START_CMD.size, addr, VIDEO_PORT))
                    } catch (_: Exception) {
                    }
                }
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (looksLikeFramePacket(buf, pkt.length)) {
                        return pkt.address.hostAddress
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun queryBatteryStatus(network: Network, deviceIp: String): BatteryInfo? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket(null)
            network.bindSocket(sock)
            sock.soTimeout = 250
            val addr = InetAddress.getByName(deviceIp)
            val buf = ByteArray(128)
            for (seq in 1..3) {
                val cmd = buildCtrlCmd(CMD_GET_BATTERY, 0, seq)
                sock.send(DatagramPacket(cmd, cmd.size, addr, CTRL_PORT))
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (pkt.length >= 12 &&
                        readU16(buf, 0) == 0x9999 &&
                        readU16(buf, 2) == CMD_GET_BATTERY
                    ) {
                        val raw1 = readU32(buf, 8)
                        val status = raw1 ushr 16
                        val isPluggedIn = (status and 0x01) != 0
                        val isFull = isPluggedIn && (status and 0x02) != 0
                        if (pkt.length >= 20) {
                            val raw2 = readU32(buf, 16)
                            if ((raw2 and 0x10000) != 0) {
                                val pct = raw2 and 0xFFFF
                                if (pct in 0..100) {
                                    return BatteryInfo(
                                        percent = pct,
                                        charging = isPluggedIn && !isFull,
                                        full = isFull
                                    )
                                }
                            }
                        }
                        if (raw1 in 0..100) {
                            return BatteryInfo(percent = raw1, charging = false, full = false)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readU16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    fun decodeYuyv(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val totalPixels = width * height
        if (data.size < totalPixels * 2) return null
        val pixels = IntArray(totalPixels)
        var src = 0
        val pairs = totalPixels / 2
        for (i in 0 until pairs) {
            val y0 = data[src].toInt() and 0xFF
            val u = (data[src + 1].toInt() and 0xFF) - 128
            val y1 = data[src + 2].toInt() and 0xFF
            val v = (data[src + 3].toInt() and 0xFF) - 128
            src += 4

            val c0 = (y0 - 16) * 298
            val dU = u * 100
            val dV = v * 208
            val rV = v * 409
            val bU = u * 516

            val r0 = clamp255((c0 + rV + 128) shr 8)
            val g0 = clamp255((c0 - dU - dV + 128) shr 8)
            val b0 = clamp255((c0 + bU + 128) shr 8)

            val c1 = (y1 - 16) * 298
            val r1 = clamp255((c1 + rV + 128) shr 8)
            val g1 = clamp255((c1 - dU - dV + 128) shr 8)
            val b1 = clamp255((c1 + bU + 128) shr 8)

            val dst = i shl 1
            pixels[dst] = (0xFF shl 24) or (r0 shl 16) or (g0 shl 8) or b0
            pixels[dst + 1] = (0xFF shl 24) or (r1 shl 16) or (g1 shl 8) or b1
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun clamp255(v: Int): Int = when {
        v < 0 -> 0
        v > 255 -> 255
        else -> v
    }
}

class FrameAssembler {
    companion object {
        const val HEADER_SIZE = 24
        const val MAX_FRAME_SIZE = 8 * 1024 * 1024
    }

    var format: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var accelerometer: Int = 0
        private set

    private var buffer: ByteArray? = null
    private var expectedSize = 0
    private var accumulated = 0
    private var lastChunkSeq = -1

    fun reset() {
        buffer = null
        expectedSize = 0
        accumulated = 0
        lastChunkSeq = -1
    }

    fun feed(packet: ByteArray, length: Int): ByteArray? {
        if (length < HEADER_SIZE || (packet[0].toInt() and 0xFF) != 102) {
            return null
        }
        val totalSize = u32(packet, 4)
        val chunkSeq = u16(packet, 12)
        val chunkLen = u16(packet, 14)
        if (chunkLen <= 0 || chunkLen + HEADER_SIZE > length) return null
        if (totalSize <= 0 || totalSize > MAX_FRAME_SIZE) return null

        if (buffer == null || totalSize != expectedSize || chunkSeq < lastChunkSeq || chunkSeq == 0) {
            buffer = ByteArray(totalSize)
            expectedSize = totalSize
            accumulated = 0
            lastChunkSeq = -1
            format = packet[2].toInt() and 0xFF
            width = u16(packet, 8)
            height = u16(packet, 10)
        }

        if (chunkSeq != lastChunkSeq + 1) {
            reset()
            return null
        }
        val buf = buffer ?: return null
        if (accumulated + chunkLen > expectedSize) {
            reset()
            return null
        }

        System.arraycopy(packet, HEADER_SIZE, buf, accumulated, chunkLen)
        accumulated += chunkLen
        lastChunkSeq = chunkSeq
        accelerometer = u32(packet, 16)

        if (accumulated != expectedSize) return null

        reset()
        return buf
    }

    private fun u16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
