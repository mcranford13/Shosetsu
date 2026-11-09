package app.shosetsu.android.backend.dictionary

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

/**
 * One English Wiktionary entry reduced to the text shown in the reader.
 * [key] is the accent-folded lookup key.
 */
internal data class WiktionaryRecord(
	val word: String,
	val pos: String?,
	val definition: String,
	val key: String,
)

private const val MAX_DEFINITION = 700
private const val MAX_GLOSSES = 3
private const val MAX_WORD_CHARS = 120
private const val MAX_KEY_CHARS = 100

private val whitespace = Regex("\\s+")

/**
 * Glosses that only point at another entry. Dropping them lets the lookup
 * fall through to the lemma, so "running" can still find "run".
 */
private val inflectionGloss = Regex(
	"""(?i)^(?:\([^)]{1,48}\)\s*)?(?:(?:archaic|dated|rare|nonstandard|informal|slang|dialectal|chiefly|also|now|often|sometimes)\s+)*(?:plural|singular|comparative|superlative|simple past(?: tense)?(?: and past participle)?|past tense(?: and past participle)?|past participle|present participle(?: and gerund)?|gerund|third-person singular(?: simple present(?: indicative)?)?|(?:alternative|obsolete|archaic)\s+(?:form|spelling)|misspelling|abbreviation|initialism|acronym|clipping|eye dialect) of\b.*"""
)

private val skipPos = setOf(
	"character",
	"symbol",
	"punctuation",
	"prefix",
	"suffix",
	"infix",
	"interfix",
	"circumfix",
	"affix",
	"romanization",
)

private val posLabels = mapOf(
	"adj" to "adjective",
	"adv" to "adverb",
	"prep" to "preposition",
	"conj" to "conjunction",
	"intj" to "interjection",
	"pron" to "pronoun",
	"det" to "determiner",
	"num" to "numeral",
	"postp" to "postposition",
)

private val formTags = setOf("form-of", "alt-of")

/**
 * Reads one wiktextract JSON object and keeps the word, part of speech, and
 * a few glosses. Unused fields, including etymology and translations, are
 * skipped without being turned into objects.
 */
internal fun parseWiktionaryLine(line: String): WiktionaryRecord? {
	if (line.isEmpty()) return null
	return try {
		parseLine(line)
	} catch (_: Exception) {
		null
	}
}

private fun parseLine(line: String): WiktionaryRecord? {
	val cursor = JsonCursor(line)
	cursor.skipWs()
	if (cursor.peek() != '{') return null
	var word: String? = null
	var pos: String? = null
	var lang: String? = null
	val glosses = LinkedHashSet<String>()
	cursor.parseObject { name ->
		when (name) {
			"word" -> word = cursor.parseStringOrNull()
			"pos" -> pos = cursor.parseStringOrNull()
			"lang_code" -> lang = cursor.parseStringOrNull()
			"senses" -> cursor.readSenses(glosses)
			else -> cursor.skipValue()
		}
	}
	if (lang != "en" || pos in skipPos) return null
	val display = word?.trim().orEmpty()
	if (display.isEmpty() || display.length > MAX_WORD_CHARS) return null
	val key = normalizeKey(display)
	if (key.isEmpty() || key.length > MAX_KEY_CHARS || glosses.isEmpty()) return null
	val definition = truncate(glosses.take(MAX_GLOSSES).joinToString("\n"), MAX_DEFINITION)
	if (definition.isEmpty()) return null
	return WiktionaryRecord(
		word = display,
		pos = displayPos(pos),
		definition = definition,
		key = key,
	)
}

private fun displayPos(pos: String?): String? {
	if (pos.isNullOrBlank()) return null
	return posLabels[pos] ?: pos.takeIf { it.length <= 24 }
}

private fun cleanGloss(raw: String): String = whitespace.replace(raw, " ").trim()

private fun truncate(text: String, max: Int): String {
	if (text.length <= max) return text
	val cut = text.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
	return text.substring(0, cut).trimEnd() + "…"
}

private class JsonCursor(private val text: String) {
	private var i = if (text.startsWith("\uFEFF")) 1 else 0

	fun peek(): Char = if (i < text.length) text[i] else '\u0000'

	fun skipWs() {
		while (i < text.length) {
			when (text[i]) {
				' ', '\n', '\r', '\t' -> i++
				else -> return
			}
		}
	}

	fun parseObject(onField: (String) -> Unit) {
		expect('{')
		skipWs()
		if (peek() == '}') {
			i++
			return
		}
		while (true) {
			val name = parseString()
			expect(':')
			onField(name)
			skipWs()
			when (peek()) {
				',' -> i++
				'}' -> {
					i++
					return
				}
				else -> error("Broken dictionary entry")
			}
		}
	}

	fun readSenses(out: LinkedHashSet<String>) {
		skipWs()
		if (peek() != '[') {
			skipValue()
			return
		}
		expect('[')
		skipWs()
		if (peek() == ']') {
			i++
			return
		}
		while (true) {
			if (out.size >= MAX_GLOSSES) skipValue() else readSense(out)
			skipWs()
			when (peek()) {
				',' -> i++
				']' -> {
					i++
					return
				}
				else -> error("Broken dictionary entry")
			}
		}
	}

	fun skipValue() {
		skipWs()
		when (peek()) {
			'"' -> skipString()
			'{' -> skipContainer('{', '}')
			'[' -> skipContainer('[', ']')
			't' -> skipLiteral("true")
			'f' -> skipLiteral("false")
			'n' -> skipLiteral("null")
			else -> skipNumber()
		}
	}

	fun parseStringOrNull(): String? {
		skipWs()
		if (peek() == 'n') {
			skipLiteral("null")
			return null
		}
		return parseString()
	}

	private fun readSense(out: LinkedHashSet<String>) {
		skipWs()
		if (peek() != '{') {
			skipValue()
			return
		}
		val glosses = ArrayList<String>(4)
		var formOf = false
		parseObject { name ->
			when (name) {
				"glosses" -> readGlosses(glosses)
				"tags" -> if (stringArrayHas(formTags)) formOf = true
				"form_of", "alt_of" -> {
					formOf = true
					skipValue()
				}
				else -> skipValue()
			}
		}
		if (formOf) return
		for (gloss in glosses) {
			val cleaned = cleanGloss(gloss)
			if (cleaned.isEmpty() || inflectionGloss.matches(cleaned)) continue
			out += cleaned
			if (out.size >= MAX_GLOSSES) return
		}
	}

	private fun readGlosses(out: MutableList<String>) {
		skipWs()
		if (peek() != '[') {
			skipValue()
			return
		}
		expect('[')
		skipWs()
		if (peek() == ']') {
			i++
			return
		}
		while (out.size < MAX_GLOSSES + 2) {
			skipWs()
			if (peek() == '"') out += parseString() else skipValue()
			skipWs()
			when (peek()) {
				',' -> i++
				']' -> {
					i++
					return
				}
				else -> error("Broken dictionary entry")
			}
		}
		finishArray()
	}

	private fun stringArrayHas(needles: Set<String>): Boolean {
		skipWs()
		if (peek() != '[') {
			skipValue()
			return false
		}
		expect('[')
		var found = false
		skipWs()
		if (peek() == ']') {
			i++
			return false
		}
		while (true) {
			skipWs()
			if (peek() == '"') {
				if (parseString() in needles) found = true
			} else {
				skipValue()
			}
			skipWs()
			when (peek()) {
				',' -> i++
				']' -> {
					i++
					return found
				}
				else -> error("Broken dictionary entry")
			}
		}
	}

	private fun finishArray() {
		var depth = 1
		while (i < text.length && depth > 0) {
			when (text[i++]) {
				'"' -> {
					i--
					skipString()
				}
				'[' -> depth++
				']' -> depth--
			}
		}
		if (depth != 0) error("Broken dictionary entry")
	}

	private fun skipContainer(open: Char, close: Char) {
		expect(open)
		var depth = 1
		while (i < text.length && depth > 0) {
			when (text[i++]) {
				'"' -> {
					i--
					skipString()
				}
				open -> depth++
				close -> depth--
			}
		}
		if (depth != 0) error("Broken dictionary entry")
	}

	private fun skipLiteral(literal: String) {
		if (!text.regionMatches(i, literal, 0, literal.length)) error("Broken dictionary entry")
		i += literal.length
	}

	private fun skipNumber() {
		if (peek() == '-') i++
		val start = i
		while (i < text.length && text[i].isJsonNumberChar()) i++
		if (i == start) error("Broken dictionary entry")
	}

	private fun parseString(): String {
		skipWs()
		if (peek() != '"') error("Broken dictionary entry")
		val start = ++i
		while (i < text.length) {
			when (text[i]) {
				'\\' -> return unescape(start)
				'"' -> {
					val value = text.substring(start, i)
					i++
					return value
				}
				else -> i++
			}
		}
		error("Broken dictionary entry")
	}

	private fun unescape(contentStart: Int): String {
		val sb = StringBuilder()
		sb.append(text, contentStart, i)
		while (i < text.length) {
			when (val c = text[i++]) {
				'\\' -> sb.append(readEscape())
				'"' -> return sb.toString()
				else -> sb.append(c)
			}
		}
		error("Broken dictionary entry")
	}

	private fun readEscape(): Char {
		if (i >= text.length) error("Broken dictionary entry")
		return when (val c = text[i++]) {
			'"', '\\', '/' -> c
			'b' -> '\b'
			'f' -> '\u000C'
			'n' -> '\n'
			'r' -> '\r'
			't' -> '\t'
			'u' -> {
				if (i + 4 > text.length) error("Broken dictionary entry")
				val code = text.substring(i, i + 4).toIntOrNull(16) ?: error("Broken dictionary entry")
				i += 4
				code.toChar()
			}
			else -> c
		}
	}

	private fun skipString() {
		skipWs()
		if (peek() != '"') error("Broken dictionary entry")
		i++
		while (i < text.length) {
			when (text[i++]) {
				'\\' -> if (i < text.length) i++
				'"' -> return
			}
		}
		error("Broken dictionary entry")
	}

	private fun expect(c: Char) {
		skipWs()
		if (peek() != c) error("Broken dictionary entry")
		i++
	}
}

private fun Char.isJsonNumberChar(): Boolean =
	this == '-' || this == '+' || this == '.' || this == 'e' || this == 'E' || this in '0'..'9'
