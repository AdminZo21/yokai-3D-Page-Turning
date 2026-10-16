package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a realistic page curl (a rolled fold that sweeps across the page, the back of the sheet
 * and a soft shadow on the page underneath) with an AGSL shader.
 *
 * The page being turned is captured once into a bitmap with [capture]. [draw] then renders only the
 * curled sheet on top of the real pager content: pixels where the next page should show through are
 * left transparent, so the next page never has to be captured and keeps loading normally.
 *
 * The geometry is a cylinder fold. The sheet is measured along a direction [TILT_DEGREES] away from
 * horizontal; [progress] moves the fold line from the free edge (0) all the way past the spine (1).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PageCurlRenderer {

    private val shader = RuntimeShader(SHADER)
    private val paint = Paint().apply { shader = this@PageCurlRenderer.shader }

    /** Fraction of the turn, from 0 (flat page) to 1 (fully turned). */
    var progress = 0f

    /** True for right-to-left: the curl starts at the left edge instead of the right one. */
    var mirror = false

    /** Whether there is a captured page to draw. */
    var isActive = false
        private set

    /**
     * Captures [page] into a bitmap painted over [background]. Returns false when the page can't be
     * captured (for example it holds a hardware bitmap), in which case nothing is drawn.
     */
    fun capture(page: View, background: Int): Boolean {
        val width = page.width
        val height = page.height
        if (width <= 0 || height <= 0) return false
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background or OPAQUE)
            page.draw(canvas)
            shader.setInputShader("page", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            isActive = true
            true
        } catch (e: Exception) {
            isActive = false
            false
        } catch (e: OutOfMemoryError) {
            isActive = false
            false
        }
    }

    /** Forgets the captured page and stops drawing. */
    fun release() {
        isActive = false
    }

    /** Draws the curl over a [width] x [height] area whose origin is the canvas origin. */
    fun draw(canvas: Canvas, width: Int, height: Int) {
        if (!isActive || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val angle = Math.toRadians(TILT_DEGREES.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val tMin = min(min(0f, w * c), min(h * s, w * c + h * s))
        val tMax = max(max(0f, w * c), max(h * s, w * c + h * s))
        val length = tMax - tMin
        val radius = RADIUS_FRACTION * w
        // The fold starts beyond the free edge and ends fully past the spine.
        val foldPosition = length - progress.coerceIn(0f, 1f) * (length + radius)

        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("dirv", c, s)
        shader.setFloatUniform("tmin", tMin)
        shader.setFloatUniform("s0", foldPosition)
        shader.setFloatUniform("r", radius)
        shader.setFloatUniform("mirror", if (mirror) 1f else 0f)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    companion object {
        /** Angle of the fold line. Positive makes the bottom corner lead, like a lifted corner. */
        const val TILT_DEGREES = 9f

        /** Radius of the rolled fold as a fraction of the page width. */
        const val RADIUS_FRACTION = 0.085f

        private const val OPAQUE = 0xFF000000.toInt()

        /**
         * Inverse-mapped cylinder curl. For every output pixel (u along the sweep direction, v across
         * it) it decides whether the pixel shows the flat page, the sheet's back lying over it, the
         * rolled fold, or nothing (the page underneath, plus a soft shadow).
         */
        internal const val SHADER = """
uniform shader page;
uniform float2 size;
uniform float2 dirv;
uniform float tmin;
uniform float s0;
uniform float r;
uniform float mirror;

const float PI = 3.14159265;

float3 paper() {
    return float3(0.957, 0.945, 0.918);
}

// Where along the sheet (t, v) lives in page space: returns (x, y, insidePage).
float3 srcPos(float t, float v) {
    float tt = t + tmin;
    float xs = tt * dirv.x - v * dirv.y;
    float ys = tt * dirv.y + v * dirv.x;
    float inside = (xs >= 0.0 && xs <= size.x && ys >= 0.0 && ys <= size.y) ? 1.0 : 0.0;
    return float3(xs, ys, inside);
}

float2 toPage(float3 p) {
    return float2(mirror > 0.5 ? size.x - p.x : p.x, p.y);
}

float flapCover(float uu, float v) {
    if (uu >= s0) {
        return 0.0;
    }
    return srcPos(s0 + PI * r + (s0 - uu), v).z;
}

// The back of the paper: mostly blank, with a faint ghost of the printed side.
float3 backFace(float3 p) {
    float3 content = float3(page.eval(toPage(p)).rgb);
    return mix(paper(), content, 0.18);
}

half4 main(float2 fc) {
    float2 q = float2(mirror > 0.5 ? size.x - fc.x : fc.x, fc.y);
    float u = q.x * dirv.x + q.y * dirv.y - tmin;
    float v = -q.x * dirv.y + q.y * dirv.x;
    float sc = size.x / 540.0;

    // Flat part of the page, possibly covered by the folded-over back of the sheet.
    if (u < s0) {
        float3 f = srcPos(s0 + PI * r + (s0 - u), v);
        if (f.z > 0.5) {
            float near = clamp((s0 - u) / (1.2 * r), 0.0, 1.0);
            float3 col = backFace(f) * (0.80 + 0.20 * near);
            return half4(half3(col), 1.0);
        }
        float cov = flapCover(u + 4.0 * sc, v) + flapCover(u + 10.0 * sc, v) +
            flapCover(u + 18.0 * sc, v) + flapCover(u + 28.0 * sc, v) +
            flapCover(u + 40.0 * sc, v);
        float3 front = float3(page.eval(fc).rgb) * (1.0 - 0.40 * cov / 5.0);
        return half4(half3(front), 1.0);
    }

    float ex = srcPos(s0 + r * PI * 0.5, v).z;

    // The rolled fold: the top half of the cylinder as seen from above.
    if (u <= s0 + r) {
        float sn = clamp((u - s0) / r, 0.0, 1.0);
        float phi = PI - asin(sn);
        float3 c = srcPos(s0 + r * phi, v);
        if (c.z > 0.5) {
            float shade = 0.5 + 0.5 * (-cos(phi));
            float spec = 0.10 * exp(-((sn - 0.35) * (sn - 0.35)) / 0.02);
            float3 col = clamp(backFace(c) * shade + spec, 0.0, 1.0);
            return half4(half3(col), 1.0);
        }
    }

    // Beyond the fold the next page shows through; only the sheet's shadow is drawn.
    float a = 0.55 * exp(-max(u - (s0 + r), 0.0) / (0.9 * r)) * ex;
    return half4(0.0, 0.0, 0.0, half(a));
}
"""
    }
}
