package tv.own.owntv.features.setup

import java.net.URLDecoder
import tv.own.owntv.core.model.SourceMessageConfidence
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.stalker.StalkerClient

/**
 * What [SourceMessageParser] read out of one pasted provider message (the "URL / user / password"
 * blob a shop or reseller sends). Purely what could be *read* — nothing is verified here; the
 * confirm step (and, when it runs, the AI analysis) owns the truth.
 *
 * [urls] is every URL the message carried, in the order it appeared, so the confirm step can offer a
 * choice when a message holds several playlists. [url] is the one the fields below describe; the
 * fields of another candidate are read with `parse(message, thatUrl)`.
 */
data class SourceMessageDraft(
    val urls: List<String> = emptyList(),
    val url: String? = null,
    val type: SourceType = SourceType.XTREAM,
    /** Xtream base (`scheme://host:port`, no endpoint file), the M3U URL, or the Stalker portal URL. */
    val server: String = "",
    val username: String = "",
    val password: String = "",
    /** Canonical `AA:BB:CC:DD:EE:FF` when the message carried a MAC; empty otherwise. */
    val mac: String = "",
    val confidence: SourceMessageConfidence = SourceMessageConfidence.NONE,
) {
    /** True when the message held something to pre-fill: a URL, a MAC, or a labelled server. */
    val hasAnything: Boolean get() = urls.isNotEmpty() || mac.isNotBlank() || server.isNotBlank()
}

/**
 * Reads URL, credentials and MAC out of a pasted provider message — no network, no Android, so it is
 * unit-tested on the JVM.
 *
 * Deliberately conservative: it recognises the shapes providers actually send (an Xtream `get.php`
 * URL with `username=`/`password=`, labelled fields in English or German, a MAC/portal pair, the
 * classic three-line "URL / user / pass", and a bare `host:port`), and reports [SourceMessageConfidence]
 * instead of inventing a value it did not see. A wrong server URL costs minutes of sync, so the
 * confirm step in the companion page always gets the last word.
 *
 * The Xtream base is derived, not taken verbatim: `SourceEntity.url` is used as `base(s)` and
 * `XtreamClient` appends `/player_api.php` to it, so a pasted `…/get.php?username=…` URL would
 * otherwise produce `…/get.php/player_api.php`.
 */
object SourceMessageParser {

    /**
     * Read [message]. [preferredUrl] re-derives the fields for one of the message's own URLs (the
     * "which URL?" step when a message carries several); a value that is not in the message is
     * ignored and the first candidate wins.
     */
    fun parse(message: String, preferredUrl: String? = null): SourceMessageDraft {
        val text = normalize(message)
        val urls = findUrls(text)
        val mac = findMac(text).orEmpty()
        val labels = findLabels(text)
        val chosen = pickUrl(urls, preferredUrl)

        // Per-URL query credentials outrank labelled ones for THAT url; a labelled server stands in
        // when the message carried no http URL at all.
        val parts = chosen?.let(::derive)
            ?: labels.server.trim().takeIf { it.isNotEmpty() }?.let(::derive)
        val urlCredentials = parts?.let { Credentials(it.user, it.pass, Origin.QUERY) }
            ?.takeIf { it.user.isNotEmpty() && it.pass.isNotEmpty() }
        val labelCredentials = Credentials(labels.user, labels.pass, Origin.LABEL)
            .takeIf { it.user.isNotEmpty() && it.pass.isNotEmpty() }
        val positional = if (urlCredentials == null && labelCredentials == null && mac.isEmpty()) {
            positionalCredentials(text, urls, labels)
        } else {
            null
        }
        val credentials = urlCredentials ?: labelCredentials ?: positional

        // A playlist URL keeps its query (the M3U form stores the URL as-is and has no credential
        // fields); everything else with a query is an Xtream panel.
        val playlistUrl = parts?.takeIf { it.isPlaylist }?.url
        val type = when {
            mac.isNotEmpty() -> SourceType.STALKER
            playlistUrl != null -> SourceType.M3U
            else -> SourceType.XTREAM
        }
        val server = when (type) {
            SourceType.M3U -> playlistUrl.orEmpty()
            SourceType.STALKER -> parts?.url.orEmpty()
            else -> parts?.base.orEmpty()
        }
        // An M3U source stores one URL and has no credential fields, so its query credentials stay
        // inside the URL and are not offered as separate values.
        val formCredentials = credentials.takeIf { type != SourceType.M3U }

        val confidence = when {
            type == SourceType.STALKER && server.isNotEmpty() -> SourceMessageConfidence.HIGH
            type == SourceType.M3U -> SourceMessageConfidence.HIGH
            formCredentials != null && formCredentials.origin != Origin.POSITION && server.isNotEmpty() ->
                SourceMessageConfidence.HIGH
            formCredentials != null && formCredentials.origin == Origin.POSITION -> SourceMessageConfidence.GUESS
            type == SourceType.STALKER || server.isNotEmpty() || formCredentials != null ->
                SourceMessageConfidence.GUESS
            else -> SourceMessageConfidence.NONE
        }

        return SourceMessageDraft(
            urls = urls,
            url = chosen,
            type = type,
            server = server,
            username = formCredentials?.user.orEmpty(),
            password = formCredentials?.pass.orEmpty(),
            mac = mac,
            confidence = confidence,
        )
    }

    /**
     * The URL as the add-source form would store it: the Xtream base for [SourceType.XTREAM] (no
     * `get.php`, no query), the whole URL for a playlist (its query carries the credentials) and the
     * portal URL for Stalker.
     *
     * Public because the AI's reading goes through the same rule: one truth about what a URL means,
     * whichever reader produced it. An empty result means the value is not a URL at all.
     */
    fun normalizeServer(rawUrl: String, type: SourceType): String {
        val parts = derive(rawUrl) ?: return ""
        return when (type) {
            SourceType.M3U, SourceType.STALKER -> parts.url
            else -> parts.base
        }
    }

    /**
     * True when the URL points at one of the Xtream panel endpoints (`get.php`, `player_api.php`,
     * …). Such a URL is a panel and never a playlist — whatever a reader claims about it.
     */
    fun isXtreamPanel(rawUrl: String): Boolean {
        val parts = derive(rawUrl) ?: return false
        val last = parts.url.substringBefore('?').substringAfterLast('/').lowercase()
        return last in XtreamEndpoints
    }

    private enum class Origin { QUERY, LABEL, POSITION }
    private data class Credentials(val user: String, val pass: String, val origin: Origin)

    private data class Labels(val user: String, val pass: String, val server: String)

    /** One URL candidate split into the parts the add-source form needs. */
    private data class UrlParts(
        /** The URL as it should be stored or shown, scheme included. */
        val url: String,
        /** Xtream base: scheme + authority + any non-endpoint path prefix, no query, no trailing `/`. */
        val base: String,
        /** True when the path is a playlist file, so the URL is kept whole. */
        val isPlaylist: Boolean,
        val user: String,
        val pass: String,
    )

    // ---- text normalisation -------------------------------------------------

    private val UnicodeSpaces = Regex("[\u00A0\u1680\u2000-\u200B\u202F\u205F\u3000]")

    /** Non-breaking spaces and stray CRs come along with copy/paste; `\s` in Java regex misses both. */
    private fun normalize(message: String): String =
        UnicodeSpaces.replace(message.replace("\r\n", "\n").replace('\r', '\n'), " ")

    // ---- URLs ---------------------------------------------------------------

    /** `,` and `;` end a URL: they separate a pasted list, and IPTV URLs carry them unencoded. */
    private val SchemeUrlRegex = Regex("""(?i)\bhttps?://[^\s<>"'()\[\]{}|\\,;]+""")

    private val BareHostPortRegex = Regex(
        """(?i)(?:^|[\s,;(<"'\[])((?:\d{1,3}\.){3}\d{1,3}|[a-z0-9][a-z0-9\-]*(?:\.[a-z0-9\-]+)+):(\d{2,5})(?![0-9])""",
    )

    private val UrlShape = Regex("""(?i)^(https?)://([^/?#]+)([^?#]*)(?:\?([^#]*))?.*$""")

    /** Endpoint files that sit *under* the Xtream base; their own segment never belongs to it. */
    private val XtreamEndpoints = setOf("get.php", "player_api.php", "panel_api.php", "xmltv.php", "index.html")

    private const val TrailingNoise = ".,;:!?)]}>\"'|"

    private fun findUrls(text: String): List<String> {
        val found = ArrayList<String>()
        val schemeSpans = ArrayList<IntRange>()
        for (match in SchemeUrlRegex.findAll(text)) {
            val cleaned = cleanUrl(match.value)
            if (cleaned.isEmpty() || derive(cleaned) == null) continue
            found += cleaned
            schemeSpans += match.range
        }
        // A scheme-less `host:port` is a candidate too, but never one that only exists *inside* a URL
        // we already read (`http://host:8080` would otherwise yield a second, redundant candidate).
        for (match in BareHostPortRegex.findAll(text)) {
            val span = match.groups[1]?.range ?: continue
            if (schemeSpans.any { span.first in it }) continue
            found += HttpScheme + match.groupValues[1] + ":" + match.groupValues[2]
        }
        return found.distinct()
    }

    private fun cleanUrl(raw: String): String {
        var value = raw.trim()
        while (value.isNotEmpty() && value.last() in TrailingNoise) value = value.dropLast(1)
        return value
    }

    private fun pickUrl(urls: List<String>, preferred: String?): String? {
        val wanted = preferred?.trim()
        if (!wanted.isNullOrEmpty()) {
            urls.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        return urls.firstOrNull()
    }

    /** Split a candidate; null when it cannot be read as `scheme://authority[/path][?query]` at all. */
    private fun derive(raw: String): UrlParts? {
        val trimmed = cleanUrl(raw)
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            HttpScheme + trimmed
        }
        val match = UrlShape.matchEntire(withScheme) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val path = match.groupValues[3]
        if (authority.isEmpty()) return null
        val query = queryParams(match.groupValues[4])
        val base = (scheme + SchemeSeparator + authority + stripEndpoint(path)).trimEnd('/')
        return UrlParts(
            url = withScheme,
            base = base,
            isPlaylist = path.substringBefore('?').let { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) },
            user = query["username"] ?: query["user"].orEmpty(),
            pass = query["password"] ?: query["pass"].orEmpty(),
        )
    }

    /** Drop a trailing Xtream endpoint segment, keep any real path prefix (`…/iptv/get.php`). */
    private fun stripEndpoint(path: String): String {
        if (path.isEmpty()) return ""
        val segments = path.split('/')
        val last = segments.lastOrNull()?.lowercase().orEmpty()
        return if (last in XtreamEndpoints) segments.dropLast(1).joinToString("/") else path
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val split = pair.split('=', limit = 2)
            val key = split[0].lowercase()
            if (key.isNotEmpty()) out[key] = urlDecode(split.getOrNull(1).orEmpty())
        }
        return out
    }

    private fun urlDecode(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    // ---- labelled fields ----------------------------------------------------

    // The leading character class keeps a label inside a URL (`?username=…`, `&password=…`) from
    // being read as a labelled field: `?` and `&` are deliberately not in it.
    private val UserLabelRegex = Regex(
        """(?i)(?:^|[\s,;|"'(<\[])(?:username|user|benutzername|benutzer|login)\s*[:=]\s*([^\s&,;|"']+)""",
    )
    private val PassLabelRegex = Regex(
        """(?i)(?:^|[\s,;|"'(<\[])(?:passwort|password|pass|pw|kennwort)\s*[:=]\s*([^\s&,;|"']+)""",
    )
    private val ServerLabelRegex = Regex(
        """(?i)(?:^|[\s,;|"'(<\[])(?:url|server|host|adresse|address|portal|link)\s*[:=]\s*([^\s,;|"']+)""",
    )

    private fun findLabels(text: String): Labels = Labels(
        user = UserLabelRegex.find(text)?.groupValues?.get(1).orEmpty(),
        pass = PassLabelRegex.find(text)?.groupValues?.get(1).orEmpty(),
        server = ServerLabelRegex.find(text)?.groupValues?.get(1)?.let(::cleanUrl).orEmpty(),
    )

    // ---- MAC ----------------------------------------------------------------

    private val MacRegex = Regex(
        """(?i)(?<![0-9a-z])([0-9a-z]{2}([:.\-])[0-9a-z]{2}(?:\2[0-9a-z]{2}){4})(?![0-9a-z])""",
    )

    /** Canonicalised through Core's own rule so the field matches what the Stalker client accepts. */
    private fun findMac(text: String): String? =
        MacRegex.find(text)?.groupValues?.get(1)?.let { StalkerClient.canonicalizeMac(it) }

    // ---- positional fallback ------------------------------------------------

    /**
     * The classic three-line share ("URL / user / pass") carries no label at all. Only used when
     * neither a query nor a label produced credentials, only with a URL in hand, and only when
     * exactly two plausible tokens are left over — anything longer is prose, not credentials.
     */
    private fun positionalCredentials(text: String, urls: List<String>, labels: Labels): Credentials? {
        // Two loose tokens name no provider on their own: they only mean something next to a server.
        if (urls.isEmpty() && labels.server.isEmpty()) return null

        var rest = text
        urls.forEach { rest = rest.replace(it, " ") }
        listOf(UserLabelRegex, PassLabelRegex, ServerLabelRegex).forEach { regex ->
            regex.findAll(rest).map { it.value }.toList().forEach { rest = rest.replace(it, " ") }
        }
        val tokens = rest.split(WhitespaceRegex).filter { it.isNotBlank() }
        if (tokens.size != 2) return null
        if (!isPlausibleCredential(tokens[0]) || !isPlausibleCredential(tokens[1])) return null
        return Credentials(tokens[0], tokens[1], Origin.POSITION)
    }

    private val WhitespaceRegex = Regex("\\s+")

    private const val MaxCredentialLength = 64
    private const val MinCredentialLength = 2

    private fun isPlausibleCredential(token: String): Boolean =
        token.length in MinCredentialLength..MaxCredentialLength &&
            token.none { it == '@' } &&
            token.last() !in ".,!?;:" &&
            token.any { it.isLetterOrDigit() }

    private const val HttpScheme = "http://"
    private const val SchemeSeparator = "://"
}
