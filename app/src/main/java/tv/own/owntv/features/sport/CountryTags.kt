package tv.own.owntv.features.sport

import java.util.Locale

/**
 * The tag a provider puts at the start of a channel or group name — a country ("DE:", "UK|",
 * "┃DE┃"), a language ("┃HINDI┃"), a region ("┃AFR┃") or a brand ("┃CANAL+┃"), whatever the
 * provider writes. One place that knows the forms, so the badge, the Live-TV language filter and
 * the Sport-Hub filter can never disagree about the same name.
 *
 * The forms:
 *  - "DE: NAME", "FR/NAME" — the code first, a colon or a slash behind it,
 *  - "DE| NAME", "USA| NAME" — the code first, a bar behind it,
 *  - "┃DE┃ NAME", "| DE | NAME" — the tag between two bars,
 *  - "┃ENGLISH┃HINDI┃ NAME" — two tags in a row; the first one leads, the second is cut off with
 *    it (a dub marker, not a second language).
 *
 * The tag is read as the provider wrote it. What it *means* — a language, a country, a mark — is
 * [SportLanguages]' business, not this object's.
 */
object CountryTags {

    /** The bar a provider writes next to its tag. */
    const val Bar = '|'

    /** The heavy bar some providers use instead of [Bar] ("┃DE┃ NAME"). */
    const val HeavyBar = '\u2503'

    /** How the provider writes the tag. */
    enum class Form {
        /** "DE: NAME", "FR/NAME" — the code first, a colon or a slash behind it. */
        SEPARATOR,

        /** "DE| NAME", "USA| NAME" — the code first, a bar behind it. */
        BAR,

        /** "┃DE┃ NAME" — the tag between two bars. */
        BLOCK,
    }

    /**
     * The tag at the start of a name: its content as the provider wrote it ("DE", "USA", "HINDI",
     * "CA EN"), the name without it and how the tag was written.
     */
    data class Tag(
        /** The tag as the provider wrote it. */
        val content: String,
        /** The name without the tag. */
        val name: String,
        /** How the tag was written. */
        val form: Form,
    ) {
        /** The tag in upper case with collapsed spaces ("ca en" → "CA EN"). */
        val normalized: String
            get() = content.trim().uppercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }.joinToString(" ")

        /** The tag's words in lower case — what the name matcher drops from a channel name. */
        val words: List<String>
            get() = normalized.split(' ').map { it.lowercase(Locale.ROOT) }
    }

    // "DE: NAME", "FR/NAME" — the code first, a colon or a slash behind it.
    private val Separator = Regex("""^\s*([A-Za-z]{2})\s*[:/]\s*""")

    // "DE| NAME", "de| NAME" — the code first, a bar behind it. Two letters, any case.
    private val BarTwo = Regex("""^\s*([A-Za-z]{2})\s*\|\s*""")

    // "USA| NAME" — a three-letter code, in capitals like the provider wrote it. Three letters in
    // lower case are a name, not a code ("Sky | Cinema" stays a name).
    private val BarThree = Regex("""^\s*([A-Z]{3})\s*\|\s*""")

    /**
     * The tag at the start of [raw], or null when the name carries none. [Tag.name] is the name
     * without the tag.
     */
    fun tagOf(raw: String): Tag? {
        blockForm(raw)?.let { return it }
        BarTwo.find(raw)?.let { match ->
            return Tag(match.groupValues[1], raw.substring(match.range.last + 1), Form.BAR)
        }
        BarThree.find(raw)?.let { match ->
            return Tag(match.groupValues[1], raw.substring(match.range.last + 1), Form.BAR)
        }
        Separator.find(raw)?.let { match ->
            return Tag(match.groupValues[1], raw.substring(match.range.last + 1), Form.SEPARATOR)
        }
        return null
    }

    /**
     * The block form: the tag between two bars ("┃DE┃ NAME", "| DE | NAME"). A bar inside a name
     * is no tag — only a bar at the very start counts. Further tags in a row ("┃ENGLISH┃HINDI┃
     * NAME") are cut off with the first one; they are short and in capitals, the name is not.
     */
    private fun blockForm(raw: String): Tag? {
        val trimmed = raw.trimStart()
        val lead = trimmed.firstOrNull() ?: return null
        if (lead != Bar && lead != HeavyBar) return null
        val close = trimmed.indexOfBar(from = 1) ?: return null
        if (close <= 1) return null
        val content = trimmed.substring(1, close).trim()
        if (content.isEmpty()) return null
        var name = trimmed.substring(close + 1)
        // A second tag in a row ("┃ENGLISH┃HINDI┃ NAME"): the first leads, the second is a dub
        // marker and goes with it.
        while (true) {
            val fieldEnd = name.indexOfFirst { it == Bar || it == HeavyBar }
            if (fieldEnd <= 0) break
            val field = name.substring(0, fieldEnd).trim()
            if (!isTagField(field)) break
            name = name.substring(fieldEnd + 1)
        }
        return Tag(content, name.trimStart(), Form.BLOCK)
    }

    /** The next bar ([Bar] or [HeavyBar]) at or after [from]. */
    private fun String.indexOfBar(from: Int): Int? {
        for (i in from until length) {
            val ch = this[i]
            if (ch == Bar || ch == HeavyBar) return i
        }
        return null
    }

    /** A further tag field: short and in capitals ("HINDI", "CA EN", "ACTOR") — never a name. */
    private fun isTagField(field: String): Boolean {
        if (field.isEmpty() || field.length > MaxTagFieldLength) return false
        if (field.any { it.isLowerCase() }) return false
        return field.all { it.isUpperCase() || it.isDigit() || it in FieldSeparators }
    }

    /** The characters a tag field may hold besides letters and digits. */
    private val FieldSeparators = setOf(' ', '&', '+', '.', '\'', '-', '/')

    /** How long a further tag field may be. */
    private const val MaxTagFieldLength = 12
}
