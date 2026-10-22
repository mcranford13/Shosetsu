package app.shosetsu.android.backend.tts

import app.shosetsu.android.view.uimodels.model.reader.TTSPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class NarrationQueueItem(
	val segmentId: String,
	val elementId: String,
	val speaker: String,
	val request: SpeechRequest,
	val backend: String,
	val pauseAfterMs: Long,
)

data class NarrationPlan(
	val chapterId: Int,
	val items: List<NarrationQueueItem>,
	val systemEngine: String,
	val systemLanguage: String,
	val systemVoice: String,
)

/**
 * Process-level owner of the active audiobook queue. Its scope is independent
 * of reader screens; the foreground service controls it directly.
 */
class NarrationPlaybackController(
	private val engine: AudiobookEngine,
) {
	val playback = MutableStateFlow<TTSPlayback>(TTSPlayback.Stopped)
	val progress = MutableStateFlow<String?>(null)
	val done = MutableStateFlow<String?>(null)
	val speaker = MutableStateFlow<String?>(null)
	val fallback = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	val errors = MutableSharedFlow<Int>(extraBufferCapacity = 1)

	val packs: VoicePackManager get() = engine.packs
	val chapterId: Int get() = plan?.chapterId ?: -1

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	@Volatile
	private var plan: NarrationPlan? = null
	@Volatile
	private var cursor = 0
	private var playbackJob: Job? = null
	private var generation = 0L

	@Synchronized
	fun submit(newPlan: NarrationPlan) {
		cancelPlayback()
		plan = newPlan
		cursor = 0
		done.value = null
		configure(newPlan)
		playback.value = TTSPlayback.Playing
		startLoop()
	}

	@Synchronized
	fun resume() {
		if (plan == null || cursor >= (plan?.items?.size ?: 0)) return
		playback.value = TTSPlayback.Playing
		startLoop()
	}

	@Synchronized
	fun pause() {
		if (playback.value == TTSPlayback.Stopped) return
		playback.value = TTSPlayback.Paused
		cancelPlayback()
	}

	@Synchronized
	fun stop() {
		playback.value = TTSPlayback.Stopped
		cancelPlayback()
		plan = null
		cursor = 0
		progress.value = null
		done.value = null
		speaker.value = null
	}

	@Synchronized
	private fun startLoop() {
		if (playbackJob?.isActive == true) return
		val activePlan = plan ?: return
		val activeGeneration = generation
		playbackJob = scope.launch {
			while (
				activeGeneration == generation &&
				playback.value == TTSPlayback.Playing &&
				cursor < activePlan.items.size
			) {
				val index = cursor
				val item = activePlan.items[index]
				progress.value = item.elementId
				speaker.value = item.speaker
				activePlan.items.drop(index + 1).take(3).forEach {
					engine.prefetch(it.request, it.backend)
				}
				val result = try {
					engine.play(item.request, item.backend)
				} catch (e: TtsFailure) {
					errors.tryEmit(e.code)
					playback.value = TTSPlayback.Paused
					return@launch
				} catch (e: CancellationException) {
					throw e
				}
				if (result == SpeechResult.Interrupted || playback.value != TTSPlayback.Playing) {
					return@launch
				}
				if (result == SpeechResult.FellBackToSystem) fallback.tryEmit(Unit)
				cursor = index + 1
				val next = activePlan.items.getOrNull(cursor)
				if (next?.segmentId != item.segmentId) done.value = item.segmentId
				if (next != null && item.pauseAfterMs > 0) delay(item.pauseAfterMs)
			}
			if (cursor >= activePlan.items.size) {
				speaker.value = null
				progress.value = null
			}
		}
	}

	private fun configure(plan: NarrationPlan) {
		engine.systemEngine = plan.systemEngine
		engine.systemLanguage = plan.systemLanguage
		engine.systemVoice = plan.systemVoice
	}

	private fun cancelPlayback() {
		generation++
		playbackJob?.cancel()
		playbackJob = null
		engine.stop()
	}
}
