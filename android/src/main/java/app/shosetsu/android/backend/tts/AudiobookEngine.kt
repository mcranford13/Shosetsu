package app.shosetsu.android.backend.tts

import app.shosetsu.android.common.SettingKey
import kotlinx.coroutines.CancellationException

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
 * Picks the system voice or a downloaded Piper voice.
 * Neural playback falls back to the system voice when a pack is missing.
 */
class AudiobookEngine(
	private val system: SystemTtsSpeaker,
	private val neural: SherpaTtsSpeaker,
	val packs: VoicePackManager,
) : AudiobookSpeaker {
	@Volatile
	var systemEngine: String = ""
		set(value) {
			field = value
			system.configuredEngine = value
		}

	@Volatile
	var systemLanguage: String = ""
		set(value) {
			field = value
			system.configuredLanguage = value
		}

	@Volatile
	var systemVoice: String = ""
		set(value) {
			field = value
			system.configuredVoice = value
		}

	override suspend fun play(request: SpeechRequest): SpeechResult = play(request, SettingKey.TTS_SYSTEM)

	suspend fun play(request: SpeechRequest, backend: String): SpeechResult {
		val neuralReady = backend == SettingKey.TTS_NEURAL &&
			request.packId.isNotEmpty() &&
			packs.espeakReady() &&
			packs.modelFile(request.packId).isFile
		if (backend == SettingKey.TTS_NEURAL && neuralReady) {
			return try {
				neural.play(request)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				val played = system.play(request)
				if (played == SpeechResult.Completed) SpeechResult.FellBackToSystem else played
			}
		}
		val played = system.play(request)
		return if (backend == SettingKey.TTS_NEURAL && played == SpeechResult.Completed) {
			SpeechResult.FellBackToSystem
		} else {
			played
		}
	}

	override fun prefetch(request: SpeechRequest) = prefetch(request, SettingKey.TTS_SYSTEM)

	fun prefetch(request: SpeechRequest, backend: String) {
		if (backend == SettingKey.TTS_NEURAL) neural.prefetch(request)
	}

	override fun stop() {
		system.stop()
		neural.stop()
	}
}
