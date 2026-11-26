package com.corentinlimier.ganblenativetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

/**
 * Minimal wireframe cube renderer, rotated by a quaternion decoded from the
 * GAN cube's own BLE GYRO notifications (not the phone's sensors). No
 * OpenGL — just manual quaternion-to-matrix + a simple perspective
 * projection drawn with Canvas, since that's much lower-risk to get right
 * without being able to build/run this before handing it over than setting
 * up a GLSurfaceView + shaders blind.
 */
class CubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var qx = 0f
    @Volatile private var qy = 0f
    @Volatile private var qz = 0f
    @Volatile private var qw = 1f

    private val baseVertices = arrayOf(
        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
        floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
    )

    private val edges = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0), // near face
        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4), // far face
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)  // connecting edges
    )

    private val edgePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#101418") }
    private val textPaint = Paint().apply {
        color = Color.GRAY
        textSize = 24f
        isAntiAlias = true
    }

    private var lastUpdateMs = 0L

    /** Called from any thread (BLE callbacks run on a Binder thread, not the UI thread). */
    fun setQuaternion(x: Float, y: Float, z: Float, w: Float) {
        qx = x; qy = y; qz = z; qw = w
        lastUpdateMs = System.currentTimeMillis()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (lastUpdateMs == 0L) {
            canvas.drawText("Waiting for GYRO data from the cube…", 16f, 28f, textPaint)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) / 5f

        val norm = sqrt((qx * qx + qy * qy + qz * qz + qw * qw).toDouble()).toFloat()
        val n = if (norm == 0f) 1f else norm
        val x = qx / n
        val y = qy / n
        val z = qz / n
        val w = qw / n

        // Standard quaternion -> 3x3 rotation matrix.
        val m00 = 1 - 2 * (y * y + z * z); val m01 = 2 * (x * y - w * z); val m02 = 2 * (x * z + w * y)
        val m10 = 2 * (x * y + w * z); val m11 = 1 - 2 * (x * x + z * z); val m12 = 2 * (y * z - w * x)
        val m20 = 2 * (x * z - w * y); val m21 = 2 * (y * z + w * x); val m22 = 1 - 2 * (x * x + y * y)

        val projected = baseVertices.map { v ->
            val rx = m00 * v[0] + m01 * v[1] + m02 * v[2]
            val ry = m10 * v[0] + m11 * v[1] + m12 * v[2]
            val rz = m20 * v[0] + m21 * v[1] + m22 * v[2]
            val perspective = 4f / (5f + rz) // camera looking down +z from z=-5, closer faces appear bigger
            Pair(cx + rx * scale * perspective, cy - ry * scale * perspective)
        }

        for (edge in edges) {
            val (x1, y1) = projected[edge[0]]
            val (x2, y2) = projected[edge[1]]
            canvas.drawLine(x1, y1, x2, y2, edgePaint)
        }
    }
}
