package dev.watchtower

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicLong

/**
 * Crash, ANR, app-start, frame metrics and screen views. Everything here only calls
 * [Watchtower.record], plus [Run.update] so the next launch knows how this run ended.
 */
internal object Instrument {
    private const val FROZEN_NS = 700_000_000L
    private const val SLOW_NS = 16_700_000L

    fun install(app: Application, cfg: Config) {
        crashHandler()
        anrWatchdog(cfg.anrThresholdMs)
        appStartAndFrames(app, cfg)
    }

    private fun crashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // written to disk before the process dies; sent on the next launch
            runCatching { Run.update { it.copy(crashed = true) } }
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
                            "error.threads" to runCatching { Threads.others(t) }.getOrDefault(""),
                        ) + CrashLoop.attrs(System.currentTimeMillis() - Watchtower.startedMs, Watchtower.launchCrashCount + 1),
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
                    Run.update { it.copy(hanging = true) }
                    Watchtower.record(
                        Event(
                            "error", "ApplicationNotResponding",
                            attrs = mapOf(
                                "error.stack" to Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "at $it" },
                                "crash.class" to "anr",
                                "error.threads" to runCatching { Threads.others(Looper.getMainLooper().thread) }.getOrDefault(""),
                                "crash.handled" to false,
                                "app.in_foreground" to true,
                            ),
                        ),
                    )
                } else if (!hung && reported) {
                    reported = false
                    Run.update { it.copy(hanging = false) }
                }
            }
        }, "wt-anr").apply { isDaemon = true }.start()
    }

    private fun appStartAndFrames(app: Application, cfg: Config) {
        val launch = LaunchTracker(Process.getStartElapsedRealtime(), SystemClock.elapsedRealtime())
        val main = Handler(Looper.getMainLooper())
        val tracker = ViewTracker(cfg.service)
        val sampler = if (cfg.profileLaunch) LaunchSampler().also { it.start() } else null
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val slow = AtomicLong()
            private val frozen = AtomicLong()
            private var started = 0
            override fun onActivityResumed(a: Activity) {
                launch.activityResumed(SystemClock.elapsedRealtime(), a.javaClass.simpleName)
                if (cfg.trackViews) tracker.appeared(a.javaClass.simpleName, System.currentTimeMillis())?.let(Watchtower::record)
                firstDraw(a) {
                    launch.firstFrame(SystemClock.elapsedRealtime())?.let { e ->
                        Watchtower.record(e)
                        if (e.name == "app_launch_cold") sampler?.stop()?.let(Watchtower::record)
                    }
                }
                slow.set(0); frozen.set(0)
                a.window.addOnFrameMetricsAvailableListener(
                    { _, m, _ ->
                        val d = m.getMetric(FrameMetrics.TOTAL_DURATION)
                        if (d > FROZEN_NS) frozen.incrementAndGet() else if (d > SLOW_NS) slow.incrementAndGet()
                    },
                    main,
                )
            }
            override fun onActivityPaused(a: Activity) {
                val s = a.javaClass.simpleName
                if (!a.isChangingConfigurations) tracker.disappeared(s, System.currentTimeMillis())
                if (slow.get() > 0) Watchtower.record(Event("vital", "slow_frames", slow.get().toDouble(), mapOf("screen" to s)))
                if (frozen.get() > 0) Watchtower.record(Event("vital", "frozen_frames", frozen.get().toDouble(), mapOf("screen" to s)))
                Watchtower.flush()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                launch.activityCreated(SystemClock.elapsedRealtime())
                if (cfg.trackViews) Fragments.install(a, tracker)
            }
            override fun onActivityStarted(a: Activity) {
                launch.activityStarted(SystemClock.elapsedRealtime())
                if (started++ == 0) Run.update { it.copy(foreground = true) }
            }
            override fun onActivityStopped(a: Activity) {
                launch.activityStopped(a.isChangingConfigurations)
                if (started > 0 && --started == 0 && !a.isChangingConfigurations) Run.update { it.copy(foreground = false) }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Runs [done] once, right after the next frame of the activity's window is drawn. */
    private fun firstDraw(a: Activity, done: () -> Unit) {
        val view = a.window.decorView
        val main = Handler(Looper.getMainLooper())
        val listener = object : ViewTreeObserver.OnDrawListener {
            var fired = false
            override fun onDraw() {
                if (fired) return
                fired = true
                // a draw listener cannot remove itself during onDraw
                main.postAtFrontOfQueue {
                    done()
                    view.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }
}
