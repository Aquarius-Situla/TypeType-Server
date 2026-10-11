package dev.typetype.server.portability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YoutubeTakeoutSubscriptionImportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `listed subscriptions are counted once and keep the date of their activity`() {
        val subscriptions = decode(
            "abonnements/abonnements.csv" to CSV_HEADER +
                "$LISTED,http://www.youtube.com/channel/$LISTED,Chaîne suivie\n" +
                "$UNDATED,http://www.youtube.com/channel/$UNDATED,Chaîne sans date\n",
            "Mon activité/YouTube/MonActivité.html" to activity(
                subscribed(LISTED, "3 mars 2024, 10:00:00 CET"),
                subscribed(LISTED, "5 sept. 2026, 14:34:55 CEST"),
                subscribed(LEFT, "1 janv. 2020, 09:00:00 CET"),
            ),
        )

        assertEquals(
            listOf(
                "http://www.youtube.com/channel/$LISTED" to Instant.parse("2026-09-05T12:34:55Z").toEpochMilli(),
                "http://www.youtube.com/channel/$UNDATED" to 0L,
            ),
            subscriptions.map { it.channelUrl to it.subscribedAt }.sortedBy { it.first },
        )
    }

    @Test
    fun `json activity dates the listed subscriptions too`() {
        val subscriptions = decode(
            "abonnements/abonnements.csv" to CSV_HEADER + "$LISTED,http://www.youtube.com/channel/$LISTED,Chaîne suivie\n",
            "Mon activité/YouTube/MonActivité.json" to
                """[{"header":"YouTube","title":"Vous vous êtes abonné à Chaîne suivie","titleUrl":"https://www.youtube.com/channel/$LISTED","time":"2026-09-05T12:34:55Z"}]""",
        )

        assertEquals(listOf(Instant.parse("2026-09-05T12:34:55Z").toEpochMilli()), subscriptions.map { it.subscribedAt })
    }

    @Test
    fun `without a subscription list the activity subscriptions are imported with their date`() {
        val subscriptions = decode(
            "Mon activité/YouTube/MonActivité.html" to activity(subscribed(LEFT, "1 janv. 2020, 09:00:00 CET")),
        )

        assertEquals(
            listOf("https://www.youtube.com/channel/$LEFT" to Instant.parse("2020-01-01T08:00:00Z").toEpochMilli()),
            subscriptions.map { it.channelUrl to it.subscribedAt },
        )
    }

    private fun decode(vararg files: Pair<String, String>): List<PortabilitySubscription> {
        val archive = directory.resolve("takeout-subscriptions.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            files.forEach { (name, value) ->
                val path = if (name.startsWith("Mon activité")) "Takeout/$name" else "Takeout/YouTube et YouTube Music/$name"
                output.putNextEntry(ZipEntry(path))
                output.write(value.toByteArray())
                output.closeEntry()
            }
        }
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(archive, archive.fileName.toString(), "application/zip"),
                spool,
            )
            val subscriptions = mutableListOf<PortabilitySubscription>()
            spool.forEach(PortabilityCategory.SUBSCRIPTIONS) { subscriptions += it as PortabilitySubscription }
            assertEquals(subscriptions.size.toLong(), spool.counts()[PortabilityCategory.SUBSCRIPTIONS] ?: 0L)
            return subscriptions
        } finally {
            spool.delete()
        }
    }

    private fun activity(vararg cells: String) = "<html><body><div class=\"mdl-grid\">${cells.joinToString("")}</div></body></html>"

    private fun subscribed(channel: String, date: String) =
        "<div class=\"outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp\"><div class=\"mdl-grid\">" +
            "<div class=\"header-cell mdl-cell mdl-cell--12-col\"><p class=\"mdl-typography--title\">YouTube<br></p></div>" +
            "<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1\">Vous vous êtes abonné à " +
            "<a href=\"https://www.youtube.com/channel/$channel\">Une chaîne</a><br>$date<br></div>" +
            "<div class=\"content-cell mdl-cell mdl-cell--12-col mdl-typography--caption\"><b>Produits :</b><br>&emsp;YouTube<br></div>" +
            "</div></div>"

    private companion object {
        const val CSV_HEADER = "ID des chaînes,URL des chaînes,Titre des chaînes\n"
        const val LISTED = "UClisted00000000000000001"
        const val UNDATED = "UCundated0000000000000001"
        const val LEFT = "UCleft0000000000000000001"
    }
}
