package dev.watchtower

/** Placeholder edge URL; every real deployment sets [Config.endpoint]. */
const val DEFAULT_ENDPOINT = "https://edge.watchtower.local"
const val SDK_NAME = "watchtower.android"
const val SDK_VERSION = "0.1.0"

/** Replay options. Text and inputs are masked unless the app opts out per view or turns masking off here. */
data class ReplayConfig(
    val enabled: Boolean = false,
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
    /** Last chance to edit or drop (return null) an event before it is buffered. */
    val redact: Redactor = Redactor { it },
)
