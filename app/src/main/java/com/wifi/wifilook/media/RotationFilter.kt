package com.wifi.wifilook.media

import kotlin.math.atan
import kotlin.math.sqrt

class RotationFilter {
    companion object {
        private const val WINDOW = 20
    }

    private val xs = IntArray(WINDOW)
    private val ys = IntArray(WINDOW)
    private val zs = IntArray(WINDOW)
    private var count = 0
    private var head = 0
    private var lastAngle = 0f

    fun update(packedAccel: Int): Float {
        val rawX = (packedAccel ushr 20) and 1023
        val rawY = (packedAccel ushr 10) and 1023
        val rawZ = packedAccel and 1023

        val x = if (rawX >= 512) 1024 - rawX else rawX
        val y = if (rawY >= 512) 1024 - rawY else rawY
        val z = if (rawZ >= 512) 1024 - rawZ else rawZ

        xs[head] = x
        ys[head] = y
        zs[head] = z
        head = (head + 1) % WINDOW
        if (count < WINDOW) {
            count++
        }
        if (count < WINDOW) {
            return lastAngle
        }
        if (stddev(xs) <= 3f && stddev(ys) <= 3f && stddev(zs) <= 3f) {
            return lastAngle
        }
        if (z == 0) {
            return lastAngle
        }
        var angle = atan(y.toDouble() / z.toDouble())
        if (rawZ > 512) {
            angle = Math.PI - angle
        }
        if (rawY > 512) {
            angle = 2.0 * Math.PI - angle
        }
        val degrees = Math.toDegrees((angle + Math.PI) % (2.0 * Math.PI)).toFloat()
        lastAngle = degrees
        return degrees
    }

    fun reset() {
        count = 0
        head = 0
        lastAngle = 0f
    }

    private fun stddev(arr: IntArray): Float {
        var sum = 0.0
        for (v in arr) sum += v
        val mean = sum / WINDOW
        var sq = 0.0
        for (v in arr) {
            val d = v - mean
            sq += d * d
        }
        return sqrt(sq / WINDOW).toFloat()
    }
}
