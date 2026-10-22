package app.shosetsu.android.common.utils.tts

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
 * Turns a paragraph into sentence-sized pieces. Kokoro sounds more natural on a
 * sentence than on a whole paragraph, and the player can synthesize the next
 * sentence while the current one is still playing.
 */
object SpeechChunker {
	private const val MAX_CHUNK = 420

	private val abbreviations = setOf(
		"mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "vol", "no", "fig",
	)
	private val bracketCitation = Regex(
		"""(?:\[\s*(?:\d+|\*+)\s*]|［\s*(?:\d+|\*+)\s*］|【\s*(?:\d+|\*+)\s*】|〖\s*(?:\d+|\*+)\s*〗|〔\s*(?:\d+|\*+)\s*〕)"""
	)
	private val bracketMarks = Regex("""[\[\]［］【】〖〗〔〕]""")

	fun chunks(text: String): List<String> {
		val normalized = text
			.replace(bracketCitation, "")
			.replace(bracketMarks, "")
			.replace(Regex("\\s*[—–]\\s*"), ", ")
			.replace('…', '.')
			.replace(Regex("\\s+"), " ")
			.trim()
		if (normalized.isEmpty()) return emptyList()

		val sentences = ArrayList<String>()
		val current = StringBuilder()
		var index = 0
		while (index < normalized.length) {
			val character = normalized[index]
			current.append(character)
			if (character == '.' || character == '!' || character == '?') {
				val punctuation = index
				while (index + 1 < normalized.length && normalized[index + 1] in ".!?") {
					index++
					current.append(normalized[index])
				}
				val next = normalized.getOrNull(index + 1)
				if ((next == null || next.isWhitespace()) && !abbreviationBefore(normalized, punctuation)) {
					addSentence(sentences, current.toString())
					current.clear()
					while (index + 1 < normalized.length && normalized[index + 1].isWhitespace()) index++
				}
			}
			index++
		}
		addSentence(sentences, current.toString())
		return sentences
	}

	private fun addSentence(sentences: MutableList<String>, raw: String) {
		val sentence = raw.trim()
		if (sentence.any { it.isLetterOrDigit() }) sentences += splitLong(sentence)
	}

	private fun abbreviationBefore(text: String, dot: Int): Boolean {
		var start = dot - 1
		while (start >= 0 && text[start].isLetter()) start--
		return text.substring(start + 1, dot).lowercase() in abbreviations
	}

	private fun splitLong(text: String): List<String> {
		if (text.length <= MAX_CHUNK) return listOf(text)
		val parts = ArrayList<String>()
		var rest = text
		while (rest.length > MAX_CHUNK) {
			val window = rest.substring(0, MAX_CHUNK)
			val comma = window.lastIndexOf(',')
			val space = window.lastIndexOf(' ')
			val cut = when {
				comma > 80 -> comma
				space > 80 -> space
				else -> MAX_CHUNK
			}
			val piece = rest.substring(0, cut).trim().trimEnd(',')
			if (piece.any { it.isLetterOrDigit() }) parts += piece
			rest = rest.substring(cut).trim().trimStart(',')
		}
		if (rest.any { it.isLetterOrDigit() }) parts += rest.trim()
		return parts
	}
}
