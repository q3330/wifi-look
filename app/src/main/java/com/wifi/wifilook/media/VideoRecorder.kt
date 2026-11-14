package com.wifi.wifilook.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(
    private val cacheDir: File,
    private val frameSource: () -> Bitmap?,
    private val onFinished: (file: File, saved: Boolean, error: String?) -> Unit
) {
    companion object {
        private const val W = 640
        private const val H = 360
        private const val FPS = 12
        private const val BITRATE = 2_000_000
        private const val MIME = "video/avc"
    }

    @Volatile
    var isRecording = false
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var discard = false

    @Volatile
    private var startMs = 0L

    @Volatile
    var framesWritten = 0L
        private set

    private var thread: Thread? = null
    private var tmpFile: File? = null

    fun elapsedMs(): Long = if (isRecording) System.currentTimeMillis() - startMs else 0L

    fun start() {
        if (isRecording) return
        val out = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        tmpFile = out
        discard = false
        stopRequested = false
        framesWritten = 0L
        startMs = System.currentTimeMillis()
        isRecording = true
        thread = Thread({ encodeLoop(out) }, "video-recorder").also { it.start() }
    }

    fun stopAndSave() {
        discard = false
        stopRequested = true
    }

    fun abort() {
        discard = true
        stopRequested = true
    }

    private fun encodeLoop(outFile: File) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var errorMsg: String? = null
        val yuvBuffer = ByteArray(W * H * 3 / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        val colorFormats = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )

        var selectedFormat = 0
        for (cf in colorFormats) {
            try {
                val format = MediaFormat.createVideoFormat(MIME, W, H).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                }
                val c = MediaCodec.createEncoderByType(MIME)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec = c
                selectedFormat = cf
                break
            } catch (_: Throwable) {
                try {
                    codec?.release()
                } catch (_: Throwable) {
                }
                codec = null
            }
        }

        if (codec == null) {
            isRecording = false
            outFile.delete()
            onFinished(outFile, false, "设备不支持 H.264 视频编码")
            return
        }

        val isPlanar = selectedFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val scaledBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBitmap)
        val paint = Paint().apply { isFilterBitmap = true }
        val srcRect = Rect()
        val dstRect = Rect(0, 0, W, H)

        try {
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var nextFrameMs = System.currentTimeMillis()
            var lastPtsUs = -1L
            val frameStepMs = 1000L / FPS

            while (!stopRequested) {
                val now = System.currentTimeMillis()
                if (now >= nextFrameMs) {
                    nextFrameMs += frameStepMs
                    if (nextFrameMs < now) nextFrameMs = now + frameStepMs

                    val src = frameSource()
                    if (src != null && !src.isRecycled) {
                        val frame = if (src.width == W && src.height == H) {
                            src
                        } else {
                            srcRect.set(0, 0, src.width, src.height)
                            canvas.drawBitmap(src, srcRect, dstRect, paint)
                            scaledBitmap
                        }
                        if (isPlanar) toI420(frame, yuvBuffer) else toNv12(frame, yuvBuffer)

                        val inIdx = codec.dequeueInputBuffer(5000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                inBuf.put(yuvBuffer)
                                val ptsUs = maxOf((now - startMs) * 1000L, lastPtsUs + 1L)
                                lastPtsUs = ptsUs
                                codec.queueInputBuffer(inIdx, 0, yuvBuffer.size, ptsUs, 0)
                                framesWritten++
                            }
                        }
                    }
                }

                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }

                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                }
            }

            // Signal EOS and drain
            val eosIdx = codec.dequeueInputBuffer(10_000L)
            if (eosIdx >= 0) {
                codec.queueInputBuffer(
                    eosIdx,
                    0,
                    0,
                    maxOf((System.currentTimeMillis() - startMs) * 1000L, lastPtsUs + 1L),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            var drainTries = 0
            while (drainTries < 40) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    drainTries++
                    continue
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    continue
                }
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) break
                }
            }
        } catch (t: Throwable) {
            errorMsg = t.message ?: "录像编码异常"
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            scaledBitmap.recycle()
            isRecording = false
        }

        val success = !discard && errorMsg == null && framesWritten > 0 && outFile.exists() && outFile.length() > 0
        if (!success) {
            outFile.delete()
        }
        onFinished(outFile, success, errorMsg)
    }

    private fun toI420(bmp: Bitmap, out: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val ySize = w * h
        var uPos = ySize
        var vPos = ySize + ySize / 4
        var yPos = 0
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                out[yPos++] = luma((px ushr 16) and 0xFF, (px ushr 8) and 0xFF, px and 0xFF).toByte()
            }
            if ((y and 1) == 0) {
                var x = 0
                while (x + 1 < w) {
                    val p0 = row[x]
                    val p1 = row[x + 1]
                    val r = (((p0 ushr 16) and 0xFF) + ((p1 ushr 16) and 0xFF)) ushr 1
                    val g = (((p0 ushr 8) and 0xFF) + ((p1 ushr 8) and 0xFF)) ushr 1
                    val b = ((p0 and 0xFF) + (p1 and 0xFF)) ushr 1
                    out[uPos++] = chromaU(r, g, b).toByte()
                    out[vPos++] = chromaV(r, g, b).toByte()
                    x += 2
                }
            }
        }
    }

    private fun toNv12(bmp: Bitmap, out: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var uvPos = w * h
        var yPos = 0
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                out[yPos++] = luma((px ushr 16) and 0xFF, (px ushr 8) and 0xFF, px and 0xFF).toByte()
            }
            if ((y and 1) == 0) {
                var x = 0
                while (x + 1 < w) {
                    val p0 = row[x]
                    val p1 = row[x + 1]
                    val r = (((p0 ushr 16) and 0xFF) + ((p1 ushr 16) and 0xFF)) ushr 1
                    val g = (((p0 ushr 8) and 0xFF) + ((p1 ushr 8) and 0xFF)) ushr 1
                    val b = ((p0 and 0xFF) + (p1 and 0xFF)) ushr 1
                    out[uvPos++] = chromaU(r, g, b).toByte()
                    out[uvPos++] = chromaV(r, g, b).toByte()
                    x += 2
                }
            }
        }
    }

    private fun luma(r: Int, g: Int, b: Int): Int =
        clamp255((((r * 66 + g * 129 + b * 25 + 128) shr 8) + 16))

    private fun chromaU(r: Int, g: Int, b: Int): Int =
        clamp255((((r * -38 - g * 74 + b * 112 + 128) shr 8) + 128))

    private fun chromaV(r: Int, g: Int, b: Int): Int =
        clamp255((((r * 112 - g * 94 - b * 18 + 128) shr 8) + 128))

    private fun clamp255(v: Int): Int = when {
        v < 0 -> 0
        v > 255 -> 255
        else -> v
    }
}

object MediaGallerySaver {
    fun savePhoto(context: Context, source: Bitmap, mirror: Boolean): String? {
        val name = "wifilook_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val bmp = if (mirror) {
            val m = Matrix().apply { postScale(-1f, 1f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, false)
        } else {
            source
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WifiLook")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                } ?: return null
                "Pictures/WifiLook/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "WifiLook"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                "Pictures/WifiLook/$name"
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (bmp !== source && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    fun saveVideo(context: Context, tmpFile: File): String? {
        val name = "wifilook_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/WifiLook")
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                FileInputStream(tmpFile).use { input ->
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        input.copyTo(out, 65536)
                        out.flush()
                    } ?: return null
                }
                "Movies/WifiLook/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "WifiLook"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileInputStream(tmpFile).use { input ->
                    FileOutputStream(file).use { out ->
                        input.copyTo(out, 65536)
                        out.flush()
                    }
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                "Movies/WifiLook/$name"
            }
        } catch (_: Throwable) {
            null
        } finally {
            tmpFile.delete()
        }
    }
}
