package tv.own.owntv.features.live

import java.text.Normalizer
import tv.own.owntv.features.sport.CountryTags

/**
 * A provider name split the way Stage shows it (G5): "DE| Sky Cinema Premieren ᴴᴰ" becomes the name
 * "Sky Cinema Premieren", the tag "HD" and the provider's tag "DE". Providers write their quality
 * and source marks as superscript letters; each run of them is one tag. The tag is shown once, as
 * the "GROUPS · DE" heading, instead of on every row.
 */
data class ProviderName(val name: String, val tags: List<String>, val tag: String?)

object ProviderTags {
    /**
     * The provider's tag at the start of the name, cut off the name. "DE| …", "USA| …" and
     * "┃DE┃ …" are a prefix; a colon or a slash ("DE: …") stays in the name, as before. The forms
     * come from [CountryTags], the one place that knows them, so the badge and the language filter
     * can never disagree about the same name.
     */
    fun parse(raw: String): ProviderName {
        val countryTag = CountryTags.tagOf(raw)
        var rest = countryTag?.takeIf { isPrefix(it) }?.name ?: raw
        val tags = mutableListOf<String>()
        val name = StringBuilder()
        val run = StringBuilder()
        fun endRun() {
            if (run.isEmpty()) return
            val tag = Normalizer.normalize(run, Normalizer.Form.NFKC).uppercase()
            if (tag !in tags) tags += tag
            run.clear()
        }
        for (ch in rest) {
            if (isSuperscript(ch)) run.append(ch) else { endRun(); name.append(ch) }
        }
        endRun()
        rest = name.toString().replace(Regex("""\s+"""), " ").trim()
        // A name that was nothing but a prefix and tags keeps the provider's own spelling.
        if (rest.isEmpty()) return ProviderName(raw.trim(), emptyList(), null)
        return ProviderName(rest, tags, countryTag?.normalized)
    }

    /**
     * Whether the tag is a prefix of the name, i.e. it is cut off before the name is shown: the bar
     * and block forms are, the colon and slash forms stay in the name.
     */
    private fun isPrefix(tag: CountryTags.Tag): Boolean = when (tag.form) {
        CountryTags.Form.BAR, CountryTags.Form.BLOCK -> true
        CountryTags.Form.SEPARATOR -> false
    }

    /**
     * The one tag every group shares, for the "GROUPS · DE" heading; null when they differ or any
     * group has none (then the heading reads just "GROUPS").
     */
    fun sharedTag(names: List<ProviderName>): String? =
        names.map { it.tag }.distinct().singleOrNull()

    /**
     * The tag badge a channel/category row shows for [rawName]: the provider's own tag, be it a
     * country ("DE"), a language ("HINDI") or a region ("AFR"). Null when the name carries no tag —
     * the row then shows no badge, never a guess.
     */
    fun channelTag(rawName: String, parsed: ProviderName = parse(rawName)): String? = parsed.tag

    // Superscript digits and letters, and the small-capital/modifier letters providers use for them
    // (ᴴᴰ, ʳᴬᵂ, ⁴ᴷ). Each folds to a plain letter or digit under NFKC.
    private fun isSuperscript(c: Char): Boolean =
        c in 'ᴬ'..'ᵪ' || c in 'ʰ'..'ʸ' || c in 'ˠ'..'ˤ' ||
            c in '⁰'..'ₜ' || c == '¹' || c == '²' || c == '³' ||
            c == 'ᶜ' || c == 'ᶠ' || c == 'ᶻ' || c == 'ꟸ' || c == 'ꟹ'
}
