package app.shosetsu.android.backend.dictionary

import java.text.Normalizer
import java.util.Locale

/*
 * This file is part of shosetsu.
 *
 * shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 */

internal data class GcideEntry(
	val word: String,
	val pos: String?,
	val definition: String,
	val keys: List<String>,
)

private const val MAX_DEFINITION = 1100
private const val MAX_PHRASE_WORDS = 4

private val paragraphPattern = Regex("(?is)<p>(.*?)</p>")
private val headwordPattern = Regex("(?is)<hw>(.*?)</hw>")
private val entryPattern = Regex("(?is)<ent>(.*?)</ent>")
private val posPattern = Regex("(?is)<pos>(.*?)</pos>")
private val alternatePattern = Regex("(?is)<(asp|altname)>(.*?)</\\1>")
private val sensePattern = Regex("(?is)<sn>(.*?)</sn>|<def>(.*?)</def>")
private val hexEscape = Regex("""\\'([0-9a-fA-F]{2})""")
private val gcideEntity = Regex("<([A-Za-z?][A-Za-z0-9]*)/")
private val htmlEntity = Regex("&#(x?[0-9A-Fa-f]+);|&(amp|lt|gt|quot|apos);")
private val tagPattern = Regex("<[^>]+>")
private val whitespace = Regex("\\s+")
private val diacritics = Regex("\\p{Mn}+")

internal fun parseGcide(corpus: String): Sequence<GcideEntry> {
	val flattened = corpus.replace("\r\n", "\n").replace('\n', ' ')
	return paragraphPattern.findAll(flattened).mapNotNull { parseParagraph(it.groupValues[1]) }
}

internal fun lookupCandidates(selection: String): List<String> {
	val key = normalizeKey(selection)
	if (key.isEmpty()) return emptyList()
	val words = key.split(' ')
	if (words.size > MAX_PHRASE_WORDS) return emptyList()
	val candidates = LinkedHashSet<String>()
	candidates += key
	if ('-' in key) {
		candidates += whitespace.replace(key.replace('-', ' '), " ").trim()
	}
	if (' ' in key) candidates += key.replace(' ', '-')
	if (words.size == 1) candidates += inflections(key)
	return candidates.filter { it.isNotEmpty() }
}

internal fun normalizeKey(raw: String): String {
	val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
		.replace(diacritics, "")
		.lowercase(Locale.US)
		.replace('’', '\'')
		.replace('‘', '\'')
	val spaced = buildString(folded.length) {
		for (ch in folded) {
			when {
				ch.isLetterOrDigit() || ch == '\'' || ch == '-' -> append(ch)
				isNotEmpty() && last() != ' ' -> append(' ')
			}
		}
	}
	return spaced.trim().trim('-', '\'')
}

private fun parseParagraph(paragraph: String): GcideEntry? {
	val headword = headwordPattern.find(paragraph)?.groupValues?.get(1)
		?: entryPattern.find(paragraph)?.groupValues?.get(1)
		?: return null
	val word = cleanInline(headword)
	val key = normalizeKey(word)
	if (word.isEmpty() || key.isEmpty() || key.length > 80) return null
	val definition = definitionOf(paragraph)
	if (definition.isEmpty()) return null
	val pos = posPattern.find(paragraph)?.groupValues?.get(1)
		?.let { cleanInline(it) }
		?.takeIf { it.isNotEmpty() && it.length <= 32 }
	val keys = LinkedHashSet<String>()
	keys += key
	alternatePattern.findAll(paragraph).take(8).forEach { match ->
		val alternate = normalizeKey(cleanInline(match.groupValues[2]))
		if (alternate.isNotEmpty() && alternate.length <= 80) keys += alternate
	}
	return GcideEntry(word = word, pos = pos, definition = definition, keys = keys.toList())
}

private fun definitionOf(paragraph: String): String {
	val lines = mutableListOf<String>()
	var label: String? = null
	sensePattern.findAll(paragraph).forEach { match ->
		val sense = match.groups[1]?.value
		val body = match.groups[2]?.value
		if (sense != null) {
			label = cleanInline(sense)
		} else if (body != null) {
			val text = polish(cleanInline(body))
			if (text.isNotEmpty()) {
				val prefix = label?.takeIf { it.isNotEmpty() }
				lines += if (prefix == null) text else "$prefix $text"
				label = null
			}
		}
	}
	return truncate(lines.joinToString("\n"), MAX_DEFINITION)
}

private fun inflections(word: String): List<String> {
	val variants = LinkedHashSet<String>()
	fun add(value: String) {
		if (value.length >= 2 && value != word) variants += value
	}
	if (word.endsWith("'s")) add(word.dropLast(2))
	if (word.endsWith("ies") && word.length > 4) add(word.dropLast(3) + "y")
	if (word.endsWith("ied") && word.length > 4) add(word.dropLast(3) + "y")
	if (word.endsWith("ing") && word.length > 5) {
		val stem = word.dropLast(3)
		if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) add(stem.dropLast(1))
		add(stem + "e")
		add(stem)
	}
	if (word.endsWith("ed") && word.length > 4) {
		val stem = word.dropLast(2)
		if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) add(stem.dropLast(1))
		add(stem + "e")
		add(stem)
	}
	if (word.endsWith("es") && word.length > 4) add(word.dropLast(2))
	if (word.endsWith("s") && !word.endsWith("ss") && word.length > 3) add(word.dropLast(1))
	if (word.endsWith("ily") && word.length > 4) add(word.dropLast(3) + "y")
	if (word.endsWith("ly") && word.length > 4) add(word.dropLast(2))
	return variants.toList()
}

private fun polish(text: String): String =
	text.replace(" -- ", " — ").replace("--", "—")

private fun truncate(text: String, max: Int): String {
	if (text.length <= max) return text
	val cut = text.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
	return text.substring(0, cut).trimEnd() + "…"
}

private fun cleanInline(raw: String): String {
	val decoded = decodeHtml(tagPattern.replace(decodeGcide(raw), ""))
	return whitespace.replace(decoded, " ").trim()
}

private fun decodeGcide(raw: String): String {
	val hexed = hexEscape.replace(raw) { hexCharacter(it.groupValues[1]) }
	return gcideEntity.replace(hexed) { entities[it.groupValues[1]] ?: "" }
}

private fun decodeHtml(raw: String): String =
	htmlEntity.replace(raw) { match ->
		val numeric = match.groupValues[1]
		when (match.groupValues[2]) {
			"amp" -> "&"
			"lt" -> "<"
			"gt" -> ">"
			"quot" -> "\""
			"apos" -> "'"
			else -> when {
				numeric.startsWith("x", ignoreCase = true) ->
					numeric.drop(1).toIntOrNull(16)?.toChar()?.toString() ?: match.value
				numeric.isNotEmpty() -> numeric.toIntOrNull()?.toChar()?.toString() ?: match.value
				else -> match.value
			}
		}
	}

private fun hexCharacter(hex: String): String = when (hex.lowercase(Locale.US)) {
	"80" -> "Ç"
	"81" -> "ü"
	"82" -> "é"
	"83" -> "â"
	"84" -> "ä"
	"85" -> "à"
	"86" -> "å"
	"87" -> "ç"
	"88" -> "ê"
	"89" -> "ë"
	"8a" -> "è"
	"8b" -> "ï"
	"8c" -> "î"
	"8d" -> "ì"
	"8e" -> "Ä"
	"8f" -> "Å"
	"90" -> "É"
	"91" -> "æ"
	"92" -> "Æ"
	"93" -> "ô"
	"94" -> "ö"
	"95" -> "ò"
	"96" -> "û"
	"97" -> "ù"
	"98" -> "ÿ"
	"99" -> "Ö"
	"9a" -> "Ü"
	"9c" -> "£"
	"a0" -> "á"
	"a1" -> "í"
	"a2" -> "ó"
	"a3" -> "ú"
	"a4" -> "ñ"
	"a5" -> "Ñ"
	"a6" -> "⅔"
	"a7" -> "⅓"
	"a9" -> "˝"
	"ab" -> "½"
	"ac" -> "¼"
	"b5" -> "☞"
	"b8" -> "\""
	"bc" -> "§"
	"bd" -> "\""
	"be" -> "ā"
	"bf" -> "'"
	"c0" -> "n"
	"e7" -> "h"
	"c1" -> "♯"
	"c2" -> "♭"
	"c3" -> "–"
	"c4" -> "―"
	"c5" -> "t"
	"c6" -> "ī"
	"c7" -> "ē"
	"c8" -> "ḍ"
	"c9" -> "ṇ"
	"ca" -> "ṭ"
	"cb" -> "ĕ"
	"cc" -> "ĭ"
	"ce" -> "ŏ"
	"cf" -> "-"
	"d0", "f5" -> "—"
	"d1" -> "Œ"
	"d2" -> "œ"
	"d3" -> "ō"
	"d4" -> "ū"
	"d5" -> "ǒ"
	"d6" -> "ǣ"
	"d7" -> "ōē"
	"dc" -> "ŭ"
	"dd" -> "ă"
	"de" -> "˘"
	"df" -> "ȳ"
	"e0", "e5" -> "a"
	"e1" -> "e"
	"e2" -> "i"
	"e3" -> "o"
	"e4" -> "u"
	"e6" -> "μ"
	"eb" -> "ð"
	"ed" -> "þ"
	"ee" -> "ã"
	"ef" -> "ṅ"
	"f0" -> "ṛ"
	"f4" -> "ȝ"
	"f6" -> "÷"
	"f7" -> "≈"
	"f8" -> "°"
	"fb" -> "√"
	else -> ""
}

private val entities: Map<String, String> = mapOf(
	"br" to " ",
	"?" to "",
	"and" to "and",
	"or" to "or",
	"Cced" to "Ç",
	"cced" to "ç",
	"uum" to "ü",
	"uuml" to "ü",
	"eacute" to "é",
	"Eacute" to "É",
	"acir" to "â",
	"acirc" to "â",
	"aum" to "ä",
	"auml" to "ä",
	"Aum" to "Ä",
	"Auml" to "Ä",
	"agrave" to "à",
	"aring" to "å",
	"Aring" to "Å",
	"ecir" to "ê",
	"ecirc" to "ê",
	"eum" to "ë",
	"euml" to "ë",
	"egrave" to "è",
	"ium" to "ï",
	"iuml" to "ï",
	"icir" to "î",
	"icirc" to "î",
	"igrave" to "ì",
	"ae" to "æ",
	"AE" to "Æ",
	"aelig" to "æ",
	"AElig" to "Æ",
	"ocir" to "ô",
	"ocirc" to "ô",
	"oum" to "ö",
	"ouml" to "ö",
	"Oum" to "Ö",
	"Ouml" to "Ö",
	"ograve" to "ò",
	"ucir" to "û",
	"ucirc" to "û",
	"ugrave" to "ù",
	"yum" to "ÿ",
	"yuml" to "ÿ",
	"Uum" to "Ü",
	"Uuml" to "Ü",
	"pound" to "£",
	"aacute" to "á",
	"iacute" to "í",
	"oacute" to "ó",
	"uacute" to "ú",
	"ntil" to "ñ",
	"ntilde" to "ñ",
	"Ntil" to "Ñ",
	"Ntilde" to "Ñ",
	"frac23" to "⅔",
	"frac13" to "⅓",
	"frac12" to "½",
	"frac14" to "¼",
	"sec" to "˝",
	"hand" to "",
	"bprime" to "",
	"prime" to "",
	"rdquo" to "\"",
	"ldquo" to "\"",
	"lsquo" to "'",
	"rsquo" to "'",
	"sect" to "§",
	"amac" to "ā",
	"amacr" to "ā",
	"imac" to "ī",
	"imacr" to "ī",
	"emac" to "ē",
	"emacr" to "ē",
	"omac" to "ō",
	"omacr" to "ō",
	"umac" to "ū",
	"umacr" to "ū",
	"ymac" to "ȳ",
	"ymacr" to "ȳ",
	"aemac" to "ǣ",
	"oemac" to "ōē",
	"nsm" to "n",
	"sharp" to "♯",
	"flat" to "♭",
	"th" to "th",
	"dsdot" to "ḍ",
	"nsdot" to "ṇ",
	"tsdot" to "ṭ",
	"rsdot" to "ṛ",
	"ndot" to "ṅ",
	"adot" to "a",
	"ecr" to "ĕ",
	"icr" to "ĭ",
	"ocr" to "ŏ",
	"ucr" to "ŭ",
	"acr" to "ă",
	"ubreve" to "ŭ",
	"abreve" to "ă",
	"oe" to "œ",
	"OE" to "Œ",
	"oelig" to "œ",
	"OElig" to "Œ",
	"ocar" to "ǒ",
	"atil" to "ã",
	"atilde" to "ã",
	"edh" to "ð",
	"thorn" to "þ",
	"yogh" to "ȝ",
	"mdash" to "—",
	"divide" to "÷",
	"deg" to "°",
	"middot" to "",
	"root" to "√",
	"schwa" to "ə",
	"asl" to "a",
	"esl" to "e",
	"isl" to "i",
	"osl" to "o",
	"usl" to "u",
)
