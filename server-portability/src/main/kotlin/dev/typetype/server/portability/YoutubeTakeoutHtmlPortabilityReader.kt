package dev.typetype.server.portability

import dev.typetype.server.services.YoutubeTakeoutActivitySignalService
import dev.typetype.server.services.YoutubeTakeoutAdMarker
import dev.typetype.server.services.YoutubeTakeoutHistoryParser
import dev.typetype.server.services.YoutubeTakeoutPathHints
import java.io.Reader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object YoutubeTakeoutHtmlPortabilityReader {
    fun read(zip: ZipFile, entries: List<ZipEntry>, sink: PortabilityRecordSink, subscriptionsListed: Boolean = false) {
        entries.asSequence().filter(::isYoutubeHtml).forEach { entry ->
            zip.getInputStream(entry).bufferedReader().use { reader ->
                val historyEntry = isHistoryEntry(entry)
                val write = { html: String ->
                    writeWindow(html, sink, includeActivitySignals = !historyEntry, historyEntry, subscriptionsListed)
                }
                // Always in windows: a whole 110 MB activity file (a Spanish Takeout) held as one
                // string, then copied while parsing, ran a 512 MB heap out of memory.
                readWindows(reader, block = write)
            }
        }
    }

    private fun writeWindow(
        html: String,
        sink: PortabilityRecordSink,
        includeActivitySignals: Boolean,
        historyEntry: Boolean,
        subscriptionsListed: Boolean,
    ) {
        val parsedHistory = YoutubeTakeoutHistoryParser.parseWithDiagnostics(html, requireWatchedMarker = !historyEntry)
        parsedHistory.items.forEach { sink.write(it.toPortability()) }
        if (parsedHistory.invalidDates > 0) {
            sink.issue(
                PortabilityIssue(
                    PortabilityCategory.HISTORY,
                    "invalid_takeout_date",
                    "YouTube Takeout HTML rows with an invalid date were skipped",
                    parsedHistory.invalidDates,
                ),
            )
        }
        if (includeActivitySignals) {
            val (subscriptions, favorites) = YoutubeTakeoutActivitySignalService.parseHtml(html)
            subscriptions.forEach {
                if (subscriptionsListed) YoutubeTakeoutSubscriptionDates.remember(sink, it.channelUrl, it.subscribedAt)
                else sink.write(it.toPortability())
            }
            favorites.forEach { sink.write(it.toPortability()) }
        }
    }

    internal fun readWindows(reader: Reader, windowChars: Int = WINDOW_CHARS, block: (String) -> Unit) {
        val buffer = CharArray(READ_CHARS)
        val window = StringBuilder(windowChars + READ_CHARS)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            window.append(buffer, 0, read)
            if (window.length >= windowChars) {
                // Cut before the last entry, so an entry always keeps its details (ads are marked there).
                val entryStart = window.lastIndexOf(YoutubeTakeoutAdMarker.HTML_CELL_START)
                if (entryStart > 0) {
                    block(window.substring(0, entryStart))
                    window.delete(0, entryStart)
                } else {
                    block(window.toString())
                    window.delete(0, maxOf(0, window.length - OVERLAP_CHARS))
                }
            }
        }
        if (window.isNotEmpty()) block(window.toString())
    }

    private fun isYoutubeHtml(entry: ZipEntry): Boolean = YoutubeTakeoutPathHints.isYoutubeHtml(entry.name)

    private fun isHistoryEntry(entry: ZipEntry): Boolean = YoutubeTakeoutPathHints.isHistoryEntry(entry.name)

    private const val READ_CHARS = 32 * 1024
    private const val WINDOW_CHARS = 4 * 1024 * 1024
    private const val OVERLAP_CHARS = 256 * 1024
}
