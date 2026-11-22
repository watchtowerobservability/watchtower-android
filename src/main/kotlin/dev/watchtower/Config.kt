package dev.watchtower

/** Placeholder edge URL; every real deployment sets [Config.endpoint]. */
const val DEFAULT_ENDPOINT = "https://edge.watchtower.local"
const val SDK_NAME = "watchtower.android"
const val SDK_VERSION = "0.1.2"

/** What a replay frame holds: a masked screenshot, or the view hierarchy (boxes, colours, masked text, image placeholders; no pixels). */
enum class ReplayMode { SCREENSHOT, WIREFRAME }

/** Replay options. Text and inputs are masked unless the app opts out per view or turns masking off here. */
data class ReplayConfig(
    val enabled: Boolean = false,
    val mode: ReplayMode = ReplayMode.SCREENSHOT,
    /** Mask every text view and input (default). Setting false still masks password and tagged views. */
    val maskText: Boolean = true,
    val intervalMs: Long = 1000,
    /** Longest screenshot side in pixels; larger screens are scaled down. */
    val maxSide: Int = 480,
    val jpegQuality: Int = 30,
)

data class Config(
    /** Publishable ingest key (sent as a bearer token). */
    val key: String,
    val service: String,
    val endpoint: String = DEFAULT_ENDPOINT,
    val release: String? = null,
    val environment: String? = null,
    val replay: ReplayConfig = ReplayConfig(),
    /** Events held on disk while offline; the oldest are dropped past this size. */
    val offlineMaxBytes: Long = 5L shl 20,
    val anrThresholdMs: Long = 5000,
    /** [Watchtower.isCrashLooping] is true from this many consecutive launch crashes. */
    val crashLoopThreshold: Int = 2,
    /** One RUM `view` event per resumed activity (screen); false turns automatic views off. */
    val trackViews: Boolean = true,
    /**
     * URL prefixes that get a W3C `traceparent` header from [WatchtowerInterceptor] (like the
     * browser SDK's `propagateTo`). Empty: no header is sent anywhere; the edge never gets one.
     */
    val propagateTo: List<String> = emptyList(),
    /** Sample the main thread every 10 ms until the first frame of a cold launch (`app_launch_profile`); opt-in. */
    val profileLaunch: Boolean = false,
    /**
     * Build or mapping id (for example the ProGuard/R8 mapping UUID the Gradle plugin uploads); added
     * to every error as `debug.build_id` so the worker picks the right symbol file.
     */
    val buildId: String? = null,
    /** Last chance to edit or drop (return null) an event before it is buffered. */
    val redact: Redactor = Redactor { it },
)
