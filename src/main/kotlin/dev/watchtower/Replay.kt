package dev.watchtower

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screenshot replay. Every frame is masked on the device before it is encoded: text views and
 * inputs are painted over by default, passwords always, and the frame is only marked `masked`
 * after that painting. The edge drops the image of any frame that is not marked masked.
 * Frames go out as rrweb custom events (`type` 5, tag `wt.screenshot`) through the replay chunk route.
 */
internal object Replay {
    private val seq = AtomicInteger()

    fun install(app: Application, cfg: Config, wt: Watchtower) {
        val main = Handler(Looper.getMainLooper())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var tick: Runnable? = null
            override fun onActivityResumed(a: Activity) {
                val r = object : Runnable {
                    override fun run() {
                        runCatching { capture(a, cfg.replay)?.let { wt.postReplay(seq.getAndIncrement(), JSONArray().put(it).toString()) } }
                        main.postDelayed(this, cfg.replay.intervalMs)
                    }
                }
                tick = r
                main.postDelayed(r, cfg.replay.intervalMs)
            }
            override fun onActivityPaused(a: Activity) { tick?.let(main::removeCallbacks) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun kind(v: View): Kind = when {
        v is EditText && (v.inputType and 0x80 != 0 || v.inputType and 0x10 != 0) -> Kind.PASSWORD
        v is EditText -> Kind.INPUT
        v is TextView -> Kind.TEXT
        v is ImageView -> Kind.IMAGE
        else -> Kind.OTHER
    }

    /** View tree to [Node]s, in root coordinates. Hidden views are skipped. */
    private fun tree(v: View, ox: Int, oy: Int): Node {
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val kids = (v as? ViewGroup)?.let { g ->
            (0 until g.childCount).map { g.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.map { tree(it, ox, oy) }
        }.orEmpty()
        val tag = v.tag?.toString()
        return Node(kind(v), loc[0] - ox, loc[1] - oy, v.width, v.height, tag == "wt-unmask", tag == "wt-mask", kids)
    }

    internal fun capture(a: Activity, cfg: ReplayConfig): JSONObject? {
        val root = a.window.decorView
        if (root.width <= 0 || root.height <= 0) return null
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val rects = maskRects(tree(root, loc[0], loc[1]), cfg.maskText)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.RGB_565)
        root.draw(Canvas(bmp))
        val paint = Paint().apply { color = Color.DKGRAY; style = Paint.Style.FILL }
        val c = Canvas(bmp)
        rects.forEach { c.drawRect(it.x.toFloat(), it.y.toFloat(), (it.x + it.w).toFloat(), (it.y + it.h).toFloat(), paint) }
        val scale = minOf(1f, cfg.maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val jpg = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, cfg.jpegQuality, it) }
        return screenshotEvent(
            System.currentTimeMillis(), Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP),
            out.width, out.height, a.javaClass.simpleName, rects.size, masked = true,
        )
    }
}

/** The rrweb custom event the edge understands. `masked` is only true after masks were painted. */
fun screenshotEvent(tsMs: Long, imageB64: String, w: Int, h: Int, screen: String, masks: Int, masked: Boolean): JSONObject =
    JSONObject().put("type", 5).put("timestamp", tsMs).put(
        "data",
        JSONObject().put("tag", "wt.screenshot").put(
            "payload",
            JSONObject().put("image", imageB64).put("mime", "image/jpeg").put("w", w).put("h", h)
                .put("screen", screen).put("mask_count", masks).put("masked", masked),
        ),
    )
