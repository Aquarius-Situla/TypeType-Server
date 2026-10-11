package dev.typetype.server

import dev.typetype.server.db.tables.UsersTable
import dev.typetype.server.models.AdminSettingsItem
import dev.typetype.server.services.FavoritesService
import dev.typetype.server.services.HistoryService
import dev.typetype.server.services.RecommenderHistoryFingerprint
import dev.typetype.server.services.SubscriptionsService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderDataRoutesTest : RecommenderRoutesFixture() {
    @Test
    fun subscriptions_algorithm_does_not_upload_watch_history_or_favorites() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        HistoryService().addImported(TEST_USER_ID, watch("private", 2_000))
        FavoritesService().add(TEST_USER_ID, "https://www.youtube.com/watch?v=private-favorite")
        save("""{"enabled":true,"algorithmId":"subscriptions"}""")

        call("/recommendations/home?service=0")

        assertEquals(0, recommender.calls("/v1/history"))
        assertTrue(!recommender.lastBody("/v1/plan").contains("history_key"))
        assertTrue(!recommender.lastBody("/v1/plan").contains("private"))
    }

    @Test
    fun history_fingerprint_ignores_history_when_only_favorites_are_included() = withApp {
        FavoritesService().add(TEST_USER_ID, "https://www.youtube.com/watch?v=kept-favorite")
        val before = RecommenderHistoryFingerprint.of(TEST_USER_ID, includeHistory = false, includeFavorites = true)
        HistoryService().addImported(TEST_USER_ID, watch("fingerprint1", 2_000))
        val after = RecommenderHistoryFingerprint.of(TEST_USER_ID, includeHistory = false, includeFavorites = true)
        assertEquals(before, after)
    }

    @Test
    fun `the person chooses what the algorithm may read of their data`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        val history = HistoryService()
        val kept = history.addImported(TEST_USER_ID, watch("keep", 2_000))
        val left = history.addImported(TEST_USER_ID, watch("leave", 3_000))
        save("""{"enabled":true,"algorithmId":"signals","excludedItems":["history:${left.id}"]}""")
        call("/recommendations/home?service=0")
        val sent = recommender.lastBody("/v1/history")
        assertTrue(sent.contains("history:${kept.id}"))
        assertTrue(!sent.contains("history:${left.id}"))
        val data = Json.parseToJsonElement(call("/recommendations/data").bodyAsText()).jsonArray
        val watches = data.first { it.jsonObject.text("id") == "history" }.jsonObject
        assertEquals("2", watches["count"].toString())
        assertEquals("true", watches["collect"].toString())
        val items = checkNotNull(watches["items"]).jsonArray.map { it.jsonObject }
        assertEquals(listOf("true", "false"), items.map { it["excluded"].toString() })
        save("""{"enabled":true,"algorithmId":"signals","unusedData":["history"]}""")
        call("/recommendations/home?service=0")
        assertTrue(!recommender.lastBody("/v1/history").contains("history:${kept.id}"))
    }

    @Test
    fun `a video watched several times is listed once and excluded as a whole`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        val history = HistoryService()
        val first = history.addImported(TEST_USER_ID, watch("twice0", 2_000))
        val second = history.addImported(TEST_USER_ID, watch("twice0", 3_000))
        save("""{"enabled":true,"algorithmId":"signals"}""")
        suspend fun listed() = Json.parseToJsonElement(call("/recommendations/data").bodyAsText()).jsonArray
            .first { it.jsonObject.text("id") == "history" }.jsonObject.getValue("items").jsonArray.single().jsonObject
        val item = listed()
        assertEquals("video:https://www.youtube.com/watch?v=twice0", item.text("id"))
        assertEquals("2", item["viewings"].toString())
        assertEquals("https://i.ytimg.com/vi/twice0/hqdefault.jpg", item.text("thumbnailUrl"))
        save("""{"enabled":true,"algorithmId":"signals","excludedItems":["${item.text("id")}"]}""")
        assertEquals("true", listed()["excluded"].toString())
        call("/recommendations/home?service=0")
        val sent = recommender.lastBody("/v1/history")
        assertTrue(!sent.contains("history:${first.id}") && !sent.contains("history:${second.id}"))
    }

    @Test
    fun `a subject the person adds is kept tidy and sent to the recommender`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        val long = "x".repeat(80)
        save("""{"enabled":true,"algorithmId":"signals","customInterests":["  astronomie   amateur ","Astronomie amateur","","$long"]}""")
        val kept = call("/recommendations/settings").json().getValue("customInterests").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("astronomie amateur", "x".repeat(60)), kept)
        call("/recommendations/home?service=0")
        assertTrue(recommender.lastBody("/v1/plan").contains(""""custom_interests":["astronomie amateur""""))
    }

    @Test
    fun `an interest is shown with the person's own videos behind it`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        val watched = HistoryService().addImported(TEST_USER_ID, watch("seen00", 2_000))
        recommender.answer("/v1/profile", """{"groups":[{"id":"g1","label":"A","from":["history:${watched.id}","favorite:https://youtu.be/liked1"]}],"channels":[]}""")
        save("""{"enabled":true,"algorithmId":"signals"}""")
        val group = call("/recommendations/profile").json().getValue("groups").jsonArray.single().jsonObject
        val examples = group.getValue("examples").jsonArray.map { it.jsonObject }
        assertEquals("Watched seen00", examples[0].text("title"))
        assertEquals("https://i.ytimg.com/vi/seen00/hqdefault.jpg", examples[0].text("thumbnailUrl"))
        assertEquals("https://i.ytimg.com/vi/liked1/mqdefault.jpg", examples[1].text("thumbnailUrl"))
    }

    @Test
    fun `a channel is shown with its avatar, followed or only watched`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        SubscriptionsService().add(TEST_USER_ID, dev.typetype.server.models.SubscriptionItem("https://www.youtube.com/channel/UCfollowed", "Followed", "https://img/followed"))
        val watched = HistoryService().addImported(TEST_USER_ID, watch("seen01", 2_000))
        HistoryService().addImported(TEST_USER_ID, watch("seen02", 3_000).copy(channelAvatar = "https://img/watched"))
        recommender.answer("/v1/profile", """{"groups":[],"channels":[{"id":"channel:UCfollowed","name":"Followed","from":[]},{"id":"channel:UCwatched","name":"Watched","from":["history:${watched.id}"]},{"id":"channel:UCnone","name":"None","from":[]}]}""")
        save("""{"enabled":true,"algorithmId":"signals"}""")
        val channels = call("/recommendations/profile").json().getValue("channels").jsonArray.map { it.jsonObject }
        assertEquals("https://img/followed", channels[0].text("avatarUrl"))
        assertEquals("https://img/watched", channels[1].text("avatarUrl"))
        assertTrue("avatarUrl" !in channels[2])
    }

    private fun watch(id: String, at: Long) = dev.typetype.server.models.HistoryItem(
        url = "https://www.youtube.com/watch?v=$id",
        title = "Watched $id",
        thumbnail = "",
        channelName = "Channel",
        channelUrl = "https://www.youtube.com/channel/c",
        duration = 0,
        progress = 0,
        watchedAt = at,
    )

    @Test
    fun `the administrator can make it everyone's home until they turn it off`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true, recommenderDefaultOn = true))
        assertEquals("signals", call("/recommendations/home?service=0").json().text("algorithm"))
        save("""{"enabled":false}""")
        assertNull(call("/recommendations/home?service=0").json().text("algorithm"))
    }

    @Test
    fun `a long history goes to the recommender in parts`() = withApp(chunk = "2") {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true, recommenderDefaultOn = true))
        val history = HistoryService()
        repeat(5) { history.addImported(TEST_USER_ID, watch("w$it", 1_000L + it)) }
        assertEquals("signals", call("/recommendations/home?service=0").json().text("algorithm"))
        assertEquals(3, recommender.calls("/v1/history"))
        assertTrue(recommender.lastBody("/v1/history").contains("\"part\":2,\"last\":true"))
    }

    private fun makeAdmin() = transaction {
        UsersTable.insert {
            it[id] = TEST_USER_ID
            it[email] = "admin@test.local"
            it[passwordHash] = "x"
            it[name] = "Admin"
            it[role] = "admin"
            it[createdAt] = 0L
            it[updatedAt] = 0L
        }
    }

    @Test
    fun `the admin console is refused to others and tells an absent service apart`() = withApp(installed = false) {
        assertEquals(HttpStatusCode.Forbidden, call("/admin/recommender/status").status)
        makeAdmin()
        val status = call("/admin/recommender/status").json()
        assertEquals("false", status["installed"].toString())
        assertEquals("false", status["reachable"].toString())
    }

    @Test
    fun `the admin console shows the service state and what users said`() = withApp {
        makeAdmin()
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        save("""{"enabled":true,"algorithmId":"signals"}""")
        opinion("""{"kind":"page","verdict":"bad","comment":"too many repeats"}""")
        call("/recommendations/home?service=0")
        val status = call("/admin/recommender/status").json()
        assertEquals("true", status["reachable"].toString())
        assertEquals(2, status["algorithms"]?.jsonArray?.size)
        assertEquals("1", status["pagesBuilt"].toString())
        assertEquals("15", status["streamRequestsPerMinuteLimit"].toString())
        val feedback = call("/admin/recommender/feedback").json()
        assertEquals("1", feedback["usersEnabled"].toString())
        assertEquals("1", feedback["usersByAlgorithm"]?.jsonObject?.get("signals").toString())
        val verdict = checkNotNull(feedback["verdicts"]).jsonArray.single().jsonObject
        assertEquals("1", verdict["bad"].toString())
        assertEquals("too many repeats", feedback["comments"]?.jsonArray?.single()?.jsonObject?.text("comment"))
    }
}
