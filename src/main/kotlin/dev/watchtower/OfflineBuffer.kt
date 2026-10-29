package dev.watchtower

import java.io.File

/**
 * Disk queue of serialized events, one file per event, oldest first. When the total size passes
 * [maxBytes] the oldest files are deleted, so an offline device never fills its storage.
 */
class OfflineBuffer(private val dir: File, private val maxBytes: Long) {
    private var seq = 0L

    init { dir.mkdirs() }

    @Synchronized
    fun add(json: String) {
        File(dir, "%020d-%06d.json".format(System.currentTimeMillis(), seq++)).writeText(json)
        trim()
    }

    @Synchronized
    fun peek(max: Int): List<Pair<File, String>> =
        files().take(max).mapNotNull { f -> runCatching { f to f.readText() }.getOrNull() }

    @Synchronized
    fun remove(files: List<File>) = files.forEach { it.delete() }

    @Synchronized
    fun size(): Int = files().size

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    private fun trim() {
        val all = files()
        var total = all.sumOf { it.length() }
        for (f in all) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
