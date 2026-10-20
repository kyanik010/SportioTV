package tv.own.owntv.features.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.companion.CompanionSourceAnalysis
import tv.own.owntv.core.model.SourceAnalysisAiState
import tv.own.owntv.features.sport.AiFailure
import tv.own.owntv.features.sport.AiRequestException
import tv.own.owntv.features.sport.DeepSeekClient
import tv.own.owntv.features.sport.SportsPrefs

/**
 * Reads a pasted provider message: the local rules and the AI, put together.
 *
 * The AI is the primary reader — it understands prose, the rules do not. The local rules are the
 * fallback (no key, no network, no budget) and the cross-check: a URL the message really carries
 * beats a URL the model produced, and the *stored* server is always the local normalization of the
 * chosen URL, whichever reader found it ([SourceMessageMerger] holds those rules).
 *
 * The result is what the companion page shows as pre-filled values. The page asks the user to
 * check them, because a wrong server costs minutes of sync — and it says whether the AI had a look
 * at the message, so a missing key does not go unnoticed.
 *
 * The AI functions can be switched off (Settings › App). Then nothing is asked, nothing is sent,
 * and the page is told the AI was switched off — not that it merely did not get to the message.
 */
class SourceMessageAnalyzer(
    private val deepSeek: DeepSeekClient,
    private val prefs: SportsPrefs,
) {

    /** The last message the AI was asked about. */
    @Volatile private var lastMessage: String = ""

    /** What the AI made of it. A successful reading is kept (no second call, no second cost). */
    @Volatile private var lastOutcome: AiOutcome? = null

    /**
     * Read [message] and describe it as a source. [preferredUrl] is the candidate the user picked
     * on the page; null asks for the reader's own choice.
     *
     * Null when there is nothing to read at all — an empty message.
     */
    suspend fun analyze(message: String, preferredUrl: String?): CompanionSourceAnalysis? {
        val text = message.trim()
        if (text.isEmpty()) return null
        val local = SourceMessageParser.parse(text, preferredUrl)
        val outcome = if (!prefs.aiEnabled.first()) {
            // The AI functions are switched off: the message is read by the local rules alone and
            // nothing leaves the device.
            AiOutcome(null, SourceAnalysisAiState.DISABLED)
        } else if (preferredUrl == null) readWithAi(text) else cachedOutcome(text)
        val merged = SourceMessageMerger.merge(text, local, outcome?.draft, preferredUrl)
        return merged.copy(aiState = outcome?.state ?: SourceAnalysisAiState.NOT_ASKED)
    }

    /**
     * Ask the AI about the whole message.
     *
     * A successful reading is kept, so the same message is never paid for twice. A failure is
     * tried again on the next request: the user may have set a key, topped up credit or come
     * back online in between.
     */
    private suspend fun readWithAi(message: String): AiOutcome {
        val cached = lastOutcome
        if (message == lastMessage && cached != null && cached.state == SourceAnalysisAiState.USED) {
            return cached
        }
        val outcome = try {
            askAi(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiRequestException) {
            // The typed failure says which hint the page shows: rejected key, no credit, …
            AiOutcome(null, aiStateFor(e.failure))
        } catch (e: Exception) {
            AiOutcome(null, SourceAnalysisAiState.FAILED)
        }
        lastMessage = message
        lastOutcome = outcome
        return outcome
    }

    private suspend fun askAi(message: String): AiOutcome {
        // The switch is the last door before a paid request leaves the device.
        if (!prefs.aiEnabled.first()) return AiOutcome(null, SourceAnalysisAiState.DISABLED)
        val key = prefs.deepSeekApiKey.first()
        if (key.isBlank()) return AiOutcome(null, SourceAnalysisAiState.NO_KEY)
        // A pasted chat message is short; a pasted *log* is not, and a huge prompt costs money and
        // time for nothing. The local rules still see the whole text.
        val content = deepSeek.analyzeSourceMessage(message.take(MaxPromptChars), key)
        val draft = SourceMessageAiParser.parse(content, message)
        // A model that answered with something unreadable is a failure, not a reading.
        return if (draft == null) {
            AiOutcome(null, SourceAnalysisAiState.FAILED)
        } else {
            AiOutcome(draft, SourceAnalysisAiState.USED)
        }
    }

    /** The AI's answer to the same message, without paying for it twice. */
    private fun cachedOutcome(message: String): AiOutcome? =
        if (message == lastMessage) lastOutcome else null

    /** What the AI made of the message: its reading, and what happened. */
    private data class AiOutcome(
        val draft: SourceMessageDraft?,
        val state: SourceAnalysisAiState,
    )

    companion object {
        /**
         * What a failed AI call means for the page: which hint the user gets and whether the key
         * has to be touched.
         */
        internal fun aiStateFor(failure: AiFailure): SourceAnalysisAiState = when (failure) {
            AiFailure.NO_KEY -> SourceAnalysisAiState.NO_KEY
            AiFailure.INVALID_KEY -> SourceAnalysisAiState.INVALID_KEY
            AiFailure.INSUFFICIENT_CREDIT -> SourceAnalysisAiState.NO_CREDIT
            AiFailure.RATE_LIMITED -> SourceAnalysisAiState.FAILED
            AiFailure.NETWORK -> SourceAnalysisAiState.FAILED
            AiFailure.EMPTY -> SourceAnalysisAiState.FAILED
            AiFailure.DISABLED -> SourceAnalysisAiState.DISABLED
            AiFailure.UNKNOWN -> SourceAnalysisAiState.FAILED
        }

        /** How much of a pasted message goes to the model. */
        const val MaxPromptChars = 8_000
    }
}
