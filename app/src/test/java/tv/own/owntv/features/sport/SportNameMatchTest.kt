package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure text logic behind the Sports-Hub channel matching. No Android, no DAO, no network —
 * just [SportNameMatch] and [SportsVocab], so these run fast and pin the two mistakes the matcher
 * cannot afford: accent/casing deciding a match, and a substring match crossing a word boundary.
 */
class SportNameMatchTest {

    // --- normalize: accents, casing, punctuation ---

    @Test
    fun accentsAreFolded() {
        assertEquals("munchen", SportNameMatch.normalize("München").trim())
        assertEquals("koln", SportNameMatch.normalize("Köln").trim())
    }

    @Test
    fun germanSharpSBecomesDoubleS() {
        assertEquals("fussball", SportNameMatch.normalize("Fußball").trim())
    }

    @Test
    fun punctuationBecomesASingleSpace() {
        assertEquals("1 fc koln", SportNameMatch.normalize("1. FC Köln").trim())
        assertEquals("brighton hove albion", SportNameMatch.normalize("Brighton & Hove Albion").trim())
    }

    @Test
    fun theResultIsSpacePadded() {
        assertEquals(" bayern ", SportNameMatch.normalize("Bayern"))
    }

    @Test
    fun emptyInputNormalizesToPadding() {
        assertEquals("", SportNameMatch.normalize("").trim())
    }

    // --- containsWord / containsPhrase: whole-word bounds ---

    @Test
    fun containsWordRejectsAPrefixInsideALongerWord() {
        assertFalse(SportNameMatch.containsWord("city", SportNameMatch.normalize("Velocity")))
    }

    @Test
    fun containsWordAcceptsAWholeWord() {
        assertTrue(SportNameMatch.containsWord("city", SportNameMatch.normalize("Man City HD")))
    }

    @Test
    fun containsPhraseMatchesTheWholeNameAcrossPunctuation() {
        assertTrue(SportNameMatch.containsPhrase("Sky Sport Bundesliga 1", SportNameMatch.normalize("DE: SKY SPORT BUNDESLIGA 1 HD")))
    }

    // --- tokensForName / discoveryPhrases ---

    @Test
    fun tokensDropShortWordsAndStops() {
        assertEquals(
            setOf("bayern", "munchen"),
            SportNameMatch.tokensForName("FC Bayern München", setOf("fc")),
        )
    }

    @Test
    fun discoveryPhrasesKeepRawAccentsPlusWords() {
        val phrases = SportNameMatch.discoveryPhrases("FC Bayern München", setOf("fc"))
        assertTrue(phrases.contains("FC Bayern München"))
        assertTrue(phrases.contains("Bayern"))
        assertTrue("raw accents must survive for SQL LIKE", phrases.contains("München"))
    }

    @Test
    fun discoveryPhrasesDropsBroadcastFiller() {
        val phrases = SportNameMatch.discoveryPhrases("Sky Sport Bundesliga 1", setOf("sky", "sport"))
        assertFalse(phrases.contains("Sky"))
        assertFalse(phrases.contains("Sport"))
        assertTrue(phrases.contains("Bundesliga"))
    }

    // --- mentionsSport ---

    @Test
    fun mentionsSportIsWholeWordAndFolded() {
        // Whole-word: "sport" does not match inside "sports", which is why the shipped vocabulary
        // lists both forms (see res/raw/sports_matching.json → sportWords).
        val sports = setOf("fussball", "sport", "sports")
        assertTrue(SportNameMatch.mentionsSport("Fußball", sports))
        assertTrue(SportNameMatch.mentionsSport("Sports", sports))
        assertTrue(SportNameMatch.mentionsSport("Sport", sports))
        assertFalse(SportNameMatch.mentionsSport("Nachrichten", sports))
    }

    // --- SportsVocab ---

    @Test
    fun aliasesAreLookedUpCaseAndAccentInsensitively() {
        val vocab = SportsVocab.of(mapOf("Borussia Dortmund" to listOf("Dortmund", "BVB")))
        assertEquals(listOf("Dortmund", "BVB"), vocab.aliasesFor(listOf("Borussia Dortmund")))
        assertEquals(listOf("Dortmund", "BVB"), vocab.aliasesFor(listOf("borussia dortmund")))
    }

    @Test
    fun collegeAliasesResolveCaseInsensitively() {
        val vocab = SportsVocab.of(mapOf("Ohio State Buckeyes" to listOf("Ohio State", "OSU")))
        assertEquals(listOf("Ohio State", "OSU"), vocab.aliasesFor(listOf("ohio state buckeyes")))
    }

    @Test
    fun ampersandAliasTokenizesCleanly() {
        assertEquals(
            setOf("texas", "aggies"),
            SportNameMatch.tokensForName("Texas A&M Aggies", emptySet()),
        )
    }

    @Test
    fun unknownNamesYieldNoAliases() {
        assertTrue(SportsVocab.of(emptyMap()).aliasesFor(listOf("Some Unknown Club")).isEmpty())
        assertTrue(SportsVocab.EMPTY.aliasesFor(listOf("Bayern Munich")).isEmpty())
    }

    @Test
    fun foldKeyCollapsesAccentsAndPunctuation() {
        assertEquals("bayern munchen", SportsVocab.foldKey("Bayern München"))
        assertEquals("man utd", SportsVocab.foldKey("Man Utd"))
    }
}
