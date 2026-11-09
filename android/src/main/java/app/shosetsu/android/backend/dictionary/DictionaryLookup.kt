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

private const val MAX_PHRASE_WORDS = 4

private val diacritics = Regex("\\p{Mn}+")

internal fun lookupCandidates(selection: String): List<String> {
	val key = normalizeKey(selection)
	if (key.isEmpty()) return emptyList()
	val words = key.split(' ')
	if (words.size > MAX_PHRASE_WORDS) return emptyList()
	val candidates = LinkedHashSet<String>()
	candidates += key
	if ('-' in key) {
		candidates += key.replace('-', ' ').trim()
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
