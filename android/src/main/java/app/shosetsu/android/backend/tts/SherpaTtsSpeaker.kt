package app.shosetsu.android.backend.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

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
 * On-device Piper and Kokoro voices through sherpa-onnx. At most two models stay loaded.
 * Kokoro speakers share one model. Pitch is ignored; these engines have no pitch control.
 */
class SherpaTtsSpeaker(
	private val packs: VoicePackManager,
) : AudiobookSpeaker {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val sessions = ArrayList<Session>(2)
	private val sessionLock = Any()
	private val cacheLock = Any()
	private val stopRequested = AtomicBoolean(false)
	private val activeTrack = AtomicReference<AudioTrack?>(null)
	private val prefetchJobs = ArrayList<Job>()
	private val cache = LinkedHashMap<String, CachedAudio>()
	private val inflight = HashSet<String>()

	private class Session(val modelId: String, val tts: OfflineTts, var lastUsed: Long)
	private class CachedAudio(val samples: FloatArray, val sampleRate: Int)

	override fun prefetch(request: SpeechRequest) {
		if (request.packId.isEmpty() || request.text.isBlank()) return
		if (!packs.espeakReady() || request.packId !in packs.installedVoiceIds()) return
		val key = cacheKey(request)
		synchronized(cacheLock) {
			if (key in cache || key in inflight || cache.size + inflight.size >= 3) return
			inflight += key
		}
		val job = scope.launch {
			try {
				val audio = synthesize(request)
				synchronized(cacheLock) {
					cache[key] = CachedAudio(audio.samples, audio.sampleRate)
					while (cache.size > 3) {
						cache.remove(cache.keys.first())
					}
				}
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (_: Exception) {
			} finally {
				synchronized(cacheLock) { inflight -= key }
			}
		}
		synchronized(cacheLock) {
			prefetchJobs += job
			prefetchJobs.removeAll { !it.isActive }
		}
	}

	override suspend fun play(request: SpeechRequest): SpeechResult = withContext(Dispatchers.IO) {
		stopRequested.set(false)
		try {
			val text = request.text.trim()
			if (text.isEmpty()) return@withContext SpeechResult.Completed
			if (!packs.espeakReady() || request.packId !in packs.installedVoiceIds()) {
				throw IllegalStateException("Voice pack is not installed")
			}
			val key = cacheKey(request)
			while (isInflight(key) && !stopRequested.get()) {
				ensureActive()
				delay(20)
			}
			val ready = takeCached(key)
			val samples: FloatArray
			val sampleRate: Int
			if (ready != null) {
				samples = ready.samples
				sampleRate = ready.sampleRate
			} else {
				val audio = synthesize(request)
				samples = audio.samples
				sampleRate = audio.sampleRate
			}
			ensureActive()
			if (stopRequested.get() || samples.isEmpty()) return@withContext SpeechResult.Interrupted
			playSamples(samples, sampleRate)
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Exception) {
			if (stopRequested.get()) SpeechResult.Interrupted else throw IllegalStateException("Neural voice failed", e)
		}
	}

	override fun stop() {
		stopRequested.set(true)
		synchronized(cacheLock) {
			prefetchJobs.forEach { it.cancel() }
			prefetchJobs.clear()
			inflight.clear()
			cache.clear()
		}
		activeTrack.getAndSet(null)?.let { track ->
			try {
				track.pause()
				track.flush()
				track.release()
			} catch (_: Throwable) {
			}
		}
	}

	fun shutdown() {
		stop()
		scope.coroutineContext[Job]?.cancel()
		synchronized(sessionLock) {
			sessions.forEach { it.tts.release() }
			sessions.clear()
		}
	}

	private fun synthesize(request: SpeechRequest): GeneratedAudio {
		val voice = packs.definition(request.packId)
		val tts = session(voice.modelKey())
		synchronized(tts) {
			return tts.generate(
				text = request.text,
				sid = voice.speakerId,
				speed = request.speed.coerceIn(0.1f, 3f),
			)
		}
	}

	private fun session(modelId: String): OfflineTts {
		synchronized(sessionLock) {
			sessions.find { it.modelId == modelId }?.let {
				it.lastUsed = System.nanoTime()
				return it.tts
			}
		}
		val created = OfflineTts(assetManager = null, config = config(modelId))
		synchronized(sessionLock) {
			sessions.find { it.modelId == modelId }?.let {
				created.release()
				it.lastUsed = System.nanoTime()
				return it.tts
			}
			while (sessions.size >= 2) {
				val victim = sessions.minBy { it.lastUsed }
				victim.tts.release()
				sessions.remove(victim)
			}
			sessions += Session(modelId, created, System.nanoTime())
			return created
		}
	}

	private fun config(modelId: String): OfflineTtsConfig {
		val voice = packs.catalog.voices.first { it.modelKey() == modelId }
		val dataDir = packs.espeakDirectory().absolutePath
		val model = packs.modelFile(voice.id).absolutePath
		val tokens = packs.tokensFile(voice.id).absolutePath
		val modelConfig = if (voice.engine == VoiceEngine.KOKORO) {
			OfflineTtsModelConfig(
				kokoro = OfflineTtsKokoroModelConfig(
					model = model,
					voices = packs.voicesFile(voice.id).absolutePath,
					tokens = tokens,
					dataDir = dataDir,
				),
				numThreads = 2,
				debug = false,
				provider = "cpu",
			)
		} else {
			OfflineTtsModelConfig(
				vits = OfflineTtsVitsModelConfig(
					model = model,
					tokens = tokens,
					dataDir = dataDir,
				),
				numThreads = 2,
				debug = false,
				provider = "cpu",
			)
		}
		return OfflineTtsConfig(model = modelConfig, maxNumSentences = 1)
	}

	private fun isInflight(key: String): Boolean = synchronized(cacheLock) { key in inflight }

	private fun takeCached(key: String): CachedAudio? = synchronized(cacheLock) { cache.remove(key) }

	private suspend fun playSamples(samples: FloatArray, sampleRate: Int): SpeechResult {
		val minBytes = AudioTrack.getMinBufferSize(
			sampleRate,
			AudioFormat.CHANNEL_OUT_MONO,
			AudioFormat.ENCODING_PCM_FLOAT,
		)
		val bufferBytes = max(minBytes, sampleRate * 2)
		val track = AudioTrack(
			AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build(),
			AudioFormat.Builder()
				.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
				.setSampleRate(sampleRate)
				.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
				.build(),
			bufferBytes,
			AudioTrack.MODE_STREAM,
			AudioManager.AUDIO_SESSION_ID_GENERATE,
		)
		activeTrack.set(track)
		try {
			track.play()
			var offset = 0
			while (offset < samples.size && !stopRequested.get() && currentCoroutineContext().isActive) {
				val count = min(4096, samples.size - offset)
				val wrote = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
				if (wrote <= 0) break
				offset += wrote
			}
			// A blocking write returns once samples are queued, not played. Releasing now drops the tail.
			val queued = offset
			val timeoutAt = System.nanoTime() +
				(queued.toLong() * 1000L / sampleRate + 1000L) * 1_000_000L
			while (!stopRequested.get() && currentCoroutineContext().isActive) {
				if (track.playbackHeadPosition >= queued) break
				if (System.nanoTime() > timeoutAt) break
				delay(15)
			}
		} finally {
			activeTrack.compareAndSet(track, null)
			try {
				track.pause()
				track.flush()
				track.release()
			} catch (_: Throwable) {
			}
		}
		return if (stopRequested.get()) SpeechResult.Interrupted else SpeechResult.Completed
	}

	private fun cacheKey(request: SpeechRequest): String {
		val voice = packs.definition(request.packId)
		return "${voice.modelKey()}|${voice.speakerId}|${request.speed}|${request.text}"
	}
}
