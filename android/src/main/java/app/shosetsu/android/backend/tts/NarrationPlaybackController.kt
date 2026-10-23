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
	val novelId: Int = -1,
	val title: String = "",
)

/**
 * The speech engine surface the playback queue needs. AudiobookEngine provides it.
 */
interface NarrationEngine {
	var systemEngine: String
	var systemLanguage: String
	var systemVoice: String
	suspend fun play(request: SpeechRequest, backend: String): SpeechResult
	fun prefetch(request: SpeechRequest, backend: String)
	fun stop()
}

/**
 * Process-level owner of the active audiobook queue. The foreground service is
 * the component that restores this queue and keeps it playing.
 */
class NarrationPlaybackController(
	private val engine: NarrationEngine,
	private val resumeStore: NarrationResumeStore? = null,
) {
	val playback = MutableStateFlow<TTSPlayback>(TTSPlayback.Stopped)
	val progress = MutableStateFlow<String?>(null)
	val done = MutableStateFlow<String?>(null)
	val speaker = MutableStateFlow<String?>(null)
	val speedMultiplier = MutableStateFlow(1f)
	val sleepUntilEpochMs = MutableStateFlow(0L)
	val planVersion = MutableStateFlow(0)
	val queueFinished = MutableSharedFlow<Int>(extraBufferCapacity = 1)
	val fallback = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	val errors = MutableSharedFlow<Int>(extraBufferCapacity = 1)

	/**
	 * The open reader, when there is one. Chapter changes go through it so the
	 * page and highlight stay with the audio. The service loads the chapter itself
	 * when this is null.
	 */
	@Volatile
	var chapterHost: NarrationChapterHost? = null

	val chapterId: Int get() = plan?.chapterId ?: -1
	val novelId: Int get() = plan?.novelId ?: -1
	val title: String get() = plan?.title.orEmpty()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	@Volatile
	private var plan: NarrationPlan? = null
	@Volatile
	private var cursor = 0
	private var playbackJob: Job? = null
	private var sleepJob: Job? = null
	private var generation = 0L

	@Synchronized
	fun submit(newPlan: NarrationPlan) {
		cancelPlayback()
		plan = newPlan
		cursor = 0
		done.value = null
		configure(newPlan)
		playback.value = TTSPlayback.Playing
		persist()
		bumpPlan()
		startLoop()
	}

	@Synchronized
	fun restore(snapshot: NarrationSnapshot, startPlayback: Boolean) {
		val restored = snapshot.toPlan()
		if (restored.items.isEmpty()) return
		cancelPlayback()
		plan = restored
		cursor = snapshot.cursor.coerceIn(0, restored.items.size)
		done.value = null
		configure(restored)
		playback.value = if (snapshot.playing) TTSPlayback.Playing else TTSPlayback.Paused
		bumpPlan()
		if (startPlayback && snapshot.playing && cursor < restored.items.size) startLoop()
	}

	@Synchronized
	fun resume() {
		if (plan == null || cursor >= (plan?.items?.size ?: 0)) return
		playback.value = TTSPlayback.Playing
		persist()
		startLoop()
	}

	@Synchronized
	fun ensurePlaying() {
		if (playback.value != TTSPlayback.Playing) return
		startLoop()
	}

	@Synchronized
	fun pause() {
		if (playback.value == TTSPlayback.Stopped) return
		playback.value = TTSPlayback.Paused
		cancelPlayback()
		persist()
	}

	/**
	 * Stops the current utterance without changing the saved resume point.
	 * Used when the service process is being torn down.
	 */
	@Synchronized
	fun interrupt() {
		cancelPlayback()
	}

	@Synchronized
	fun stop() {
		clearSleep()
		playback.value = TTSPlayback.Stopped
		cancelPlayback()
		plan = null
		cursor = 0
		progress.value = null
		done.value = null
		speaker.value = null
		resumeStore?.clear()
		bumpPlan()
	}

	/** Moves to a later sentence in the current chapter. */
	@Synchronized
	fun skipForward() = skip(1)

	/** Moves to the previous sentence, or restarts the first one. */
	@Synchronized
	fun skipBackward() = skip(-1)

	/**
	 * Cycles the session speed applied on top of each queued request.
	 * The queue itself is left alone.
	 */
	@Synchronized
	fun cycleSpeed(): Float {
		val steps = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
		val index = steps.indexOfFirst { kotlin.math.abs(it - speedMultiplier.value) < 0.02f }
		val next = steps[if (index < 0) 1 else (index + 1) % steps.size]
		speedMultiplier.value = next
		if (playback.value == TTSPlayback.Playing) restartCurrent()
		return next
	}

	/** Cycles the sleep timer through 15, 30, and 60 minutes, then off. */
	@Synchronized
	fun cycleSleep(nowMs: Long = System.currentTimeMillis()): Int {
		val minutes = when (sleepMinutes(nowMs)) {
			15 -> 30
			30 -> 60
			60 -> 0
			else -> 15
		}
		armSleep(minutes * 60_000L, nowMs)
		return minutes
	}

	/** Pauses playback after [durationMs]. A non-positive duration clears the timer. */
	@Synchronized
	fun armSleep(durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
		sleepJob?.cancel()
		sleepJob = null
		if (durationMs <= 0L) {
			sleepUntilEpochMs.value = 0L
			return
		}
		val until = nowMs + durationMs
		sleepUntilEpochMs.value = until
		sleepJob = scope.launch {
			val wait = until - System.currentTimeMillis()
			if (wait > 0L) delay(wait)
			if (sleepUntilEpochMs.value != until) return@launch
			sleepUntilEpochMs.value = 0L
			pause()
		}
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
					engine.prefetch(spoken(it.request), it.backend)
				}
				val result = try {
					engine.play(spoken(item.request), item.backend)
				} catch (e: TtsFailure) {
					errors.tryEmit(e.code)
					playback.value = TTSPlayback.Paused
					return@launch
				} catch (e: CancellationException) {
					throw e
				}
				if (
					activeGeneration != generation ||
					result == SpeechResult.Interrupted ||
					playback.value != TTSPlayback.Playing
				) {
					return@launch
				}
				if (result == SpeechResult.FellBackToSystem) fallback.tryEmit(Unit)
				synchronized(this@NarrationPlaybackController) {
					if (activeGeneration != generation || playback.value != TTSPlayback.Playing) return@launch
					cursor = index + 1
					persist()
				}
				val next = activePlan.items.getOrNull(cursor)
				if (next?.segmentId != item.segmentId) done.value = item.segmentId
				if (next != null && item.pauseAfterMs > 0) delay(item.pauseAfterMs)
			}
			if (
				activeGeneration == generation &&
				cursor >= activePlan.items.size &&
				playback.value == TTSPlayback.Playing
			) {
				speaker.value = null
				progress.value = null
				queueFinished.tryEmit(activePlan.chapterId)
			}
		}
	}

	@Synchronized
	private fun skip(delta: Int) {
		val active = plan ?: return
		if (active.items.isEmpty() || playback.value == TTSPlayback.Stopped) return
		val wasPlaying = playback.value == TTSPlayback.Playing
		val last = active.items.lastIndex
		val origin = cursor.coerceIn(0, active.items.size)
		if (delta > 0 && origin >= last && origin < active.items.size) return
		val target = if (delta < 0 && origin >= active.items.size) {
			last
		} else {
			(origin + delta).coerceIn(0, last)
		}
		cancelPlayback()
		cursor = target
		val item = active.items[target]
		progress.value = item.elementId
		speaker.value = item.speaker
		persist()
		if (wasPlaying) startLoop()
	}

	@Synchronized
	private fun restartCurrent() {
		val active = plan ?: return
		if (cursor >= active.items.size) return
		cancelPlayback()
		startLoop()
	}

	private fun spoken(request: SpeechRequest): SpeechRequest {
		val speed = request.speed * speedMultiplier.value
		return if (speed == request.speed) request else request.copy(speed = speed)
	}

	private fun sleepMinutes(nowMs: Long): Int {
		val until = sleepUntilEpochMs.value
		if (until <= nowMs) return 0
		val remaining = until - nowMs
		return when {
			remaining > 45 * 60_000L -> 60
			remaining > 20 * 60_000L -> 30
			else -> 15
		}
	}

	private fun clearSleep() {
		sleepJob?.cancel()
		sleepJob = null
		sleepUntilEpochMs.value = 0L
	}

	private fun bumpPlan() {
		planVersion.value = planVersion.value + 1
	}

	@Synchronized
	private fun persist() {
		val active = plan ?: return
		if (playback.value == TTSPlayback.Stopped) return
		resumeStore?.write(
			active.toSnapshot(
				cursor = cursor,
				playing = playback.value == TTSPlayback.Playing,
			)
		)
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

/**
 * Loads an adjacent chapter while the reader is open.
 * Returning false means there is no chapter in that direction.
 */
interface NarrationChapterHost {
	suspend fun playRelativeChapter(delta: Int): Boolean
}
