package app.shosetsu.android.domain.usecases

import app.shosetsu.android.backend.tts.VoicePackManager
import app.shosetsu.android.common.SettingKey
import app.shosetsu.android.common.utils.tts.DialogueSegmenter
import app.shosetsu.android.domain.model.local.NovelVoiceCastEntity
import app.shosetsu.android.domain.repository.base.INovelVoiceCastRepository
import app.shosetsu.android.domain.repository.base.ISettingsRepository
import app.shosetsu.android.view.uimodels.model.reader.CharacterGender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
 * Remembers which downloaded voice belongs to each character of a novel.
 * The first time a character is heard, a voice is chosen and stored.
 */
class NovelVoiceCastUseCase(
	private val repository: INovelVoiceCastRepository,
	private val settings: ISettingsRepository,
	private val voices: VoicePackManager,
) {
	private val locks = ConcurrentHashMap<Int, Mutex>()

	fun getFlow(novelId: Int): Flow<List<NovelVoiceCastEntity>> = repository.getFlow(novelId)

	suspend fun resolve(
		novelId: Int,
		characterKey: String,
		displayName: String,
		gender: CharacterGender = CharacterGender.UNKNOWN,
	): NovelVoiceCastEntity {
		val mutex = locks.getOrPut(novelId) { Mutex() }
		return mutex.withLock {
			repository.get(novelId, characterKey)?.let { return@withLock it }
			repository.getAll(novelId)
				.firstOrNull {
					it.characterKey != characterKey &&
						DialogueSegmenter.characterKey(it.displayName) == characterKey
				}
				?.let { alias ->
					val migrated = alias.copy(
						characterKey = characterKey,
						displayName = displayName,
					)
					repository.upsert(migrated)
					return@withLock migrated
				}
			val installed = voices.installedVoiceIds()
			val narratorDefault = settings.getString(SettingKey.ReaderNarratorVoice)
				.ifBlank { installed.firstOrNull().orEmpty() }
			val unknownDefault = settings.getString(SettingKey.ReaderUnknownVoice)
				.ifBlank { installed.getOrNull(1) ?: installed.firstOrNull().orEmpty() }
			val chosen = when (characterKey) {
				DialogueSegmenter.NARRATOR -> narratorDefault
				DialogueSegmenter.UNKNOWN -> unknownDefault
				else -> {
					val pool = installed.filter { it != narratorDefault }.ifEmpty { installed }
					val matching = if (gender == CharacterGender.UNKNOWN) {
						pool
					} else {
						pool.filter { voices.definition(it).gender == gender }.ifEmpty { pool }
					}
					if (matching.isEmpty()) {
						""
					} else {
						matching[Math.floorMod(characterKey.hashCode(), matching.size)]
					}
				}
			}
			val entity = NovelVoiceCastEntity(novelId, characterKey, displayName, chosen)
			if (chosen.isNotEmpty()) repository.upsert(entity)
			entity
		}
	}

	suspend fun save(entity: NovelVoiceCastEntity) {
		repository.upsert(entity)
	}
}
