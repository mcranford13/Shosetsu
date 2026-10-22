package app.shosetsu.android.providers.database.dao

import app.shosetsu.android.common.enums.ReadingStatus
import app.shosetsu.android.domain.model.database.DBChapterEntity
import app.shosetsu.lib.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChapterReconcilerTest {
	@Test
	fun insertedChapterDoesNotInheritProgressFromItsOrder() {
		val first = entity(1, "/one", "One", 1.0, ReadingStatus.READ)
		val second = entity(2, "/two", "Two", 2.0, ReadingStatus.READ)
		val incoming = listOf(
			chapter("/new", "New", 1.0),
			chapter("/one", "One", 2.0),
			chapter("/two", "Two", 3.0),
		)

		val result = ChapterReconciler.match(listOf(first, second), incoming)

		assertNull(result.matches[0].existing)
		assertSame(first, result.matches[1].existing)
		assertSame(second, result.matches[2].existing)
		assertEquals(emptyList<DBChapterEntity>(), result.removable)
	}

	@Test
	fun unchangedUrlKeepsExistingRowWhenMetadataChanges() {
		val existing = entity(1, "/one", "Old title", 1.0, ReadingStatus.READ)

		val result = ChapterReconciler.match(
			listOf(existing),
			listOf(chapter("/one", "New title", 5.0)),
		)

		assertSame(existing, result.matches.single().existing)
	}

	@Test
	fun uniqueTitleCanPreserveIdentityWhenUrlChanges() {
		val existing = entity(1, "/old", "Chapter One", 1.0, ReadingStatus.READ)

		val result = ChapterReconciler.match(
			listOf(existing),
			listOf(chapter("/new", " chapter  one ", 2.0)),
		)

		assertSame(existing, result.matches.single().existing)
	}

	@Test
	fun removedOrdinaryChapterIsRemovable() {
		val removed = entity(1, "/old", "Old", 1.0)

		val result = ChapterReconciler.match(listOf(removed), emptyList())

		assertEquals(listOf(removed), result.removable)
	}

	@Test
	fun removedSavedOrBookmarkedChapterIsRetained() {
		val saved = entity(1, "/saved", "Saved", 1.0).copy(isSaved = true)
		val bookmarked = entity(2, "/bookmarked", "Bookmarked", 2.0).copy(bookmarked = true)

		val result = ChapterReconciler.match(listOf(saved, bookmarked), emptyList())

		assertEquals(emptyList<DBChapterEntity>(), result.removable)
	}

	private fun entity(
		id: Int,
		url: String,
		title: String,
		order: Double,
		status: ReadingStatus = ReadingStatus.UNREAD,
	) = DBChapterEntity(
		id = id,
		url = url,
		novelID = 10,
		extensionID = 20,
		title = title,
		releaseDate = "",
		order = order,
		readingPosition = if (status == ReadingStatus.READ) 1.0 else 0.0,
		readingStatus = status,
	)

	private fun chapter(url: String, title: String, order: Double) = Novel.Chapter(
		title = title,
		link = url,
		release = "",
		order = order,
	)
}
