package app.shosetsu.android.backend.tts

import android.content.Context
import app.shosetsu.android.R
import app.shosetsu.android.common.SettingKey
import app.shosetsu.android.common.utils.asHtml
import app.shosetsu.android.common.utils.tts.DialogueSegmenter
import app.shosetsu.android.domain.repository.base.INovelsRepository
import app.shosetsu.android.domain.repository.base.ISettingsRepository
import app.shosetsu.android.domain.usecases.NovelVoiceCastUseCase
import app.shosetsu.android.domain.usecases.RecordChapterIsReadingUseCase
import app.shosetsu.android.domain.usecases.get.GetChapterPassageUseCase
import app.shosetsu.android.domain.usecases.get.GetExtensionUseCase
import app.shosetsu.android.domain.usecases.get.GetReaderChaptersUseCase
import app.shosetsu.android.view.uimodels.model.reader.ReaderUIItem.ReaderChapterUI
import app.shosetsu.lib.Novel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Builds and starts the narration plan for the chapter before or after the one
 * currently playing. Used when the reader is not open.
 */
class NarrationChapterPlayer(
	private val context: Context,
	private val novels: INovelsRepository,
	private val chapters: GetReaderChaptersUseCase,
	private val passages: GetChapterPassageUseCase,
	private val extensions: GetExtensionUseCase,
	private val settings: ISettingsRepository,
	private val voiceCast: NovelVoiceCastUseCase,
	private val reading: RecordChapterIsReadingUseCase,
	private val controller: NarrationPlaybackController,
) {
	private val gate = Mutex()

	suspend fun playNextIfEnabled(): Boolean {
		if (!settings.getBoolean(SettingKey.ReaderNextChapter)) return false
		val announce = settings.getBoolean(SettingKey.ReaderNextChapterAlert)
		return playRelative(1, announce)
	}

	suspend fun playRelative(delta: Int, announce: Boolean): Boolean {
		if (delta == 0) return false
		return try {
			gate.withLock { loadRelative(delta, announce) }
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			false
		}
	}

	private suspend fun loadRelative(delta: Int, announce: Boolean): Boolean {
		val novelId = controller.novelId
		val chapterId = controller.chapterId
		if (novelId < 0 || chapterId < 0) return false
		val chapterItems = chapters(novelId).first()
		val index = chapterItems.indexOfFirst { it.id == chapterId }
		if (index < 0) return false
		val target = chapterItems.getOrNull(index + delta) ?: return false
		val plan = planFor(novelId, target, announce && delta > 0) ?: return false
		controller.submit(plan)
		try {
			reading(target)
		} catch (_: Exception) {
		}
		return true
	}

	private suspend fun planFor(
		novelId: Int,
		chapter: ReaderChapterUI,
		announce: Boolean,
	): NarrationPlan? {
		val novel = novels.getNovel(novelId) ?: return null
		val extension = extensions(novel.extensionID) ?: return null
		val bytes = passages(chapter) ?: return null
		var html = bytes.decodeToString()
		if (extension.chapterType == Novel.ChapterType.STRING) {
			html = asHtml(html, chapter.title)
		}
		val segments = segmentsFromChapterHtml(
			html,
			dedupTitles = settings.getBoolean(SettingKey.ReaderDeDupChapterTitle),
		)
		if (segments.isEmpty()) return null
		val speed = settings.getFloat(SettingKey.ReaderSpeed) / 10f
		val pitch = settings.getFloat(SettingKey.ReaderPitch) / 10f
		val backend = settings.getString(SettingKey.ReaderTtsBackend)
		val linePause = settings.getInt(SettingKey.ReaderTtsLinePause).coerceAtLeast(0).toLong()
		val paragraphPause = settings.getInt(SettingKey.ReaderTtsParagraphPause).coerceAtLeast(0).toLong()
		val voices = HashMap<String, String>()
		val items = buildNarrationQueue(
			segments = segments,
			startSegmentIndex = 0,
			startChunkIndex = 0,
			speed = speed,
			pitch = pitch,
			backend = backend,
			linePauseMs = linePause,
			paragraphPauseMs = paragraphPause,
			packId = { segment ->
				voices.getOrPut(segment.characterKey) {
					voiceCast.resolve(
						novelId,
						segment.characterKey,
						segment.displayName,
						segment.gender,
					).voicePackId
				}
			},
		).toMutableList()
		if (items.isEmpty()) return null
		if (announce) {
			val narrator = voiceCast.resolve(
				novelId,
				DialogueSegmenter.NARRATOR,
				DialogueSegmenter.NARRATOR_NAME,
			)
			items.add(
				0,
				NarrationQueueItem(
					segmentId = CHAPTER_ALERT_ID,
					elementId = "",
					speaker = DialogueSegmenter.NARRATOR_NAME,
					request = SpeechRequest(
						text = context.getString(R.string.reader_tts_next_chapter),
						speed = speed,
						pitch = pitch,
						packId = narrator.voicePackId,
					),
					backend = backend,
					pauseAfterMs = linePause,
				),
			)
		}
		return NarrationPlan(
			chapterId = chapter.id,
			novelId = novelId,
			title = chapter.title,
			items = items,
			systemEngine = settings.getString(SettingKey.ReaderEngine),
			systemLanguage = settings.getString(SettingKey.ReaderLanguage),
			systemVoice = settings.getString(SettingKey.ReaderVoice),
		)
	}

	private companion object {
		const val CHAPTER_ALERT_ID = "narration-chapter-alert"
	}
}
