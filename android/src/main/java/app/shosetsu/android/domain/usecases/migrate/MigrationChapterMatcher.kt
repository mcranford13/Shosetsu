package app.shosetsu.android.domain.usecases.migrate

import app.shosetsu.android.common.enums.ReadingStatus
import app.shosetsu.android.domain.model.local.ChapterEntity
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
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
 * Matches chapters between two copies of a novel by normalized title.
 * Each destination chapter is used once. When several chapters share a title,
 * the one closest in source order is chosen.
 */
object MigrationChapterMatcher {
	private val releaseFormats = listOf(
		"yyyy-MM-dd",
		"yyyy-MM-dd'T'HH:mm:ss",
		"MMM dd, yyyy",
		"MMM d, yyyy",
		"dd/MM/yyyy",
	)

	fun normalizeTitle(title: String): String =
		title.lowercase(Locale.US)
			.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
			.trim()
			.replace(Regex("\\s+"), " ")

	fun match(
		source: List<ChapterEntity>,
		destination: List<ChapterEntity>,
	): List<Pair<ChapterEntity, ChapterEntity>> {
		val used = mutableSetOf<Int>()
		val destinationByTitle = destination
			.filter { it.id != null }
			.groupBy { normalizeTitle(it.title) }
		val pairs = mutableListOf<Pair<ChapterEntity, ChapterEntity>>()
		for (chapter in source.sortedBy { it.order }) {
			if (chapter.id == null) continue
			val key = normalizeTitle(chapter.title)
			if (key.isBlank()) continue
			val candidates = destinationByTitle[key].orEmpty().filter { it.id !in used }
			val best = candidates.minByOrNull { abs(it.order - chapter.order) } ?: continue
			val bestId = best.id ?: continue
			used += bestId
			pairs += chapter to best
		}
		return pairs
	}

	fun rank(status: ReadingStatus): Int = when (status) {
		ReadingStatus.UNREAD -> 0
		ReadingStatus.READING -> 1
		ReadingStatus.READ -> 2
		ReadingStatus.UNKNOWN -> -1
	}

	/**
	 * Progress to write onto the destination chapter.
	 * A library novel only gains progress. A novel that was not in the library
	 * receives the source chapter's status, position, and bookmark.
	 * Returns null when the destination chapter would not change.
	 */
	fun progressToApply(
		source: ChapterEntity,
		destination: ChapterEntity,
		destinationWasBookmarked: Boolean,
	): AppliedProgress? {
		val status: ReadingStatus
		val position: Double
		val bookmarked: Boolean
		if (!destinationWasBookmarked) {
			status = source.readingStatus
			position = source.readingPosition
			bookmarked = source.bookmarked
		} else {
			val sourceRank = rank(source.readingStatus)
			val destinationRank = rank(destination.readingStatus)
			status = if (sourceRank > destinationRank) source.readingStatus else destination.readingStatus
			position = when {
				sourceRank > destinationRank -> source.readingPosition
				sourceRank == destinationRank ->
					maxOf(source.readingPosition, destination.readingPosition)
				else -> destination.readingPosition
			}
			bookmarked = destination.bookmarked || source.bookmarked
		}
		if (
			status == destination.readingStatus &&
			position == destination.readingPosition &&
			bookmarked == destination.bookmarked
		) {
			return null
		}
		return AppliedProgress(status, position, bookmarked)
	}

	fun latestChapter(chapters: List<ChapterEntity>): ChapterEntity? {
		if (chapters.isEmpty()) return null
		val dated = chapters.mapNotNull { chapter ->
			parseReleaseMillis(chapter.releaseDate)?.let { chapter to it }
		}
		if (dated.isNotEmpty()) return dated.maxBy { it.second }.first
		return chapters.maxByOrNull { it.order }
	}

	fun parseReleaseMillis(raw: String): Long? {
		val text = raw.trim()
		if (text.isEmpty()) return null
		text.toLongOrNull()?.let { value ->
			if (value <= 0L) return null
			return if (value < 10_000_000_000L) value * 1000 else value
		}
		for (pattern in releaseFormats) {
			val format = SimpleDateFormat(pattern, Locale.US)
			format.isLenient = false
			val position = ParsePosition(0)
			val parsed = format.parse(text, position) ?: continue
			if (position.index == text.length) return parsed.time
		}
		return null
	}
}

data class AppliedProgress(
	val status: ReadingStatus,
	val position: Double,
	val bookmarked: Boolean,
)
