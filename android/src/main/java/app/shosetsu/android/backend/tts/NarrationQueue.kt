package app.shosetsu.android.backend.tts

import app.shosetsu.android.common.utils.tts.DialogueSegmenter
import app.shosetsu.android.common.utils.tts.SpeechChunker
import app.shosetsu.android.view.uimodels.model.reader.LazyTTSText
import app.shosetsu.android.view.uimodels.model.reader.SegmentSource
import app.shosetsu.android.view.uimodels.model.reader.SpeechSegment
import org.jsoup.Jsoup

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
 * Turns chapter HTML into the same speech segments the reader highlights.
 */
fun segmentsFromChapterHtml(html: String, dedupTitles: Boolean): List<SpeechSegment> {
	val document = Jsoup.parse(html)
	if (dedupTitles) {
		val titles = document.select("h1")
		if (titles.size > 1) {
			for (title in titles.subList(1, titles.size).reversed()) {
				title.remove()
			}
		}
	}
	val elements = document.body().select("*:not(:has(*)):not(br)")
	val sources = ArrayList<SegmentSource>(elements.size)
	elements.forEach { element ->
		val lazy = LazyTTSText(element)
		sources += SegmentSource(lazy.id, lazy.text)
	}
	return DialogueSegmenter.segment(sources)
}

/**
 * Splits segments into the sentence queue. Speed stored here is the reader's
 * base speed. The playback controller applies the session multiplier at play time.
 */
suspend fun buildNarrationQueue(
	segments: List<SpeechSegment>,
	startSegmentIndex: Int,
	startChunkIndex: Int,
	speed: Float,
	pitch: Float,
	backend: String,
	linePauseMs: Long,
	paragraphPauseMs: Long,
	packId: suspend (SpeechSegment) -> String,
): List<NarrationQueueItem> {
	if (segments.isEmpty()) return emptyList()
	val start = startSegmentIndex.coerceIn(0, segments.lastIndex)
	val items = ArrayList<NarrationQueueItem>()
	for (index in start until segments.size) {
		val segment = segments[index]
		val next = segments.getOrNull(index + 1)
		val chunks = SpeechChunker.chunks(segment.text)
		if (chunks.isEmpty()) continue
		val firstChunk = if (index == start) {
			startChunkIndex.coerceIn(0, chunks.lastIndex)
		} else {
			0
		}
		val voice = packId(segment)
		for (chunkIndex in firstChunk until chunks.size) {
			val pauseAfter = if (chunkIndex < chunks.lastIndex) {
				linePauseMs
			} else if (next != null && next.elementId != segment.elementId) {
				paragraphPauseMs
			} else {
				linePauseMs
			}
			items += NarrationQueueItem(
				segmentId = segment.id,
				elementId = segment.elementId,
				speaker = segment.displayName,
				request = SpeechRequest(chunks[chunkIndex], speed, pitch, voice),
				backend = backend,
				pauseAfterMs = pauseAfter.coerceAtLeast(0),
			)
		}
	}
	return items
}
