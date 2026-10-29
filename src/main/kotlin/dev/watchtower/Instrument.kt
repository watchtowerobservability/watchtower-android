package dev.watchtower

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import java.util.concurrent.atomic.AtomicLong

/** Crash, ANR, app-start and frame metrics. Everything here only calls [Watchtower.record]. */
internal object Instrument {
    private const val FROZEN_NS = 700_000_000L
    private const val SLOW_NS = 16_700_000L

    fun install(app: Application, cfg: Config) {
        crashHandler()
        anrWatchdog(cfg.anrThresholdMs)
        appStartAndFrames(app)
    }

    private fun crashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // written to disk before the process dies; sent on the next launch
            runCatching {
                Watchtower.record(
                    Event(
                        "error", e.javaClass.simpleName,
                        attrs = mapOf(
                            "error.message" to (e.message ?: ""),
                            "error.stack" to e.stackTraceToString().take(8000),
                            "crash.class" to "exception",
                            "crash.handled" to false,
                            "thread.name" to t.name,
                        ),
                    ),
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Posts a tick to the main thread; if it has not run within the threshold the main thread is hung. */
    private fun anrWatchdog(thresholdMs: Long) {
        val main = Handler(Looper.getMainLooper())
        val tick = AtomicLong(SystemClock.uptimeMillis())
        Thread({
            var reported = false
            while (!Thread.currentThread().isInterrupted) {
                val sent = SystemClock.uptimeMillis()
                main.post { tick.set(SystemClock.uptimeMillis()) }
                try { Thread.sleep(thresholdMs) } catch (_: InterruptedException) { return@Thread }
                val hung = tick.get() < sent
                if (hung && !reported) {
                    reported = true
                    Watchtower.record(
                        Event(
                            "error", "ApplicationNotResponding",
                            attrs = mapOf(
                                "error.stack" to Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "at $it" },
                                "crash.class" to "anr",
                                "crash.handled" to false,
                                "app.in_foreground" to true,
                            ),
                        ),
                    )
                } else if (!hung) reported = false
            }
        }, "wt-anr").apply { isDaemon = true }.start()
    }

    private fun appStartAndFrames(app: Application) {
        var launched = false
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val slow = AtomicLong()
            private val frozen = AtomicLong()
            override fun onActivityResumed(a: Activity) {
                if (!launched) {
                    launched = true
                    val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                    Watchtower.record(Event("vital", "app_launch_cold", ms.toDouble(), mapOf("screen" to a.javaClass.simpleName)))
                }
                slow.set(0); frozen.set(0)
                a.window.addOnFrameMetricsAvailableListener(
                    { _, m, _ ->
                        val d = m.getMetric(FrameMetrics.TOTAL_DURATION)
                        if (d > FROZEN_NS) frozen.incrementAndGet() else if (d > SLOW_NS) slow.incrementAndGet()
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            override fun onActivityPaused(a: Activity) {
                val s = a.javaClass.simpleName
                if (slow.get() > 0) Watchtower.record(Event("vital", "slow_frames", slow.get().toDouble(), mapOf("screen" to s)))
                if (frozen.get() > 0) Watchtower.record(Event("vital", "frozen_frames", frozen.get().toDouble(), mapOf("screen" to s)))
                Watchtower.flush()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
