package app.shosetsu.android.common.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterRangeSelectorTest {
	private data class Chapter(val id: Int, val order: Double)

	@Test
	fun usesCanonicalOrderRatherThanInputOrder() {
		val chapters = listOf(Chapter(3, 3.0), Chapter(1, 1.0), Chapter(2, 2.0))

		val selected = selectChapterRange(chapters, 1, 2) { it.order }

		assertEquals(listOf(1, 2), selected.map { it.id })
	}

	@Test
	fun acceptsReversedEndpoints() {
		val chapters = (1..5).map { Chapter(it, it.toDouble()) }

		val selected = selectChapterRange(chapters, 4, 2) { it.order }

		assertEquals(listOf(2, 3, 4), selected.map { it.id })
	}

	@Test
	fun safelyClampsEndpoints() {
		val chapters = (1..3).map { Chapter(it, it.toDouble()) }

		val selected = selectChapterRange(chapters, -5, 20) { it.order }

		assertEquals(listOf(1, 2, 3), selected.map { it.id })
	}
}
