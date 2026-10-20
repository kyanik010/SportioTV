package tv.own.owntv.features.setup

import tv.own.owntv.core.companion.CompanionSourceAnalysis
import tv.own.owntv.core.model.SourceMessageConfidence
import tv.own.owntv.core.model.SourceType

/**
 * Puts the local reading of a provider message and the AI's reading into one result.
 *
 * Pure, so the rules below are unit-tested on the JVM: which value wins, what may be mixed and
 * what never may.
 *
 * The rules:
 *  - the *target* is the URL the user picked, else the candidate the AI's server matches, else
 *    the first candidate;
 *  - the server is always the local normalization of that target — one truth about what a URL
 *    means, whichever reader found it;
 *  - the credentials are the AI's, but only when the AI read the *target*; otherwise the local
 *    ones — somebody else's login must not be mixed in;
 *  - a MAC is only ever the one the message itself carries;
 *  - the type follows the MAC, then the URL's shape, then the AI.
 */
object SourceMessageMerger {

    /**
     * Merge the two readings of [message]. [local] is what the rules found, [reading] what the AI
     * found (null when the AI was not asked or had nothing to say) and [preferredUrl] the
     * candidate the user picked on the page (null when nothing is picked yet).
     */
    fun merge(
        message: String,
        local: SourceMessageDraft,
        reading: SourceMessageDraft?,
        preferredUrl: String? = null,
    ): CompanionSourceAnalysis {
        val mac = local.mac
        val aiServer = reading?.server?.takeIf { it.isNotBlank() }.orEmpty()
        // The candidate the AI's server points at — the URL is compared in all its forms, so a
        // `get.php?…` and its base are recognised as the same address.
        val aiTarget = if (aiServer.isEmpty()) {
            null
        } else {
            local.urls.firstOrNull { candidate -> sameUrl(candidate, aiServer) }
        }
        val picked = preferredUrl?.let { wanted ->
            local.urls.firstOrNull { it.equals(wanted, ignoreCase = true) }
        }
        // The URL the result describes. The AI's own server — one the rules did not recognise as a
        // candidate — wins over the first candidate: it is the reader's own reading of the text.
        val target = picked ?: aiTarget
        val aiOwnServer = target == null && aiServer.isNotEmpty()
        val effective = target ?: if (aiOwnServer) null else local.url
        val basis = SourceMessageParser.parse(message, effective)

        val type = when {
            mac.isNotEmpty() -> SourceType.STALKER
            basis.type == SourceType.M3U -> SourceType.M3U
            // The AI may call a playlist a playlist even without the .m3u ending — but a panel
            // endpoint is a panel, whatever a reader says.
            reading?.type == SourceType.M3U && !isPanel(effective, aiServer) -> SourceType.M3U
            else -> SourceType.XTREAM
        }
        val server = when {
            effective != null -> SourceMessageParser.normalizeServer(effective, type)
            aiOwnServer -> SourceMessageParser.normalizeServer(aiServer, type)
            else -> basis.server
        }

        // The AI's credentials belong to the URL the AI read. For another URL they are somebody
        // else's login and must not be mixed in.
        val aiApplies = reading != null && aiTarget != null && effective != null && aiTarget == effective
        val aiUsername = reading?.username.orEmpty()
        val aiPassword = reading?.password.orEmpty()
        val username: String
        val password: String
        when {
            aiApplies -> {
                username = aiUsername.ifEmpty { basis.username }
                password = aiPassword.ifEmpty { basis.password }
            }
            aiOwnServer -> {
                username = aiUsername
                password = aiPassword
            }
            else -> {
                username = basis.username
                password = basis.password
            }
        }

        val confidence = when {
            aiApplies -> SourceMessageConfidence.HIGH
            aiOwnServer -> SourceMessageConfidence.GUESS
            else -> basis.confidence
        }

        return CompanionSourceAnalysis(
            type = type,
            server = server,
            username = username,
            password = password,
            mac = mac,
            urls = local.urls,
            url = effective,
            confidence = confidence,
            fromAi = aiApplies || aiOwnServer,
        )
    }

    /** True when two URLs are the same address, in any of the forms a reader may write it. */
    private fun sameUrl(candidate: String, other: String): Boolean =
        urlForms(candidate).intersect(urlForms(other)).isNotEmpty()

    /** True when the URL on show is an Xtream panel endpoint. */
    private fun isPanel(effective: String?, aiServer: String): Boolean {
        val url = effective ?: aiServer
        if (url.isEmpty()) return false
        return SourceMessageParser.isXtreamPanel(url)
    }

    /**
     * The URL as it may be written: with and without the query, with and without the endpoint
     * file, with and without a trailing slash.
     */
    private fun urlForms(url: String): Set<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptySet()
        val base = SourceMessageParser.normalizeServer(trimmed, SourceType.XTREAM)
        return setOf(trimmed, base)
            .flatMap { listOf(it, it.substringBefore('?')) }
            .map { it.trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
