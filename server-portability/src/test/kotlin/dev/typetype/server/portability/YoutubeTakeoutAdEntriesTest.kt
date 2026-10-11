package dev.typetype.server.portability

import dev.typetype.server.services.YoutubeTakeoutHistoryParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YoutubeTakeoutAdEntriesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `html history skips the ads Takeout lists among watched videos`() {
        val archive = directory.resolve("takeout-ads.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.entry(
                "Takeout/YouTube et YouTube Music/historique/watch-history.html",
                page(
                    cell("Vous avez regardé", "watched0001", "Une vidéo", channel = true, date = FRENCH_DATE),
                    cell("Vous avez regardé", "advert00001", "Offre étudiante", details = "Des annonces Google", date = FRENCH_DATE),
                    cell("Vous avez regardé", "watched0002", "Une autre vidéo", channel = true, date = FRENCH_DATE),
                ),
            )
            output.entry(
                "Takeout/My Activity/YouTube/MyActivity.html",
                page(
                    cell("You watched", "watched0003", "A video", channel = true, date = ENGLISH_DATE),
                    cell("You watched", "advert00002", "Shop now", details = "From Google Ads", date = ENGLISH_DATE),
                ),
            )
        }
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(archive, archive.fileName.toString(), "application/zip"),
                spool,
            )
            val watched = mutableListOf<String>()
            spool.forEach(PortabilityCategory.HISTORY) { watched += (it as PortabilityHistory).video.url.takeLast(11) }
            assertEquals(listOf("watched0001", "watched0002", "watched0003"), watched.sorted())
        } finally {
            spool.delete()
        }
    }

    @Test
    fun `json history skips ads marked in French details`() {
        val json = directory.resolve("watch-history.json")
        Files.writeString(
            json,
            """[
                {"header":"YouTube","title":"Vous avez regardé Une vidéo","titleUrl":"https://www.youtube.com/watch?v=watched0001","time":"2026-09-05T12:34:55Z"},
                {"header":"YouTube","title":"Vous avez regardé Offre étudiante","titleUrl":"https://www.youtube.com/watch?v=advert00001","time":"2026-09-05T12:34:55Z","details":[{"name":"Des annonces Google"}]}
            ]""".trimIndent(),
        )
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(json, "watch-history.json", "application/json"),
                spool,
            )
            assertEquals(1L, spool.counts()[PortabilityCategory.HISTORY])
        } finally {
            spool.delete()
        }
    }

    @Test
    fun `html history skips ads by their shape in a language the server has no words for`() {
        val archive = directory.resolve("takeout-ads-es.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.entry(
                "Takeout/YouTube y YouTube Music/historial/historial-de-reproducciones.html",
                page(
                    cell("Has visto", "watched0001", "Un vídeo", channel = true, date = SPANISH_DATE),
                    cell("Has visto", "advert00001", "Oferta", details = "De los anuncios de Google", date = SPANISH_DATE),
                    cell("Widziałeś", "advert00002", "Reklama", details = "Z reklam Google", date = SPANISH_DATE),
                    cell("Has visto", "nochannel01", "Un vídeo sin canal", date = SPANISH_DATE),
                    cell("Has visto", "watched0002", "Otro vídeo", channel = true, details = "YouTube Music", date = SPANISH_DATE),
                ),
            )
        }
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(archive, archive.fileName.toString(), "application/zip"),
                spool,
            )
            val watched = mutableListOf<String>()
            spool.forEach(PortabilityCategory.HISTORY) { watched += (it as PortabilityHistory).video.url.takeLast(11) }
            assertEquals(listOf("nochannel01", "watched0001", "watched0002"), watched.sorted())
        } finally {
            spool.delete()
        }
    }

    @Test
    fun `json history skips ads by their shape in any language`() {
        val json = directory.resolve("watch-history.json")
        Files.writeString(
            json,
            """[
                {"header":"YouTube","title":"Has visto Un vídeo","titleUrl":"https://www.youtube.com/watch?v=watched0001","time":"2026-09-05T12:34:55Z","subtitles":[{"name":"Un canal","url":"https://www.youtube.com/channel/UCchannel0000000000000001"}]},
                {"header":"YouTube","title":"Has visto Oferta","titleUrl":"https://www.youtube.com/watch?v=advert00001","time":"2026-09-05T12:34:55Z","details":[{"name":"De los anuncios de Google"}]}
            ]""".trimIndent(),
        )
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(json, "watch-history.json", "application/json"),
                spool,
            )
            assertEquals(1L, spool.counts()[PortabilityCategory.HISTORY])
        } finally {
            spool.delete()
        }
    }

    @Test
    fun `an html file larger than a window is decoded whole, ads left out`() {
        val cells = (1..12_000).map { index ->
            if (index % 4 == 0) {
                cell("Has visto", "ad%09d".format(index), "Anuncio $index", details = "De los anuncios de Google", date = SPANISH_DATE)
            } else {
                cell("Has visto", "w%010d".format(index), "Vídeo $index", channel = true, date = SPANISH_DATE)
            }
        }
        val html = page(*cells.toTypedArray())
        assert(html.length > 4 * 1024 * 1024) { "the file must span several windows" }
        val archive = directory.resolve("takeout-large.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.entry("Takeout/YouTube y YouTube Music/historial/historial-de-reproducciones.html", html)
        }
        val spool = PortabilitySpool.create(directory)
        try {
            YoutubeTakeoutPortabilityAdapter().decode(
                PortabilityInputFactory.create(archive, archive.fileName.toString(), "application/zip"),
                spool,
            )
            assertEquals(9_000L, spool.counts()[PortabilityCategory.HISTORY])
        } finally {
            spool.delete()
        }
    }

    @Test
    fun `large html files are read in windows that never cut an entry from its details`() {
        val cells = (1..400).map { index ->
            if (index % 3 == 0) {
                cell("Vous avez regardé", "advert%05d".format(index), "Annonce $index", details = "Des annonces Google", date = FRENCH_DATE)
            } else {
                cell("Vous avez regardé", "watch%06d".format(index), "Vidéo $index", channel = true, date = FRENCH_DATE)
            }
        }
        val html = page(*cells.toTypedArray())
        val watched = mutableSetOf<String>()
        YoutubeTakeoutHtmlPortabilityReader.readWindows(StringReader(html), windowChars = 5_000) { window ->
            YoutubeTakeoutHistoryParser.parse(window, requireWatchedMarker = false).forEach { watched += it.url.takeLast(11) }
        }
        assertEquals((1..400).filter { it % 3 != 0 }.map { "watch%06d".format(it) }.toSet(), watched)
    }

    private fun page(vararg cells: String): String =
        "<html><body><div class=\"mdl-grid\">${cells.joinToString("")}</div></body></html>"

    private fun cell(verb: String, video: String, title: String, channel: Boolean = false, details: String? = null, date: String): String {
        val channelLink = if (channel) "<a href=\"https://www.youtube.com/channel/UCchannel0000000000000001\">Une chaîne</a><br>" else ""
        val detailLines = details?.let { "<b>Informations détaillées :</b><br>&emsp;$it<br>" }.orEmpty()
        return "<div class=\"outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp\"><div class=\"mdl-grid\">" +
            "<div class=\"header-cell mdl-cell mdl-cell--12-col\"><p class=\"mdl-typography--title\">YouTube<br></p></div>" +
            "<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1\">$verb " +
            "<a href=\"https://www.youtube.com/watch?v=$video\">$title</a><br>$channelLink$date<br></div>" +
            "<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1 mdl-typography--text-right\"></div>" +
            "<div class=\"content-cell mdl-cell mdl-cell--12-col mdl-typography--caption\"><b>Produits :</b><br>&emsp;YouTube<br>" +
            detailLines +
            "<b>Que fait cette information ici ?</b><br>&emsp;Cette activité a été enregistrée sur votre compte Google." +
            "</div></div></div>"
    }

    private fun ZipOutputStream.entry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray())
        closeEntry()
    }

    private companion object {
        const val FRENCH_DATE = "5 sept. 2026, 14:34:55 CEST"
        const val ENGLISH_DATE = "5 Sep 2026, 14:34:55 CEST"
        const val SPANISH_DATE = "16 sept 2026, 18:02:08 CEST"
    }
}
