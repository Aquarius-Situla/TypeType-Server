package dev.typetype.server

import dev.typetype.server.models.AdminSettingsItem
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderRoutesTest : RecommenderRoutesFixture() {
    @Test
    fun `an instance without the service answers 404 and keeps its home page`() = withApp(installed = false) {
        assertEquals(HttpStatusCode.NotFound, client.get("/version/recommender").status)
        assertEquals(HttpStatusCode.NotFound, call("/recommendations/algorithms").status)
        assertEquals(HttpStatusCode.NotFound, call("/recommendations/settings").status)
        assertEquals(HttpStatusCode.NotFound, call("/recommendations/profile").status)
        val home = call("/recommendations/home?service=0").json()
        assertNull(home.text("algorithm"))
        assertEquals(JsonNull, home["rows"] ?: JsonNull)
        assertEquals(0, recommender.calls("/v1/plan"))
    }

    @Test
    fun `the version of the algorithms is the recommender's own answer`() = withApp {
        recommender.answer("/version", """{"service":"recommender","version":"0.0.1"}""")
        val version = client.get("/version/recommender")
        assertEquals(HttpStatusCode.OK, version.status)
        assertEquals("0.0.1", version.json().text("version"))
    }

    @Test
    fun `the administrator switch is needed too`() = withApp {
        assertEquals(HttpStatusCode.NotFound, call("/recommendations/algorithms").status)
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        assertEquals(HttpStatusCode.OK, call("/recommendations/algorithms").status)
    }

    @Test
    fun `algorithms come from the recommender, narrowed by the administrator`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        val all = call("/recommendations/algorithms").json()
        assertEquals(listOf("subscriptions", "signals"), all["algorithms"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertEquals("signals", all["default_algorithm"]?.jsonPrimitive?.content)
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true, recommenderAllowedAlgorithms = listOf("subscriptions")))
        val narrowed = call("/recommendations/algorithms").json()
        assertEquals(1, narrowed["algorithms"]?.jsonArray?.size)
        assertEquals("subscriptions", narrowed["default_algorithm"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a person keeps the current home until they turn the option on`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        assertNull(call("/recommendations/home?service=0").json().text("algorithm"))
        assertEquals(HttpStatusCode.OK, save("""{"enabled":true,"algorithmId":"signals"}""").status)
        val home = call("/recommendations/home?service=0").json()
        assertEquals("signals", home["algorithm"]?.jsonPrimitive?.content)
        assertEquals(listOf("t2", "t1"), home["items"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        val row = checkNotNull(home["rows"]).jsonArray.single().jsonObject
        assertEquals("topic", row["reason"]?.jsonPrimitive?.content)
        assertEquals(2, row["videoUrls"]?.jsonArray?.size)
        assertEquals(1, recommender.calls("/v1/history"))
    }

    @Test
    fun `settings are kept, and an unknown algorithm is refused`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        assertEquals(HttpStatusCode.BadRequest, save("""{"enabled":true,"algorithmId":"nope"}""").status)
        save("""{"enabled":true,"algorithmId":"subscriptions","parameters":{"page_size":30,"bad":"text"},"mutedGroups":["g","g"]}""")
        cache.values.clear()
        val kept = call("/recommendations/settings").json()
        assertEquals("subscriptions", kept["algorithmId"]?.jsonPrimitive?.content)
        assertEquals("30", kept["parameters"]?.jsonObject?.get("page_size").toString())
        assertNull(kept["parameters"]?.jsonObject?.get("bad"))
        assertEquals(1, kept["mutedGroups"]?.jsonArray?.size)
    }

    @Test
    fun `a recommender that fails leaves the current home page, never an error`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        save("""{"enabled":true,"algorithmId":"signals"}""")
        recommender.answer("/v1/plan", """{"detail":"boom"}""", status = 500)
        val response = call("/recommendations/home?service=0")
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.json().text("algorithm"))
    }

    @Test
    fun `the profile is the recommender's own answer`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        assertEquals(HttpStatusCode.OK, call("/recommendations/profile").status)
        assertEquals(1, recommender.calls("/v1/profile"))
    }

    @Test
    fun `an opinion is kept with the algorithm and settings of the moment`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        save("""{"enabled":true,"algorithmId":"subscriptions","parameters":{"page_size":30}}""")
        val saved = opinion("""{"kind":"page","verdict":"good","comment":" nice ","algorithmId":"forged"}""")
        assertEquals(HttpStatusCode.Created, saved.status)
        val item = saved.json()
        assertEquals("subscriptions", item.text("algorithmId"))
        assertEquals("nice", item.text("comment"))
        assertEquals("30", item["parameters"]?.jsonObject?.get("page_size").toString())
        assertEquals("repetitive", opinion("""{"kind":"page","verdict":"bad","reason":"repetitive"}""").json().text("reason"))
        assertNull(opinion("""{"kind":"page","verdict":"good","reason":"repetitive"}""").json().text("reason"))
        assertEquals(HttpStatusCode.BadRequest, opinion("""{"kind":"page","verdict":"meh"}""").status)
        assertEquals(HttpStatusCode.BadRequest, opinion("""{"kind":"video","verdict":"not_for_me"}""").status)
    }

    @Test
    fun `a video said not for me is left out of the next page built`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        save("""{"enabled":true,"algorithmId":"signals"}""")
        val url = "https://www.youtube.com/watch?v=t2"
        opinion("""{"kind":"video","verdict":"not_for_me","videoUrl":"$url"}""")
        call("/recommendations/home?service=0")
        assertTrue(recommender.lastBody("/v1/plan").contains("not-interested:$url"))
    }

    @Test
    fun `not for me takes one video off the kept page without building it again`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        save("""{"enabled":true,"algorithmId":"signals"}""")
        suspend fun shown() = call("/recommendations/home?service=0").json()["items"]?.jsonArray
            ?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
        assertEquals(listOf("t2", "t1"), shown())
        val url = "https://www.youtube.com/watch?v=t2"
        val first = opinion("""{"kind":"video","verdict":"not_for_me","videoUrl":"$url","title":" A title ","channelName":"A channel"}""").json()
        assertEquals("A title", first.text("title"))
        assertEquals(listOf("t1"), shown())
        val second = opinion("""{"kind":"video","verdict":"not_for_me","videoUrl":"$url","reason":"seen"}""").json()
        assertEquals(first.text("id"), second.text("id"))
        assertEquals("seen", second.text("reason"))
        assertNull(opinion("""{"kind":"video","verdict":"not_for_me","videoUrl":"https://www.youtube.com/watch?v=t9","reason":"forged"}""").json().text("reason"))
        val listed = Json.parseToJsonElement(call("/recommendations/feedback").bodyAsText()).jsonArray
        assertEquals(2, listed.size)
        val removed = client.delete("/recommendations/feedback/${first.text("id")}") { header(HttpHeaders.Authorization, "Bearer test-jwt") }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(listOf("t2", "t1"), shown())
    }

    @Test
    fun `the last home page says how it was built`() = withApp {
        adminSettings.upsert(AdminSettingsItem(recommenderEnabled = true))
        assertEquals(HttpStatusCode.NotFound, call("/recommendations/last-home").status)
        save("""{"enabled":true,"algorithmId":"signals"}""")
        call("/recommendations/home?service=0")
        val trace = call("/recommendations/last-home").json()
        assertEquals("3", trace["candidatesReceived"].toString())
        assertEquals("1", trace["dropped"]?.jsonObject?.get("seen").toString())
        assertEquals("because", trace["rows"]?.jsonArray?.single()?.jsonObject?.text("why"))
        assertEquals("Video t2", trace["scores"]?.jsonArray?.single()?.jsonObject?.text("title"))
    }
}
