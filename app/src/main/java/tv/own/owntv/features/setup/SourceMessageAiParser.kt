package tv.own.owntv.features.setup

import java.net.URI
import org.json.JSONObject
import tv.own.owntv.core.model.SourceMessageConfidence
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.stalker.StalkerClient

/**
 * Reads the model's answer to a pasted provider message: JSON in, the fields out.
 *
 * Pure — no network, no Android — so the schema handling is unit-tested on the JVM.
 *
 * Everything is checked before it is used, because the answer comes from a model that may
 * hallucinate, and because the pasted text itself may try to talk the model into something:
 *  - only `http(s)` URLs are accepted, and only when their host really occurs in the message;
 *  - only a MAC in the canonical shape is accepted, and only when the message carries it;
 *  - the type must be one of the three the add-source form knows.
 *
 * What survives is still only a *reading*: the confirm page shows it, the user decides.
 */
object SourceMessageAiParser {

    /**
     * Parse the model's answer to [message]. Null when the answer is no JSON, or holds nothing
     * usable — the caller then falls back to the local rules.
     *
     * [message] is the text the answer was about: it is what the URL and MAC checks are made
     * against, so an invented host or an invented MAC cannot get through.
     */
    fun parse(content: String, message: String): SourceMessageDraft? {
        val json = readJson(content) ?: return null
        val type = readType(json)
        val mac = readMac(json, message)
        val rawServer = readString(json, ServerKey).trim()
        val usable = isHttpUrl(rawServer) && hostOccursIn(rawServer, message)
        val server = if (usable) SourceMessageParser.normalizeServer(rawServer, type) else ""
        val username = readText(json, UsernameKey)
        val password = readText(json, PasswordKey)
        val urls = readUrls(json, message)

        // Credentials the model left in the URL's query are still credentials: an Xtream
        // `get.php?username=…&password=…` is the most common form there is. They are read from the
        // raw URL, because the stored server is the base and no longer carries the query.
        val query = if (usable) SourceMessageParser.parse(rawServer) else null
        val draft = SourceMessageDraft(
            urls = urls,
            url = null,
            type = type,
            server = server,
            username = username.ifEmpty { query?.username.orEmpty() },
            password = password.ifEmpty { query?.password.orEmpty() },
            mac = mac,
            // The reader decides how sure it is, never the model.
            confidence = SourceMessageConfidence.NONE,
        )
        return draft.takeIf { it.hasAnything }
    }

    /** The answer as an object; fenced JSON is unwrapped first, and anything else is dropped. */
    private fun readJson(content: String): JSONObject? {
        val text = stripFences(content)
        if (text.isBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun stripFences(content: String): String {
        var text = content.trim()
        if (!text.startsWith(Fence)) return text
        text = text.removePrefix(FenceJson).removePrefix(Fence).trim()
        val end = text.lastIndexOf(Fence)
        if (end >= 0) text = text.substring(0, end).trim()
        return text
    }

    /** Only the three kinds the form knows; anything else reads as Xtream. */
    private fun readType(json: JSONObject): SourceType = when (readString(json, TypeKey).lowercase()) {
        "m3u", "m3u8" -> SourceType.M3U
        "stalker" -> SourceType.STALKER
        else -> SourceType.XTREAM
    }

    /** Every URL the model listed that is an `http(s)` URL whose host is really in the message. */
    private fun readUrls(json: JSONObject, message: String): List<String> {
        val array = json.optJSONArray(UrlsKey) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until array.length()) {
            val raw = readValue(array.opt(i)).trim()
            if (!isHttpUrl(raw)) continue
            if (!hostOccursIn(raw, message)) continue
            if (out.none { it.equals(raw, ignoreCase = true) }) out.add(raw)
        }
        return out
    }

    /**
     * The MAC in the canonical form — and only when the message carries it. A MAC the model
     * invented would fail the portal login, so the message is the only admissible source.
     */
    private fun readMac(json: JSONObject, message: String): String {
        val raw = readString(json, MacKey).trim()
        if (raw.isEmpty()) return ""
        val canonical = StalkerClient.canonicalizeMac(raw) ?: return ""
        return canonical.takeIf { macOccursIn(it, message) }.orEmpty()
    }

    /** Plain text, bounded and free of control characters — it ends up in a form field. */
    private fun readText(json: JSONObject, key: String): String {
        val raw = readString(json, key).trim()
        if (raw.isEmpty()) return ""
        if (raw.length > MaxFieldLength) return ""
        if (raw.any { it.isISOControl() }) return ""
        return raw
    }

    /**
     * The value as text. A JSON null is *not* the string "null" — a model that sends
     * `{"password":null}` means "no password", not a password called "null".
     */
    private fun readString(json: JSONObject, key: String): String = readValue(json.opt(key))

    private fun readValue(value: Any?): String =
        if (value == null || value == JSONObject.NULL) "" else value.toString()

    private fun isHttpUrl(value: String): Boolean {
        if (!value.startsWith(HttpScheme, ignoreCase = true) && !value.startsWith(HttpsScheme, ignoreCase = true)) {
            return false
        }
        return runCatching { URI(value).host }.getOrNull()?.isNotBlank() == true
    }

    /** True when the URL's host occurs in the message — the model may not invent one. */
    private fun hostOccursIn(url: String, message: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        return message.lowercase().contains(host.lowercase())
    }

    /** True when the message carries the MAC's twelve symbols, whatever separators it used. */
    private fun macOccursIn(mac: String, message: String): Boolean {
        val body = mac.replace(":", "").lowercase()
        val haystack = message.lowercase().filter { it.isLetterOrDigit() }
        return haystack.contains(body)
    }

    private const val TypeKey = "type"
    private const val ServerKey = "server"
    private const val UsernameKey = "username"
    private const val PasswordKey = "password"
    private const val MacKey = "mac"
    private const val UrlsKey = "urls"
    private const val HttpScheme = "http://"
    private const val HttpsScheme = "https://"
    private const val Fence = "```"
    private const val FenceJson = "```json"
    private const val MaxFieldLength = 256
}
