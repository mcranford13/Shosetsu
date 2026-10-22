package app.shosetsu.android.providers.database.dao

import app.shosetsu.android.domain.model.database.DBChapterEntity
import app.shosetsu.lib.Novel

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
 * Matches a source refresh to existing rows without assigning one row twice.
 *
 * URL is the primary identity. A unique, unchanged title is a conservative
 * fallback for sources that rewrite chapter URLs. Order is deliberately not an
 * identity: inserting a chapter can renumber every chapter after it.
 */
internal object ChapterReconciler {
	internal data class Match(
		val incoming: Novel.Chapter,
		val existing: DBChapterEntity?,
	)

	internal data class Result(
		val matches: List<Match>,
		val removable: List<DBChapterEntity>,
	)

	fun match(
		existing: List<DBChapterEntity>,
		incoming: List<Novel.Chapter>,
	): Result {
		val matches = arrayOfNulls<DBChapterEntity>(incoming.size)
		val usedExisting = HashSet<Int>()

		incoming.forEachIndexed { incomingIndex, chapter ->
			val existingIndex = existing.indices.firstOrNull { index ->
				index !in usedExisting && existing[index].url == chapter.link
			}
			if (existingIndex != null) {
				usedExisting += existingIndex
				matches[incomingIndex] = existing[existingIndex]
			}
		}

		incoming.forEachIndexed { incomingIndex, chapter ->
			if (matches[incomingIndex] != null) return@forEachIndexed
			val normalizedTitle = normalizeTitle(chapter.title)
			if (normalizedTitle.isEmpty()) return@forEachIndexed
			val candidates = existing.indices.filter { existingIndex ->
				existingIndex !in usedExisting &&
					normalizeTitle(existing[existingIndex].title) == normalizedTitle
			}
			if (candidates.size == 1) {
				val existingIndex = candidates.single()
				usedExisting += existingIndex
				matches[incomingIndex] = existing[existingIndex]
			}
		}

		return Result(
			matches = incoming.mapIndexed { index, chapter -> Match(chapter, matches[index]) },
			removable = existing.filterIndexed { index, chapter ->
				index !in usedExisting && !chapter.isSaved && !chapter.bookmarked
			},
		)
	}

	private fun normalizeTitle(value: String): String =
		value.trim().replace(Regex("\\s+"), " ").lowercase()
}
