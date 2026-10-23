package app.shosetsu.android.backend.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
 * The speech queue and cursor, without chapter HTML or voice models.
 * The foreground service writes this so playback can resume after the reader
 * or the process is gone.
 */
@Serializable
data class NarrationSnapshot(
	val novelId: Int,
	val chapterId: Int,
	val title: String,
	val cursor: Int,
	val playing: Boolean,
	val systemEngine: String,
	val systemLanguage: String,
	val systemVoice: String,
	val items: List<NarrationSnapshotItem>,
)

@Serializable
data class NarrationSnapshotItem(
	val segmentId: String,
	val elementId: String,
	val speaker: String,
	val text: String,
	val speed: Float,
	val pitch: Float,
	val packId: String,
	val backend: String,
	val pauseAfterMs: Long,
)

class NarrationResumeStore(
	private val file: File,
) {
	private val json = Json { ignoreUnknownKeys = true }

	@Synchronized
	fun read(): NarrationSnapshot? {
		if (!file.isFile || file.length() == 0L) return null
		return try {
			json.decodeFromString<NarrationSnapshot>(file.readText())
		} catch (_: Exception) {
			null
		}
	}

	@Synchronized
	fun write(snapshot: NarrationSnapshot) {
		file.parentFile?.mkdirs()
		val temp = File(file.parentFile, "${file.name}.tmp")
		temp.writeText(json.encodeToString(NarrationSnapshot.serializer(), snapshot))
		if (file.exists() && !file.delete()) {
			file.writeText(temp.readText())
			temp.delete()
			return
		}
		if (!temp.renameTo(file)) {
			file.writeText(temp.readText())
			temp.delete()
		}
	}

	@Synchronized
	fun clear() {
		file.delete()
		File(file.parentFile, "${file.name}.tmp").delete()
	}
}

fun NarrationPlan.toSnapshot(cursor: Int, playing: Boolean): NarrationSnapshot =
	NarrationSnapshot(
		novelId = novelId,
		chapterId = chapterId,
		title = title,
		cursor = cursor.coerceIn(0, items.size),
		playing = playing,
		systemEngine = systemEngine,
		systemLanguage = systemLanguage,
		systemVoice = systemVoice,
		items = items.map { item ->
			NarrationSnapshotItem(
				segmentId = item.segmentId,
				elementId = item.elementId,
				speaker = item.speaker,
				text = item.request.text,
				speed = item.request.speed,
				pitch = item.request.pitch,
				packId = item.request.packId,
				backend = item.backend,
				pauseAfterMs = item.pauseAfterMs,
			)
		},
	)

fun NarrationSnapshot.toPlan(): NarrationPlan =
	NarrationPlan(
		chapterId = chapterId,
		novelId = novelId,
		title = title,
		items = items.map { item ->
			NarrationQueueItem(
				segmentId = item.segmentId,
				elementId = item.elementId,
				speaker = item.speaker,
				request = SpeechRequest(item.text, item.speed, item.pitch, item.packId),
				backend = item.backend,
				pauseAfterMs = item.pauseAfterMs,
			)
		},
		systemEngine = systemEngine,
		systemLanguage = systemLanguage,
		systemVoice = systemVoice,
	)
