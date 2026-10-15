package app.shosetsu.android.backend.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.shosetsu.android.ui.reader.enqueueSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

class TtsFailure(val code: Int) : Exception("TTS failed with code $code")

/**
 * Android system voice. Pitch and speed use the same 1.0-is-normal scale as the reader settings.
 */
class SystemTtsSpeaker(
	private val context: Context,
) : AudiobookSpeaker {
	var configuredEngine: String = ""
	var configuredLanguage: String = ""
	var configuredVoice: String = ""

	private var engineName: String? = null
	private var tts: TextToSpeech? = null

	override suspend fun play(request: SpeechRequest): SpeechResult {
		val text = request.text.trim()
		if (text.isEmpty()) return SpeechResult.Completed
		val engine = prepare(configuredEngine, configuredLanguage, configuredVoice)
		withContext(Dispatchers.Main) {
			engine.setPitch(request.pitch.coerceIn(0.1f, 3f))
			engine.setSpeechRate(request.speed.coerceIn(0.1f, 3f))
		}
		return suspendCancellableCoroutine { cont ->
			val firstId = UUID.randomUUID().toString()
			var lastId = firstId
			engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
				override fun onStart(utteranceId: String?) {}

				override fun onDone(utteranceId: String?) {
					if (utteranceId == lastId && cont.isActive) cont.resume(SpeechResult.Completed)
				}

				@Deprecated("Deprecated in Java")
				override fun onError(utteranceId: String?) {
					if (cont.isActive) cont.resumeWithException(TtsFailure(TextToSpeech.ERROR))
				}

				override fun onError(utteranceId: String?, errorCode: Int) {
					if (cont.isActive) cont.resumeWithException(TtsFailure(errorCode))
				}
			})
			lastId = enqueueSpeech(engine, text, firstId, { code ->
				if (code < 0 && cont.isActive) cont.resumeWithException(TtsFailure(code))
			}, flush = true)
			cont.invokeOnCancellation { engine.stop() }
		}
	}

	/**
	 * Applies the saved system engine, language, and voice before speaking.
	 * Empty values keep the device defaults.
	 */
	suspend fun prepare(
		engine: String = "",
		language: String = "",
		voice: String = "",
	): TextToSpeech = withContext(Dispatchers.Main) {
		val current = tts
		val ready = if (current != null && engineName == engine) {
			current
		} else {
			open(engine).also {
				tts?.shutdown()
				tts = it
				engineName = engine
			}
		}
		applyLanguage(ready, language)
		applyVoice(ready, voice, language)
		ready
	}

	override fun stop() {
		tts?.stop()
	}

	fun shutdown() {
		tts?.shutdown()
		tts = null
		engineName = null
	}

	private suspend fun open(engine: String): TextToSpeech {
		val result = CompletableDeferred<Int>()
		val created = if (engine.isEmpty()) {
			TextToSpeech(context) { result.complete(it) }
		} else {
			TextToSpeech(context, { result.complete(it) }, engine)
		}
		if (result.await() != TextToSpeech.SUCCESS) {
			created.shutdown()
			throw TtsFailure(TextToSpeech.ERROR)
		}
		return created
	}

	private fun applyLanguage(tts: TextToSpeech, language: String) {
		val locale = if (language.isEmpty()) {
			Locale.getDefault()
		} else {
			tts.availableLanguages?.find { it.toLanguageTag() == language } ?: Locale.getDefault()
		}
		tts.setLanguage(locale)
	}

	private fun applyVoice(tts: TextToSpeech, voice: String, language: String) {
		if (voice.isEmpty()) return
		val locale = if (language.isEmpty()) Locale.getDefault()
		else tts.availableLanguages?.find { it.toLanguageTag() == language }
		val match = tts.voices?.find { it.name == voice && (locale == null || it.locale == locale) }
		if (match != null) tts.setVoice(match)
	}
}
