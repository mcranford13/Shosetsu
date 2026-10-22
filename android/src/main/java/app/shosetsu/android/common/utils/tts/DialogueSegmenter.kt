package app.shosetsu.android.common.utils.tts

import app.shosetsu.android.view.uimodels.model.reader.CharacterGender
import app.shosetsu.android.view.uimodels.model.reader.SegmentSource
import app.shosetsu.android.view.uimodels.model.reader.SpeechSegment
import kotlin.math.abs

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
 * Splits chapter text into narration and quoted dialogue, and guesses who is speaking.
 *
 * Quotes use straight or curly doubles, single quotes, corner brackets, or guillemets.
 * A name in the same sentence as a speech verb ("Alice looked up and said", "whispered Clara")
 * claims the quote. "he said" keeps the previous named speaker. A quote with no tag keeps
 * that speaker when only a short beat separates them.
 */
object DialogueSegmenter {
	const val NARRATOR = "narrator"
	const val UNKNOWN = "unknown"
	const val NARRATOR_NAME = "Narrator"
	const val UNKNOWN_NAME = "Unknown"

	private const val WINDOW = 140
	private const val INHERIT_GAP = 80

	private val verbs = listOf(
		"said", "says", "asked", "asks", "replied", "replies", "whispered", "shouted",
		"yelled", "muttered", "exclaimed", "cried", "answered", "continued", "added",
		"told", "called", "murmured", "hissed", "snapped", "demanded", "gasped",
		"sighed", "laughed", "chuckled", "growled", "pleaded", "begged", "offered",
		"suggested", "agreed", "admitted", "announced", "declared", "remarked",
		"interrupted", "repeated", "promised", "warned", "ordered", "screamed",
		"stammered", "breathed", "thought", "wondered", "inquired", "responded",
		"began", "explained", "insisted", "protested", "argued", "complained",
		"grumbled", "retorted", "countered", "sobbed", "mumbled", "drawled",
	)

	private val verbPattern = verbs.joinToString("|") { verb ->
		val first = verb.first()
		"[${first.uppercase()}${first.lowercase()}]${verb.drop(1)}"
	}

	private val namePattern = """[A-Z][\p{L}'’.-]*(?:\s+[A-Z][\p{L}'’.-]*){0,2}"""
	private val nameRegex = Regex(namePattern)
	private val verbRegex = Regex("""\b(?:$verbPattern)\b""")
	private val colonSpeaker = Regex("""($namePattern)\s*:\s*$""")
	private val pronounTag = Regex(
		"""\b(?:$verbPattern)\s+(?:he|she|they|him|her|them)\b|\b(?:he|she|they)\s+(?:$verbPattern)\b""",
		RegexOption.IGNORE_CASE,
	)

	private val quoteClosers = mapOf(
		'"' to '"',
		'“' to '”',
		'「' to '」',
		'『' to '』',
		'«' to '»',
		'‘' to '’',
		'\'' to '\'',
		'[' to ']',
	)

	private val pronouns = setOf(
		"i", "you", "he", "she", "they", "we", "it", "him", "her", "them", "his",
		"me", "my", "your", "yours", "our", "ours", "their", "theirs",
	)

	private val stopwords = setOf(
		"a", "an", "the", "and", "but", "or", "then", "when", "after", "before",
		"while", "as", "if", "so", "that", "this", "there", "here", "now", "still",
		"just", "suddenly", "meanwhile", "however", "although", "though", "because",
		"until", "once", "chapter", "volume", "part", "prologue", "epilogue",
		"preface", "introduction", "note", "notes",
	)

	private data class Mark(val elementId: String, val start: Int, val end: Int)
	private data class Speaker(
		val key: String,
		val name: String,
		val gender: CharacterGender = CharacterGender.UNKNOWN,
	)

	/**
	 * Stable key for a detected name. "Alice" and " alice " share a key.
	 * Reserved roles stay distinct from a character who happens to use that name.
	 */
	fun characterKey(displayName: String): String {
		val normalized = displayName.trim().replace(Regex("\\s+"), " ").lowercase()
		if (normalized == NARRATOR || normalized == UNKNOWN) return "name:$normalized"
		val words = normalized.split(' ').toMutableList()
		if (words.size >= 2 && words[0] == "young" && words[1].trimEnd('.') == "master") {
			words.removeAt(0)
			words.removeAt(0)
		}
		while (words.size > 1 && words.first().trimEnd('.') in identityTitles) {
			words.removeAt(0)
		}
		return words.joinToString(" ")
	}

	fun segment(pieces: List<SegmentSource>): List<SpeechSegment> {
		val builder = StringBuilder()
		val marks = ArrayList<Mark>()
		for (piece in pieces) {
			if (piece.text.isEmpty()) continue
			val start = builder.length
			builder.append(piece.text)
			if (builder.isEmpty() || builder.last() != '\n') builder.append('\n')
			marks += Mark(piece.elementId, start, builder.length)
		}
		if (marks.isEmpty()) return emptyList()
		val full = builder.toString()

		val regions = ArrayList<Region>()
		var cursor = 0
		var previous: Speaker? = null
		var previousQuoteEnd = -1
		for (quote in findQuotes(full)) {
			if (cursor < quote.start) {
				regions += Region(cursor, quote.start, false, NARRATOR, NARRATOR_NAME)
			}
			val speaker = attribute(full, quote.start, quote.end, previous, previousQuoteEnd)
			regions += Region(
				quote.innerStart,
				quote.innerEnd,
				true,
				speaker.key,
				speaker.name,
				speaker.gender,
			)
			previous = speaker
			previousQuoteEnd = quote.end
			cursor = quote.end
		}
		if (cursor < full.length) {
			regions += Region(cursor, full.length, false, NARRATOR, NARRATOR_NAME)
		}

		val inferredGenders = regions
			.filter { it.gender != CharacterGender.UNKNOWN }
			.associate { it.key to it.gender }
		val segments = ArrayList<SpeechSegment>()
		for (region in regions) {
			for (slice in slices(full, marks, region.start, region.end)) {
				segments += SpeechSegment(
					id = "${slice.elementId}#${segments.size}",
					elementId = slice.elementId,
					text = slice.text,
					characterKey = region.key,
					displayName = region.name,
					dialogue = region.dialogue,
					gender = inferredGenders[region.key] ?: region.gender,
				)
			}
		}
		return segments
	}

	private data class Region(
		val start: Int,
		val end: Int,
		val dialogue: Boolean,
		val key: String,
		val name: String,
		val gender: CharacterGender = CharacterGender.UNKNOWN,
	)

	private data class RawPiece(val elementId: String, val text: String)
	private data class Quote(val start: Int, val end: Int, val innerStart: Int, val innerEnd: Int)

	private fun findQuotes(text: String): List<Quote> {
		val quotes = ArrayList<Quote>()
		var index = 0
		while (index < text.length) {
			if (isOpeningQuote(text, index)) {
				val close = findClose(text, index + 1, quoteClosers.getValue(text[index]))
				if (close > index + 1 && isUsableQuote(text, index, close)) {
					quotes += Quote(index, close + 1, index + 1, close)
					index = close + 1
					continue
				}
			}
			index++
		}
		return quotes
	}

	private fun isOpeningQuote(text: String, index: Int): Boolean {
		val mark = text[index]
		if (mark !in quoteClosers) return false
		if (mark == '[' && (text.getOrNull(index - 1) == '[' || text.getOrNull(index + 1) == '[')) {
			return false
		}
		if (mark == '\'' || mark == '’') {
			val previous = text.getOrNull(index - 1)
			if (previous != null && previous.isLetter()) return false
			val next = text.getOrNull(index + 1) ?: return false
			if (next.isWhitespace()) return false
		}
		return true
	}

	private fun isUsableQuote(text: String, open: Int, close: Int): Boolean {
		if (text[open] != '[') return true
		val inner = text.substring(open + 1, close)
		return inner.length <= MAX_BRACKET_SPEECH &&
			'\n' !in inner &&
			'[' !in inner &&
			']' !in inner &&
			inner.any { it.isLetter() }
	}

	private fun findClose(text: String, from: Int, close: Char): Int {
		var index = from
		while (index < text.length) {
			val mark = text[index]
			val matches = mark == close ||
				(close == '\'' && mark == '’') ||
				(close == '’' && mark == '\'')
			if (matches && !isInternalApostrophe(text, index)) return index
			index++
		}
		return -1
	}

	private fun isInternalApostrophe(text: String, index: Int): Boolean {
		val previous = text.getOrNull(index - 1)
		val next = text.getOrNull(index + 1)
		return previous != null && next != null && previous.isLetter() && next.isLetter()
	}

	private fun attribute(
		full: String,
		quoteStart: Int,
		quoteEnd: Int,
		previous: Speaker?,
		previousQuoteEnd: Int,
	): Speaker {
		val beforeStart = (quoteStart - WINDOW).coerceAtLeast(0)
		val afterEnd = (quoteEnd + WINDOW).coerceAtMost(full.length)
		val before = full.substring(beforeStart, quoteStart)
		val after = full.substring(quoteEnd, afterEnd)
		val tagWindow = before.takeLast(48) + " " + after.take(48)
		val pronounGender = if (pronounTag.containsMatchIn(tagWindow)) {
			genderFromPronoun(tagWindow)
		} else {
			CharacterGender.UNKNOWN
		}
		val named = namesNearVerb(before, beforeStart, quoteStart) +
			namesNearVerb(after, quoteEnd, quoteEnd)
		named.minByOrNull { it.first }?.let {
			val titleGender = genderFromTitle(it.second)
			return Speaker(
				characterKey(it.second),
				it.second,
				if (titleGender == CharacterGender.UNKNOWN) pronounGender else titleGender,
			)
		}

		colonSpeaker.find(before)?.let { match ->
			usableName(match.groupValues[1])?.let {
				return Speaker(characterKey(it), it, genderFromTitle(it))
			}
		}

		val earlier = previous
		if (pronounTag.containsMatchIn(tagWindow) && earlier != null && earlier.isNamed()) {
			val gender = genderFromPronoun(tagWindow)
			return if (gender == CharacterGender.UNKNOWN) earlier else earlier.copy(gender = gender)
		}

		if (previous != null && previousQuoteEnd >= 0) {
			val gap = full.substring(previousQuoteEnd, quoteStart).trim()
			if (gap.length <= INHERIT_GAP) return previous
		}
		return Speaker(UNKNOWN, UNKNOWN_NAME)
	}

	private fun namesNearVerb(window: String, windowStart: Int, quoteEdge: Int): List<Pair<Int, String>> {
		val found = ArrayList<Pair<Int, String>>()
		for (verb in verbRegex.findAll(window)) {
			val (clauseStart, clauseEnd) = clauseBounds(window, verb.range.first, verb.range.last + 1)
			val beforeVerb = window.substring(clauseStart, verb.range.first)
			val afterVerb = window.substring(verb.range.last + 1, clauseEnd)
			val name = nearestName(afterVerb, fromEnd = false) ?: nearestName(beforeVerb, fromEnd = true)
			if (name != null) {
				found += abs(quoteEdge - (windowStart + verb.range.first)) to name
			}
		}
		return found
	}

	private fun clauseBounds(text: String, verbStart: Int, verbEnd: Int): Pair<Int, Int> {
		val breaks = ".!?\n"
		var start = verbStart
		while (start > 0 && text[start - 1] !in breaks) start--
		var end = verbEnd
		while (end < text.length && text[end] !in breaks) end++
		return start to end
	}

	private fun nearestName(text: String, fromEnd: Boolean): String? {
		val names = nameRegex.findAll(text).mapNotNull { usableName(it.value) }.toList()
		if (names.isEmpty()) return null
		return if (fromEnd) names.last() else names.first()
	}

	private fun usableName(raw: String): String? {
		val cleaned = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', ',', ';', ':')
		val words = cleaned.split(' ').filter { it.isNotBlank() }
		val significant = words.indexOfFirst { word ->
			val token = word.trimEnd('.').lowercase()
			token !in titles && !isPronoun(token) && token !in stopwords
		}
		if (significant < 0) return null
		val from = if (significant > 0 && words[significant - 1].trimEnd('.').lowercase() in titles) {
			significant - 1
		} else {
			significant
		}
		return words.subList(from, words.size).joinToString(" ")
	}

	private val titles = setOf(
		"mr", "mrs", "ms", "miss", "dr", "sir", "lord", "lady", "prof", "professor",
		"master", "saint", "elder", "king", "queen", "prince", "princess", "emperor",
		"empress", "duke", "duchess", "father", "mother", "brother", "sister",
		"patriarch", "matriarch",
	)
	private val identityTitles = titles + setOf("captain", "general", "commander")
	private val femaleTitles = setOf(
		"mrs", "ms", "miss", "lady", "queen", "princess", "empress", "duchess",
		"mother", "sister", "matriarch",
	)
	private val maleTitles = setOf(
		"mr", "sir", "lord", "master", "king", "prince", "emperor", "duke",
		"father", "brother", "patriarch",
	)
	private val femalePronoun = Regex("""\b(?:she|her|hers)\b""", RegexOption.IGNORE_CASE)
	private val malePronoun = Regex("""\b(?:he|him|his)\b""", RegexOption.IGNORE_CASE)

	private fun genderFromTitle(name: String): CharacterGender {
		val words = name.lowercase().split(Regex("\\s+")).map { it.trimEnd('.') }
		return when {
			words.any { it in femaleTitles } -> CharacterGender.FEMALE
			words.any { it in maleTitles } -> CharacterGender.MALE
			else -> CharacterGender.UNKNOWN
		}
	}

	private fun genderFromPronoun(tag: String): CharacterGender = when {
		femalePronoun.containsMatchIn(tag) && !malePronoun.containsMatchIn(tag) -> CharacterGender.FEMALE
		malePronoun.containsMatchIn(tag) && !femalePronoun.containsMatchIn(tag) -> CharacterGender.MALE
		else -> CharacterGender.UNKNOWN
	}

	private fun isPronoun(name: String) = name.trim().trimEnd('.').lowercase() in pronouns

	private fun Speaker.isNamed(): Boolean =
		key != UNKNOWN && key != NARRATOR && !key.startsWith("name:")

	private fun slices(full: String, marks: List<Mark>, start: Int, end: Int): List<RawPiece> {
		if (start >= end) return emptyList()
		val result = ArrayList<RawPiece>()
		for (mark in marks) {
			if (mark.end <= start) continue
			if (mark.start >= end) break
			val from = maxOf(start, mark.start)
			val to = minOf(end, mark.end)
			if (from >= to) continue
			val text = full.substring(from, to).trim()
			if (!text.any { it.isLetterOrDigit() }) continue
			val last = result.lastOrNull()
			if (last != null && last.elementId == mark.elementId) {
				result[result.lastIndex] = last.copy(text = "${last.text} $text".trim())
			} else {
				result += RawPiece(mark.elementId, text)
			}
		}
		return result
	}

	private const val MAX_BRACKET_SPEECH = 400
}
