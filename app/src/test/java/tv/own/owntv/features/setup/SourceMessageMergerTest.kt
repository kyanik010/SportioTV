package tv.own.owntv.features.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceMessageConfidence
import tv.own.owntv.core.model.SourceType

/**
 * How the local reading and the AI's reading become one result.
 *
 * The cases that matter are the ones where the two disagree: the AI's URL is not one of the
 * message's own, the user picks a different URL than the AI read, or the message carries a MAC.
 */
class SourceMessageMergerTest {

    private val twoUrls = """
        http://a.example:8080/get.php?username=aa&password=aa1
        http://b.example:8080/get.php?username=bb&password=bb1
    """.trimIndent()

    private fun local(message: String = twoUrls): SourceMessageDraft =
        SourceMessageParser.parse(message)

    @Test
    fun withoutTheAiTheLocalReadingIsTheResult() {
        val result = SourceMessageMerger.merge(twoUrls, local(), reading = null)

        assertEquals(SourceType.XTREAM, result.type)
        assertEquals("http://a.example:8080", result.server)
        assertEquals("aa", result.username)
        assertEquals("aa1", result.password)
        assertEquals(listOf("http://a.example:8080/get.php?username=aa&password=aa1", "http://b.example:8080/get.php?username=bb&password=bb1"), result.urls)
        assertEquals(SourceMessageConfidence.HIGH, result.confidence)
        assertFalse(result.fromAi)
    }

    @Test
    fun theAiPicksTheCandidateItsServerMatches() {
        val reading = SourceMessageDraft(
            type = SourceType.XTREAM,
            server = "http://b.example:8080",
            username = "bb",
            password = "bb1",
        )

        val result = SourceMessageMerger.merge(twoUrls, local(), reading)

        assertEquals("http://b.example:8080", result.server)
        assertEquals("bb", result.username)
        assertEquals("bb1", result.password)
        assertEquals("http://b.example:8080/get.php?username=bb&password=bb1", result.url)
        assertEquals(SourceMessageConfidence.HIGH, result.confidence)
        assertTrue(result.fromAi)
    }

    @Test
    fun anAiServerThatIsNoCandidateIsOnlyAGuess() {
        val reading = SourceMessageDraft(
            type = SourceType.XTREAM,
            server = "http://c.example:8080",
            username = "cc",
            password = "cc1",
        )

        val result = SourceMessageMerger.merge(twoUrls, local(), reading)

        assertEquals("http://c.example:8080", result.server)
        assertEquals(SourceMessageConfidence.GUESS, result.confidence)
        // The AI's own server: its credentials belong to it, so they are the ones on show.
        assertEquals("cc", result.username)
        assertEquals("cc1", result.password)
    }

    @Test
    fun theUsersPickWinsOverTheAisChoice() {
        val reading = SourceMessageDraft(
            type = SourceType.XTREAM,
            server = "http://b.example:8080",
            username = "bb",
            password = "bb1",
        )

        val result = SourceMessageMerger.merge(
            twoUrls,
            local(),
            reading,
            preferredUrl = "http://a.example:8080/get.php?username=aa&password=aa1",
        )

        assertEquals("http://a.example:8080", result.server)
        assertEquals("aa", result.username)
        assertEquals("aa1", result.password)
    }

    @Test
    fun theAisCredentialsNeverLeakIntoAnotherUrl() {
        val reading = SourceMessageDraft(
            type = SourceType.XTREAM,
            server = "http://b.example:8080",
            username = "bb",
            password = "bb1",
        )

        val result = SourceMessageMerger.merge(
            twoUrls,
            local(),
            reading,
            preferredUrl = "http://a.example:8080/get.php?username=aa&password=aa1",
        )

        assertFalse(result.username == "bb")
        assertFalse(result.password == "bb1")
    }

    @Test
    fun aMacInTheMessageMakesItAStalkerSource() {
        val message = """
            Portal: http://portal.example:8080/c/
            MAC: 00-1a-79-aa-bb-cc
        """.trimIndent()

        val result = SourceMessageMerger.merge(message, local(message), reading = null)

        assertEquals(SourceType.STALKER, result.type)
        assertEquals("http://portal.example:8080/c/", result.server)
        assertEquals("00:1A:79:AA:BB:CC", result.mac)
    }

    @Test
    fun theAiMayCallAPlaylistAPlaylist() {
        val message = "http://provider.example/list?id=42"
        val reading = SourceMessageDraft(
            type = SourceType.M3U,
            server = "http://provider.example/list?id=42",
        )

        val result = SourceMessageMerger.merge(message, local(message), reading)

        assertEquals(SourceType.M3U, result.type)
        assertEquals("http://provider.example/list?id=42", result.server)
    }

    @Test
    fun aPanelEndpointStaysAnXtreamSourceEvenIfTheAiCallsItAPlaylist() {
        val message = "http://example.com:8080/get.php?username=u1&password=p1"
        val reading = SourceMessageDraft(
            type = SourceType.M3U,
            server = "http://example.com:8080/get.php?username=u1&password=p1",
        )

        val result = SourceMessageMerger.merge(message, local(message), reading)

        assertEquals(SourceType.XTREAM, result.type)
        assertEquals("http://example.com:8080", result.server)
        assertEquals("u1", result.username)
    }

    @Test
    fun aProseMessageWithoutAUrlKeepsTheLabelledServer() {        val message = """
            Server: http://example.com:8080
            Benutzer: nutzer
            Passwort: geheim
        """.trimIndent()

        val result = SourceMessageMerger.merge(message, local(message), reading = null)

        assertEquals("http://example.com:8080", result.server)
        assertEquals("nutzer", result.username)
        assertEquals("geheim", result.password)
    }
}
