package tv.own.owntv.features.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceMessageConfidence
import tv.own.owntv.core.model.SourceType

/**
 * The local rules of the playlist import (P0). Pure text in, fields out: no network, no Android, no
 * database — the same shapes a shop or reseller actually sends.
 */
class SourceMessageParserTest {

    // ---- Xtream: credentials in the URL -------------------------------------

    @Test
    fun xtreamQueryCredentialsAreSplitOffTheUrl() {
        val message = "http://example.com:8080/get.php?username=user1&password=pass1&type=m3u_plus&output=ts"

        val draft = SourceMessageParser.parse(message)

        assertEquals(SourceType.XTREAM, draft.type)
        assertEquals("http://example.com:8080", draft.server)
        assertEquals("user1", draft.username)
        assertEquals("pass1", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
        assertEquals(listOf(message), draft.urls)
        assertEquals(message, draft.url)
    }

    @Test
    fun xtreamBaseKeepsANonEndpointPathPrefix() {
        val draft = SourceMessageParser.parse(
            "http://host:8080/iptv/player_api.php?username=u1x&password=p1x",
        )

        assertEquals("http://host:8080/iptv", draft.server)
        assertEquals("u1x", draft.username)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun shortQueryKeysAreAccepted() {
        val draft = SourceMessageParser.parse("http://example.com:8080/player_api.php?user=uux&pass=ppx")

        assertEquals("uux", draft.username)
        assertEquals("ppx", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun urlCredentialsOutrankLabelledOnes() {
        val message = """
            http://example.com:8080/get.php?username=aa&password=aa1
            User: bb
            Pass: bb1
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals("aa", draft.username)
        assertEquals("aa1", draft.password)
    }

    // ---- Labelled fields ----------------------------------------------------

    @Test
    fun germanLabelledMessageIsRead() {
        val message = """
            Server: http://example.com:8080
            Benutzer: nutzer
            Passwort: geh3im
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals(SourceType.XTREAM, draft.type)
        assertEquals("http://example.com:8080", draft.server)
        assertEquals("nutzer", draft.username)
        assertEquals("geh3im", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun englishLabelsInOneLineAreRead() {
        val draft = SourceMessageParser.parse("url=http://example.com:8080 user=u1 pass=p1")

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("u1", draft.username)
        assertEquals("p1", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun labelledOrderDoesNotMatter() {
        val message = """
            Passwort: p1x
            URL: http://example.com:8080
            Benutzername: u1x
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("u1x", draft.username)
        assertEquals("p1x", draft.password)
    }

    @Test
    fun aServerWithoutSchemeGetsHttpPrefix() {
        val message = """
            Server: example.com
            Benutzer: u1x
            Passwort: p1x
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals("http://example.com", draft.server)
        assertEquals("u1x", draft.username)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
        assertTrue(draft.hasAnything)
    }

    @Test
    fun aBareHostPortIsACandidate() {
        val draft = SourceMessageParser.parse(
            """
                Server: provider.example:8080
                User: u1x
                Pass: p1x
            """.trimIndent(),
        )

        assertEquals("http://provider.example:8080", draft.server)
        assertEquals("http://provider.example:8080", draft.url)
        assertEquals("u1x", draft.username)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun passwordKeepsSpecialCharacters() {
        val message = """
            Server: http://example.com:8080
            User: u1x
            Pass: p@ss.w0rd!
        """.trimIndent()

        assertEquals("p@ss.w0rd!", SourceMessageParser.parse(message).password)
    }

    @Test
    fun trailingPunctuationIsStrippedFromTheUrlValue() {
        val message = """
            Playlist: http://provider.example/list.m3u.
            User: u1x
            Pass: p1x
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals("http://provider.example/list.m3u", draft.server)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun labelsAreReadWithCrlfAndNonBreakingSpaces() {
        val message = "Server:\u00A0http://example.com:8080\r\nUser:\u00A0u1x\r\nPass:\u00A0p1x"

        val draft = SourceMessageParser.parse(message)

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("u1x", draft.username)
        assertEquals("p1x", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    // ---- Positional fallback ------------------------------------------------

    @Test
    fun threeLinesWithoutLabelsAreReadAsAGuess() {
        val draft = SourceMessageParser.parse("http://example.com:8080\nnutzer1\ngeheim1")

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("nutzer1", draft.username)
        assertEquals("geheim1", draft.password)
        assertEquals(SourceMessageConfidence.GUESS, draft.confidence)
    }

    @Test
    fun oneLineWithoutLabelsIsReadAsAGuess() {
        val draft = SourceMessageParser.parse("http://example.com:8080 u1 p1")

        assertEquals("u1", draft.username)
        assertEquals("p1", draft.password)
        assertEquals(SourceMessageConfidence.GUESS, draft.confidence)
    }

    @Test
    fun proseIsNeverGuessedAsCredentials() {
        val message = "http://example.com:8080\nBitte wenden Sie sich an den Support"

        val draft = SourceMessageParser.parse(message)

        assertEquals("http://example.com:8080", draft.server)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
        assertEquals(SourceMessageConfidence.GUESS, draft.confidence)
    }

    @Test
    fun twoTokensWithoutAnyServerAreNotGuessed() {
        val draft = SourceMessageParser.parse("nutzer1\ngeheim1")

        assertEquals("", draft.username)
        assertEquals(SourceMessageConfidence.NONE, draft.confidence)
        assertFalse(draft.hasAnything)
    }

    // ---- Several URLs -------------------------------------------------------

    @Test
    fun severalUrlsEachKeepTheirOwnCredentials() {
        val first = "http://a.example:8080/get.php?username=aa&password=aa1"
        val second = "http://b.example:8080/get.php?username=bb&password=bb1"
        val message = "Pick one:\n$first\n$second"

        val draft = SourceMessageParser.parse(message)

        assertEquals(listOf(first, second), draft.urls)
        assertEquals(first, draft.url)
        assertEquals("http://a.example:8080", draft.server)
        assertEquals("aa", draft.username)

        val other = SourceMessageParser.parse(message, second)

        assertEquals(second, other.url)
        assertEquals("http://b.example:8080", other.server)
        assertEquals("bb", other.username)
        assertEquals("bb1", other.password)
        assertEquals(SourceMessageConfidence.HIGH, other.confidence)
    }

    @Test
    fun severalUrlsShareLabelledCredentials() {
        val message = """
            http://a.example:8080
            http://b.example:8080
            Benutzer: u1x
            Passwort: p1x
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals(2, draft.urls.size)
        assertEquals("http://a.example:8080", draft.server)
        assertEquals("u1x", draft.username)

        val second = SourceMessageParser.parse(message, "http://b.example:8080")

        assertEquals("http://b.example:8080", second.server)
        assertEquals("u1x", second.username)
        assertEquals("p1x", second.password)
    }

    @Test
    fun anUnknownPreferredUrlFallsBackToTheFirstCandidate() {
        val draft = SourceMessageParser.parse("http://example.com:8080 u1 p1", "http://nope.example:9")

        assertEquals("http://example.com:8080", draft.url)
    }

    @Test
    fun repeatedUrlsAreListedOnce() {
        val message = "http://example.com:8080\nhttp://example.com:8080"

        assertEquals(listOf("http://example.com:8080"), SourceMessageParser.parse(message).urls)
    }

    // ---- M3U ----------------------------------------------------------------

    @Test
    fun aPlaylistUrlWithoutCredentialsIsAnM3uSource() {
        val draft = SourceMessageParser.parse("http://provider.example/playlist.m3u")

        assertEquals(SourceType.M3U, draft.type)
        assertEquals("http://provider.example/playlist.m3u", draft.server)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun aPlaylistUrlKeepsItsQueryCredentialsInsideTheUrl() {
        val url = "http://provider.example/playlist.m3u8?username=u1x&password=p1x"

        val draft = SourceMessageParser.parse(url)

        assertEquals(SourceType.M3U, draft.type)
        assertEquals(url, draft.server)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    // ---- Stalker ------------------------------------------------------------

    @Test
    fun aPortalAndMacBecomeAStalkerSource() {
        val message = """
            Portal: http://portal.example:8080/c/
            MAC: 00-1a-79-aa-bb-cc
        """.trimIndent()

        val draft = SourceMessageParser.parse(message)

        assertEquals(SourceType.STALKER, draft.type)
        assertEquals("http://portal.example:8080/c/", draft.server)
        assertEquals("00:1A:79:AA:BB:CC", draft.mac)
        assertEquals(SourceMessageConfidence.HIGH, draft.confidence)
    }

    @Test
    fun aMacWithDotSeparatorsIsCanonicalised() {
        val message = """
            Portal: http://portal.example:8080/c/
            MAC: 00.1a.79.aa.bb.cc
        """.trimIndent()

        assertEquals("00:1A:79:AA:BB:CC", SourceMessageParser.parse(message).mac)
    }

    @Test
    fun aMacWithoutAPortalIsOnlyAGuess() {
        val draft = SourceMessageParser.parse("MAC 00:1A:79:AA:BB:CC")

        assertEquals(SourceType.STALKER, draft.type)
        assertEquals("00:1A:79:AA:BB:CC", draft.mac)
        assertEquals("", draft.server)
        assertNull(draft.url)
        assertEquals(SourceMessageConfidence.GUESS, draft.confidence)
        assertTrue(draft.hasAnything)
    }

    // ---- Nothing to read ----------------------------------------------------

    @Test
    fun plainTextFindsNothing() {
        val draft = SourceMessageParser.parse("Hallo, wie geht's dir?")

        assertEquals(SourceMessageConfidence.NONE, draft.confidence)
        assertTrue(draft.urls.isEmpty())
        assertEquals("", draft.server)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
        assertEquals("", draft.mac)
        assertFalse(draft.hasAnything)
    }

    @Test
    fun anEmptyMessageFindsNothing() {
        val draft = SourceMessageParser.parse("")

        assertEquals(SourceMessageConfidence.NONE, draft.confidence)
        assertFalse(draft.hasAnything)
        assertNull(draft.url)
    }
}
