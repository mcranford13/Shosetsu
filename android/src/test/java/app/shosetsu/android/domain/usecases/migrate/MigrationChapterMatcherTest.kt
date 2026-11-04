package app.shosetsu.android.domain.usecases.migrate

import app.shosetsu.android.common.enums.ReadingStatus
import app.shosetsu.android.domain.model.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationChapterMatcherTest {
	@Test
	fun normalizeTitleDropsPunctuationAndCase() {
		assertEquals(
			"chapter 1 the beginning",
			MigrationChapterMatcher.normalizeTitle("Chapter 1: The Beginning!")
		)
	}

	@Test
	fun matchUsesEachDestinationChapterOnceAndPrefersClosestOrder() {
		val source = listOf(
			chapter(1, "Chapter 1", 1.0),
			chapter(2, "Chapter 1", 10.0),
			chapter(3, "Chapter 2", 2.0),
		)
		val destination = listOf(
			chapter(11, "Chapter 1!", 10.0),
			chapter(12, "chapter 1", 1.0),
			chapter(13, "Chapter 2", 2.0),
		)

		val pairs = MigrationChapterMatcher.match(source, destination)

		assertEquals(listOf(1 to 12, 3 to 13, 2 to 11), pairs.map { it.first.id to it.second.id })
	}

	@Test
	fun blankTitlesAreNotMatched() {
		val pairs = MigrationChapterMatcher.match(
			listOf(chapter(1, "!!!", 1.0)),
			listOf(chapter(2, "...", 1.0)),
		)
		assertTrue(pairs.isEmpty())
	}

	@Test
	fun bookmarkedDestinationDoesNotLoseProgress() {
		val source = chapter(1, "Chapter 1", 1.0).apply {
			readingStatus = ReadingStatus.UNREAD
			readingPosition = 0.1
			bookmarked = false
		}
		val destination = chapter(2, "Chapter 1", 1.0).apply {
			readingStatus = ReadingStatus.READ
			readingPosition = 1.0
			bookmarked = true
		}

		assertNull(MigrationChapterMatcher.progressToApply(source, destination, true))
	}

	@Test
	fun bookmarkedDestinationGainsHigherProgressAndBookmarks() {
		val source = chapter(1, "Chapter 1", 1.0).apply {
			readingStatus = ReadingStatus.READING
			readingPosition = 0.8
			bookmarked = true
		}
		val destination = chapter(2, "Chapter 1", 1.0).apply {
			readingStatus = ReadingStatus.READING
			readingPosition = 0.2
			bookmarked = false
		}

		val applied = MigrationChapterMatcher.progressToApply(source, destination, true)

		assertEquals(ReadingStatus.READING, applied?.status)
		assertEquals(0.8, applied?.position)
		assertEquals(true, applied?.bookmarked)
	}

	@Test
	fun freshDestinationCopiesSourceProgress() {
		val source = chapter(1, "Chapter 1", 1.0).apply {
			readingStatus = ReadingStatus.READ
			readingPosition = 0.5
			bookmarked = true
		}
		val destination = chapter(2, "Chapter 1", 1.0)

		val applied = MigrationChapterMatcher.progressToApply(source, destination, false)

		assertEquals(ReadingStatus.READ, applied?.status)
		assertEquals(0.5, applied?.position)
		assertEquals(true, applied?.bookmarked)
	}

	@Test
	fun latestChapterPrefersAParsedReleaseDate() {
		val older = chapter(1, "Early", 50.0, "2020-01-01")
		val newer = chapter(2, "Later", 1.0, "2024-06-15")
		val undated = chapter(3, "Order only", 99.0, "")

		assertEquals(2, MigrationChapterMatcher.latestChapter(listOf(older, newer, undated))?.id)
		assertEquals(3, MigrationChapterMatcher.latestChapter(listOf(undated))?.id)
	}

	private fun chapter(
		id: Int,
		title: String,
		order: Double,
		releaseDate: String = "",
	) = ChapterEntity(
		id = id,
		url = "https://example.test/$id",
		novelID = 1,
		extensionID = 1,
		title = title,
		releaseDate = releaseDate,
		order = order,
	)
}
