package tv.own.owntv.features.setup

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.model.SourceAnalysisAiState
import tv.own.owntv.features.sport.AiFailure

/**
 * How a failed AI call becomes the hint on the companion page.
 *
 * The distinction matters to the user: a missing key is fixed in the sports settings, a rejected
 * key has to be replaced, a missing credit has to be topped up, and a network problem needs
 * nothing but patience.
 */
class SourceMessageAnalyzerTest {

    @Test
    fun `every failure maps to the hint that fits it`() {
        assertEquals(
            SourceAnalysisAiState.NO_KEY,
            SourceMessageAnalyzer.aiStateFor(AiFailure.NO_KEY),
        )
        assertEquals(
            SourceAnalysisAiState.INVALID_KEY,
            SourceMessageAnalyzer.aiStateFor(AiFailure.INVALID_KEY),
        )
        assertEquals(
            SourceAnalysisAiState.NO_CREDIT,
            SourceMessageAnalyzer.aiStateFor(AiFailure.INSUFFICIENT_CREDIT),
        )
    }

    @Test
    fun `the failures that are not the user's doing end up as one`() {
        for (failure in listOf(
            AiFailure.RATE_LIMITED,
            AiFailure.NETWORK,
            AiFailure.EMPTY,
            AiFailure.UNKNOWN,
        )) {
            assertEquals(
                "failure $failure",
                SourceAnalysisAiState.FAILED,
                SourceMessageAnalyzer.aiStateFor(failure),
            )
        }
    }

    /**
     * The AI functions switched off: the message was not read by a model at all, so the page says
     * so — and names the switch, so it is not mistaken for a message the AI simply did not get to.
     */
    @Test
    fun `a switched-off AI is not asked and says so`() {
        assertEquals(
            SourceAnalysisAiState.DISABLED,
            SourceMessageAnalyzer.aiStateFor(AiFailure.DISABLED),
        )
    }
}
