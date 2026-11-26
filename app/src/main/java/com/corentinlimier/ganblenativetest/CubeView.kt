package com.corentinlimier.ganblenativetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

/**
 * Renders a colored 54-sticker Rubik's cube, rotated by a quaternion decoded
 * from the GAN cube's own BLE GYRO notifications (not the phone's sensors),
 * with sticker colors driven by the decoded facelet state (FACELETS sync +
 * MOVE replay, done in MainActivity). No OpenGL — manual quaternion-to-
 * matrix + a simple perspective projection drawn with Canvas, since that's
 * much lower-risk to get right without a build/test loop than a
 * GLSurfaceView + shaders.
 *
 * Sticker layout/orientation matches the same coordinate convention already
 * used (and reasoned through) in the web tool's Three.js cube renderer:
 * face order 0..5 = U R F D L B, each face's 9 stickers in Kociemba facelet
 * index order (row-major, index = face*9 + row*3 + col).
 */
class CubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var qx = 0f
    @Volatile private var qy = 0f
    @Volatile private var qz = 0f
    @Volatile private var qw = 1f
    @Volatile private var haveOrientation = false

    private data class Sticker(val corners: Array<FloatArray>, val normal: FloatArray, var color: Int)

    companion object {
        private const val HALF = 0.41f
        private const val OFFSET = 1.52f

        val FACE_COLOR = mapOf(
            'U' to Color.WHITE,
            'R' to Color.rgb(196, 30, 58),
            'F' to Color.rgb(0, 132, 61),
            'D' to Color.rgb(255, 213, 0),
            'L' to Color.rgb(255, 140, 0),
            'B' to Color.rgb(0, 81, 186)
        )

        const val SOLVED_FACELETS =
            "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"

        private fun quadXZ(cx: Float, cy: Float, cz: Float): Array<FloatArray> = arrayOf(
            floatArrayOf(cx - HALF, cy, cz - HALF), floatArrayOf(cx + HALF, cy, cz - HALF),
            floatArrayOf(cx + HALF, cy, cz + HALF), floatArrayOf(cx - HALF, cy, cz + HALF)
        )

        private fun quadXY(cx: Float, cy: Float, cz: Float): Array<FloatArray> = arrayOf(
            floatArrayOf(cx - HALF, cy - HALF, cz), floatArrayOf(cx + HALF, cy - HALF, cz),
            floatArrayOf(cx + HALF, cy + HALF, cz), floatArrayOf(cx - HALF, cy + HALF, cz)
        )

        private fun quadZY(cx: Float, cy: Float, cz: Float): Array<FloatArray> = arrayOf(
            floatArrayOf(cx, cy - HALF, cz - HALF), floatArrayOf(cx, cy - HALF, cz + HALF),
            floatArrayOf(cx, cy + HALF, cz + HALF), floatArrayOf(cx, cy + HALF, cz - HALF)
        )

        /** face: 0=U 1=R 2=F 3=D 4=L 5=B, matching the GAN protocol's own face numbering. */
        private fun cornersAndNormalFor(face: Int, r: Int, c: Int): Pair<Array<FloatArray>, FloatArray> {
            return when (face) {
                0 -> { // U, normal +Y
                    val cx = (c - 1).toFloat(); val cz = (r - 1).toFloat()
                    quadXZ(cx, OFFSET, cz) to floatArrayOf(0f, 1f, 0f)
                }
                1 -> { // R, normal +X
                    val cz = (1 - c).toFloat(); val cy = (1 - r).toFloat()
                    quadZY(OFFSET, cy, cz) to floatArrayOf(1f, 0f, 0f)
                }
                2 -> { // F, normal +Z
                    val cx = (c - 1).toFloat(); val cy = (1 - r).toFloat()
                    quadXY(cx, cy, OFFSET) to floatArrayOf(0f, 0f, 1f)
                }
                3 -> { // D, normal -Y
                    val cx = (c - 1).toFloat(); val cz = (1 - r).toFloat()
                    quadXZ(cx, -OFFSET, cz) to floatArrayOf(0f, -1f, 0f)
                }
                4 -> { // L, normal -X
                    val cz = (c - 1).toFloat(); val cy = (1 - r).toFloat()
                    quadZY(-OFFSET, cy, cz) to floatArrayOf(-1f, 0f, 0f)
                }
                else -> { // B, normal -Z
                    val cx = (1 - c).toFloat(); val cy = (1 - r).toFloat()
                    quadXY(cx, cy, -OFFSET) to floatArrayOf(0f, 0f, -1f)
                }
            }
        }

        private fun buildStickers(): Array<Sticker> {
            val list = ArrayList<Sticker>(54)
            for (face in 0..5) {
                for (r in 0..2) {
                    for (c in 0..2) {
                        val (corners, normal) = cornersAndNormalFor(face, r, c)
                        list.add(Sticker(corners, normal, Color.DKGRAY))
                    }
                }
            }
            return list.toTypedArray()
        }
    }

    private val stickers: Array<Sticker> = buildStickers().also { applyFacelets(it, SOLVED_FACELETS) }

    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val borderPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#101418") }
    private val textPaint = Paint().apply { color = Color.GRAY; textSize = 24f; isAntiAlias = true }

    /** Called from any thread (BLE callbacks run on a Binder thread, not the UI thread). */
    fun setQuaternion(x: Float, y: Float, z: Float, w: Float) {
        qx = x; qy = y; qz = z; qw = w
        haveOrientation = true
        postInvalidate()
    }

    /** facelets: 54-char Kociemba string, e.g. SOLVED_FACELETS. Called from any thread. */
    fun setFacelets(facelets: String) {
        applyFacelets(stickers, facelets)
        postInvalidate()
    }

    private fun applyFacelets(target: Array<Sticker>, facelets: String) {
        for (i in target.indices) {
            val letter = facelets.getOrNull(i) ?: SOLVED_FACELETS[i]
            target[i].color = FACE_COLOR[letter] ?: Color.DKGRAY
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (!haveOrientation) {
            canvas.drawText("Waiting for GYRO data from the cube…", 16f, 28f, textPaint)
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

        fun rotate(v: FloatArray): FloatArray = floatArrayOf(
            m00 * v[0] + m01 * v[1] + m02 * v[2],
            m10 * v[0] + m11 * v[1] + m12 * v[2],
            m20 * v[0] + m21 * v[1] + m22 * v[2]
        )

        fun project(v: FloatArray): Pair<Float, Float> {
            val perspective = 4f / (5f + v[2]) // camera looking down +z from z=-5
            return Pair(cx + v[0] * scale * perspective, cy - v[1] * scale * perspective)
        }

        val path = Path()
        for (sticker in stickers) {
            val rotatedNormal = rotate(sticker.normal)
            // Convex solid: a face is visible iff its outward normal points toward the
            // camera (negative Z here) — simple backface cull, no depth sort needed.
            if (rotatedNormal[2] >= 0f) continue

            val projected = sticker.corners.map { project(rotate(it)) }
            path.reset()
            path.moveTo(projected[0].first, projected[0].second)
            for (i in 1 until projected.size) path.lineTo(projected[i].first, projected[i].second)
            path.close()

            fillPaint.color = sticker.color
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, borderPaint)
        }
    }
}
