package tv.own.owntv.features.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceType

/**
 * The AI's answer to a pasted provider message: JSON in, the fields out.
 *
 * The point of most of these cases is what the parser *drops*: an invented host, an invented MAC
 * and a null are all things a model produces, and none of them may reach the add-source form.
 */
class SourceMessageAiParserTest {

    private val xtreamMessage = """
        Hier sind deine Daten:
        http://example.com:8080/get.php?username=user1&password=pass1
        Viel Spaß!
    """.trimIndent()

    @Test
    fun theDocumentedShapeIsRead() {
        val content = """
            {"type":"XTREAM","server":"http://example.com:8080","username":"u1","password":"p1",
             "mac":"","urls":["http://example.com:8080"]}
        """.trimIndent()

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals(SourceType.XTREAM, draft.type)
        assertEquals("http://example.com:8080", draft.server)
        assertEquals("u1", draft.username)
        assertEquals("p1", draft.password)
        assertEquals("", draft.mac)
        assertEquals(listOf("http://example.com:8080"), draft.urls)
    }

    @Test
    fun aFencedAnswerIsUnwrapped() {
        val content = """
            ```json
            {"type":"XTREAM","server":"http://example.com:8080","username":"u1","password":"p1"}
            ```
        """.trimIndent()

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("u1", draft.username)
    }

    @Test
    fun theServerIsTheXtreamBaseEvenWhenTheModelGivesTheEndpoint() {
        val content = """{"server":"http://example.com:8080/get.php?username=u1&password=p1"}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("http://example.com:8080", draft.server)
    }

    @Test
    fun credentialsLeftInTheQueryAreStillCredentials() {
        val content = """{"server":"http://example.com:8080/get.php?username=u1&password=p1"}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("u1", draft.username)
        assertEquals("p1", draft.password)
    }

    @Test
    fun anInventedHostIsDropped() {
        val content = """
            {"type":"XTREAM","server":"http://evil.example:8080","username":"u1","password":"p1",
             "urls":["http://evil.example:8080","http://example.com:8080"]}
        """.trimIndent()

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("", draft.server)
        assertEquals(listOf("http://example.com:8080"), draft.urls)
    }

    @Test
    fun anInventedMacIsDropped() {
        val content = """{"mac":"AA:BB:CC:DD:EE:FF","urls":["http://example.com:8080"]}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("", draft.mac)
    }

    @Test
    fun aMacTheMessageCarriesIsCanonicalised() {
        val message = "Portal: http://portal.example:8080/c/ MAC: 00-1a-79-aa-bb-cc"
        val content = """{"type":"STALKER","server":"http://portal.example:8080/c/","mac":"00:1a:79:aa:bb:cc"}"""

        val draft = SourceMessageAiParser.parse(content, message)!!

        assertEquals("00:1A:79:AA:BB:CC", draft.mac)
        assertEquals(SourceType.STALKER, draft.type)
        assertEquals("http://portal.example:8080/c/", draft.server)
    }

    @Test
    fun aPlaylistKeepsItsWholeUrlAndItsCredentialsStayInIt() {
        val message = "http://provider.example/list.m3u?username=u1&password=p1"
        val content = """{"type":"M3U","server":"http://provider.example/list.m3u?username=u1&password=p1"}"""

        val draft = SourceMessageAiParser.parse(content, message)!!

        assertEquals(SourceType.M3U, draft.type)
        assertEquals("http://provider.example/list.m3u?username=u1&password=p1", draft.server)
        // The M3U form has no credential fields — the query belongs to the URL.
        assertEquals("", draft.username)
        assertEquals("", draft.password)
    }

    @Test
    fun anUnknownTypeReadsAsXtream() {
        val content = """{"type":"MAGIC","server":"http://example.com:8080"}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals(SourceType.XTREAM, draft.type)
    }

    @Test
    fun aJsonNullIsNotTheStringNull() {
        val content = """{"type":"XTREAM","server":"http://example.com:8080","username":null,"password":null}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)!!

        assertEquals("", draft.username)
        assertEquals("", draft.password)
    }

    @Test
    fun anAnswerWithoutJsonIsNothing() {
        assertNull(SourceMessageAiParser.parse("I could not read that message.", xtreamMessage))
        assertNull(SourceMessageAiParser.parse("", xtreamMessage))
    }

    @Test
    fun anAnswerWithNothingUsableIsNothing() {
        assertNull(SourceMessageAiParser.parse("""{"type":"XTREAM","server":"","username":"","password":""}""", xtreamMessage))
    }

    @Test
    fun aServerWithoutASchemeIsDropped() {
        val content = """{"server":"example.com:8080","username":"u1"}"""

        val draft = SourceMessageAiParser.parse(content, xtreamMessage)

        assertTrue(draft == null || draft.server.isEmpty())
    }
}
