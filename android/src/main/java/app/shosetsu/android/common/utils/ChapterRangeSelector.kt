package app.shosetsu.android.common.utils

/**
 * Selects an inclusive, one-based chapter range in canonical source order.
 * Reversed endpoints are accepted.
 */
internal fun <T> selectChapterRange(
	chapters: List<T>,
	first: Int,
	last: Int,
	order: (T) -> Double,
): List<T> {
	if (chapters.isEmpty()) return emptyList()
	val sorted = chapters.sortedBy(order)
	val start = minOf(first, last).coerceIn(1, sorted.size)
	val end = maxOf(first, last).coerceIn(1, sorted.size)
	return sorted.subList(start - 1, end)
}
