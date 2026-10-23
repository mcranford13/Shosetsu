package app.shosetsu.android.backend.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.shosetsu.android.R
import app.shosetsu.android.common.consts.BundleKeys.BUNDLE_CHAPTER_ID
import app.shosetsu.android.common.consts.BundleKeys.BUNDLE_NOVEL_ID
import app.shosetsu.android.common.consts.Notifications.CHANNEL_NARRATION
import app.shosetsu.android.common.consts.Notifications.ID_NARRATION
import app.shosetsu.android.ui.reader.ChapterReader
import app.shosetsu.android.view.uimodels.model.reader.TTSPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicBoolean

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
 * Owns audiobook playback after the reader closes.
 * The speech queue, cursor, and engine live in [NarrationPlaybackController]. This
 * service restores the last snapshot, exposes that state to the reader, and holds
 * the wake lock, audio focus, and media notification.
 */
class NarrationService : Service(), DIAware {
	override val di: DI by closestDI()
	private val playbackController by instance<NarrationPlaybackController>()
	private val chapterPlayer by instance<NarrationChapterPlayer>()
	private val resumeStore by instance<NarrationResumeStore>()
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val session by lazy { Session() }

	/**
	 * In-process connection for the reader. The StateFlows are the service's playback state.
	 */
	inner class Session : Binder() {
		val playback: StateFlow<TTSPlayback> = playbackController.playback
		val progress: StateFlow<String?> = playbackController.progress
		val speaker: StateFlow<String?> = playbackController.speaker
		val done: StateFlow<String?> = playbackController.done

		fun submit(plan: NarrationPlan) {
			playbackController.submit(plan)
			title = plan.title
			novelId = plan.novelId
			chapterId = plan.chapterId
			playing = true
			text = plan.items.firstOrNull()?.speaker?.let { getString(R.string.reader_tts_speaking, it) }.orEmpty()
			publish()
		}

		fun resume() {
			playbackController.resume()
			playing = true
			publish()
		}

		fun pause() {
			pausedByFocus = false
			playbackController.pause()
			playing = false
			publish()
		}

		fun stop() {
			playbackController.stop()
			shutdown()
		}
	}

	private lateinit var audioManager: AudioManager
	private lateinit var mediaSession: MediaSession
	private var wakeLock: PowerManager.WakeLock? = null
	private var focusRequest: AudioFocusRequest? = null
	private var focusHeld = false
	private var pausedByFocus = false
	private var playing = false
	private var title = ""
	private var text = ""
	private var novelId = -1
	private var chapterId = -1

	private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
		when (change) {
			AudioManager.AUDIOFOCUS_LOSS,
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
				if (playing) {
					pausedByFocus = true
					playbackController.pause()
				}
			}

			AudioManager.AUDIOFOCUS_GAIN -> {
				if (pausedByFocus) {
					pausedByFocus = false
					playbackController.resume()
				}
			}
		}
	}

	override fun onBind(intent: Intent?): IBinder = session

	override fun onCreate() {
		super.onCreate()
		audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
		val power = getSystemService(POWER_SERVICE) as PowerManager
		wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shosetsu:narration").apply {
			setReferenceCounted(false)
		}
		mediaSession = MediaSession(this, "shosetsu-narration").apply {
			@Suppress("DEPRECATION")
			setFlags(
				MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
					MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
			)
			setCallback(object : MediaSession.Callback() {
				override fun onPlay() {
					playbackController.resume()
				}

				override fun onPause() {
					pausedByFocus = false
					playbackController.pause()
				}

				override fun onStop() {
					playbackController.stop()
				}

				override fun onSkipToNext() {
					playbackController.skipForward()
				}

				override fun onSkipToPrevious() {
					playbackController.skipBackward()
				}

				override fun onFastForward() {
					playbackController.skipForward()
				}

				override fun onRewind() {
					playbackController.skipBackward()
				}

				override fun onCustomAction(action: String, extras: Bundle?) {
					when (action) {
						ACTION_CHAPTER_NEXT -> shiftChapter(1)
						ACTION_CHAPTER_PREVIOUS -> shiftChapter(-1)
						ACTION_SPEED -> playbackController.cycleSpeed()
						ACTION_SLEEP -> playbackController.cycleSleep()
					}
				}
			})
		}
		if (playbackController.chapterId < 0) {
			resumeStore.read()?.let { snapshot ->
				playbackController.restore(snapshot, startPlayback = false)
				title = snapshot.title
				novelId = snapshot.novelId
				chapterId = snapshot.chapterId
			}
		}
		serviceScope.launch {
			combine(
				playbackController.playback,
				playbackController.speaker,
				playbackController.speedMultiplier,
				playbackController.sleepUntilEpochMs,
				playbackController.planVersion,
			) { state, speaker, _, _, _ ->
				state to speaker
			}.collect { (state, speaker) ->
				if (!shown.get()) return@collect
				playing = state == TTSPlayback.Playing
				refreshIdentity()
				text = speaker?.let { getString(R.string.reader_tts_speaking, it) }
					?: if (state == TTSPlayback.Paused) getString(R.string.reader_tts_paused) else text
				publish()
			}
		}
		serviceScope.launch(Dispatchers.IO) {
			playbackController.queueFinished.collect {
				if (playbackController.chapterHost != null) return@collect
				chapterPlayer.playNextIfEnabled()
			}
		}
		serviceScope.launch {
			while (true) {
				delay(30_000)
				if (shown.get() && playbackController.sleepUntilEpochMs.value > System.currentTimeMillis()) {
					publish()
				}
			}
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent == null) {
			restoreForeground()
			return START_STICKY
		}
		when (intent.action) {
			ACTION_PAUSE -> {
				pausedByFocus = false
				playing = false
				publish()
				playbackController.pause()
			}

			ACTION_RESUME -> {
				playing = true
				publish()
				playbackController.resume()
			}

			ACTION_STOP -> {
				playbackController.stop()
				shutdown()
				return START_NOT_STICKY
			}

			ACTION_DISMISS -> {
				playbackController.stop()
				shutdown()
				return START_NOT_STICKY
			}

			ACTION_SKIP_NEXT -> playbackController.skipForward()
			ACTION_SKIP_PREVIOUS -> playbackController.skipBackward()
			ACTION_CHAPTER_NEXT -> shiftChapter(1)
			ACTION_CHAPTER_PREVIOUS -> shiftChapter(-1)
			ACTION_SPEED -> playbackController.cycleSpeed()
			ACTION_SLEEP -> playbackController.cycleSleep()

			else -> {
				playing = intent.getBooleanExtra(EXTRA_PLAYING, playbackController.playback.value == TTSPlayback.Playing)
				title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { playbackController.title }
				text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
				novelId = intent.getIntExtra(EXTRA_NOVEL, playbackController.novelId)
				chapterId = intent.getIntExtra(EXTRA_CHAPTER, playbackController.chapterId)
				if (playing) playbackController.ensurePlaying()
				publish()
			}
		}
		return START_STICKY
	}

	override fun onDestroy() {
		playbackController.interrupt()
		releaseWakeLock()
		abandonFocus()
		if (::mediaSession.isInitialized) {
			mediaSession.isActive = false
			mediaSession.release()
		}
		serviceScope.cancel()
		super.onDestroy()
	}

	private fun publish() {
		val notification = notification()
		try {
			ServiceCompat.startForeground(
				this,
				ID_NARRATION,
				notification,
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
				} else {
					0
				},
			)
		} catch (_: Exception) {
			shutdown()
			return
		}
		if (playing) {
			acquireWakeLock()
			requestFocus()
		} else {
			releaseWakeLock()
			if (!pausedByFocus) abandonFocus()
		}
		val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
		val rate = if (playing) playbackController.speedMultiplier.value else 0f
		mediaSession.setMetadata(
			MediaMetadata.Builder()
				.putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank {
					getString(R.string.notification_channel_name_narration)
				})
				.putString(MediaMetadata.METADATA_KEY_ARTIST, statusText())
				.build()
		)
		mediaSession.setPlaybackState(
			PlaybackState.Builder()
				.setActions(
					PlaybackState.ACTION_PLAY or
						PlaybackState.ACTION_PAUSE or
						PlaybackState.ACTION_PLAY_PAUSE or
						PlaybackState.ACTION_STOP or
						PlaybackState.ACTION_SKIP_TO_NEXT or
						PlaybackState.ACTION_SKIP_TO_PREVIOUS or
						PlaybackState.ACTION_FAST_FORWARD or
						PlaybackState.ACTION_REWIND
				)
				.addCustomAction(customAction(ACTION_CHAPTER_PREVIOUS, getString(R.string.reader_tts_previous_chapter), android.R.drawable.ic_media_rew))
				.addCustomAction(customAction(ACTION_CHAPTER_NEXT, getString(R.string.reader_tts_next_chapter_action), android.R.drawable.ic_media_ff))
				.addCustomAction(customAction(ACTION_SPEED, getString(R.string.reader_tts_speed, speedLabel()), android.R.drawable.ic_menu_sort_by_size))
				.addCustomAction(customAction(ACTION_SLEEP, sleepActionLabel(), android.R.drawable.ic_lock_idle_alarm))
				.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, rate)
				.build()
		)
		mediaSession.isActive = true
	}

	private fun refreshIdentity() {
		val controllerTitle = playbackController.title
		if (controllerTitle.isNotBlank()) title = controllerTitle
		if (playbackController.novelId >= 0) novelId = playbackController.novelId
		if (playbackController.chapterId >= 0) chapterId = playbackController.chapterId
	}

	private fun shiftChapter(delta: Int) {
		serviceScope.launch(Dispatchers.IO) {
			val host = playbackController.chapterHost
			if (host != null) {
				host.playRelativeChapter(delta)
			} else {
				chapterPlayer.playRelative(delta, announce = false)
			}
		}
	}

	private fun speedLabel(): String {
		val speed = playbackController.speedMultiplier.value
		val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
		return "${text}×"
	}

	private fun statusText(): String {
		val until = playbackController.sleepUntilEpochMs.value
		val sleep = if (until > System.currentTimeMillis()) {
			val minutes = ((until - System.currentTimeMillis() + 59_999L) / 60_000L).toInt()
			getString(R.string.reader_tts_sleep_minutes, minutes)
		} else {
			null
		}
		return listOfNotNull(text.ifBlank { null }, speedLabel(), sleep).joinToString(" · ")
	}

	private fun notification(): Notification {
		val open = PendingIntent.getActivity(
			this,
			0,
			Intent(this, ChapterReader::class.java).apply {
				putExtra(BUNDLE_NOVEL_ID, novelId)
				putExtra(BUNDLE_CHAPTER_ID, chapterId)
				addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			},
			pendingFlags(),
		)
		val toggleAction = if (playing) ACTION_PAUSE else ACTION_RESUME
		val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
		val toggleLabel = if (playing) getString(R.string.pause) else getString(R.string.resume)
		val expanded = RemoteViews(packageName, R.layout.notification_narration)
		val shownTitle = title.ifBlank { getString(R.string.notification_channel_name_narration) }
		expanded.setTextViewText(R.id.narration_title, shownTitle)
		expanded.setTextViewText(R.id.narration_status, statusText())
		expanded.setTextViewText(R.id.narration_speed, getString(R.string.reader_tts_speed, speedLabel()))
		expanded.setTextViewText(R.id.narration_sleep, sleepActionLabel())
		expanded.setImageViewResource(R.id.narration_toggle, toggleIcon)
		expanded.setOnClickPendingIntent(R.id.narration_previous, servicePending(ACTION_SKIP_PREVIOUS))
		expanded.setOnClickPendingIntent(R.id.narration_toggle, servicePending(toggleAction))
		expanded.setOnClickPendingIntent(R.id.narration_next, servicePending(ACTION_SKIP_NEXT))
		expanded.setOnClickPendingIntent(R.id.narration_chapter_previous, servicePending(ACTION_CHAPTER_PREVIOUS))
		expanded.setOnClickPendingIntent(R.id.narration_chapter_next, servicePending(ACTION_CHAPTER_NEXT))
		expanded.setOnClickPendingIntent(R.id.narration_speed, servicePending(ACTION_SPEED))
		expanded.setOnClickPendingIntent(R.id.narration_sleep, servicePending(ACTION_SLEEP))
		expanded.setOnClickPendingIntent(R.id.narration_stop, servicePending(ACTION_STOP))
		return NotificationCompat.Builder(this, CHANNEL_NARRATION)
			.setSmallIcon(R.drawable.ic_narration_notification)
			.setContentTitle(shownTitle)
			.setContentText(statusText())
			.setContentIntent(open)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCustomBigContentView(expanded)
			.addAction(action(android.R.drawable.ic_media_previous, getString(R.string.reader_tts_previous), ACTION_SKIP_PREVIOUS))
			.addAction(action(toggleIcon, toggleLabel, toggleAction))
			.addAction(action(android.R.drawable.ic_media_next, getString(R.string.reader_tts_next), ACTION_SKIP_NEXT))
			.build()
	}

	private fun sleepActionLabel(): String {
		val until = playbackController.sleepUntilEpochMs.value
		if (until <= System.currentTimeMillis()) return getString(R.string.reader_tts_sleep)
		val minutes = ((until - System.currentTimeMillis() + 59_999L) / 60_000L).toInt()
		return getString(R.string.reader_tts_sleep_minutes, minutes)
	}

	private fun action(icon: Int, label: String, action: String): NotificationCompat.Action =
		NotificationCompat.Action(icon, label, servicePending(action))

	private fun servicePending(action: String): PendingIntent =
		PendingIntent.getService(
			this,
			action.hashCode(),
			Intent(this, NarrationService::class.java).setAction(action),
			pendingFlags(),
		)

	private fun customAction(action: String, label: String, icon: Int): PlaybackState.CustomAction =
		PlaybackState.CustomAction.Builder(action, label, icon).build()

	private fun pendingFlags(): Int {
		val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
		return immutable or PendingIntent.FLAG_UPDATE_CURRENT
	}

	private fun acquireWakeLock() {
		val lock = wakeLock ?: return
		if (!lock.isHeld) lock.acquire(WAKE_TIMEOUT_MS)
	}

	private fun releaseWakeLock() {
		val lock = wakeLock ?: return
		if (lock.isHeld) lock.release()
	}

	private fun requestFocus() {
		if (focusHeld) return
		val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
				.setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
				)
				.setOnAudioFocusChangeListener(focusListener)
				.build()
				.also { focusRequest = it }
			audioManager.requestAudioFocus(request)
		} else {
			@Suppress("DEPRECATION")
			audioManager.requestAudioFocus(
				focusListener,
				AudioManager.STREAM_MUSIC,
				AudioManager.AUDIOFOCUS_GAIN,
			)
		}
		focusHeld = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
	}

	private fun abandonFocus() {
		if (!focusHeld) return
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
		} else {
			@Suppress("DEPRECATION")
			audioManager.abandonAudioFocus(focusListener)
		}
		focusHeld = false
	}

	private fun restoreForeground() {
		if (playbackController.chapterId < 0) {
			val snapshot = resumeStore.read() ?: return
			playbackController.restore(snapshot, startPlayback = snapshot.playing)
			title = snapshot.title
			novelId = snapshot.novelId
			chapterId = snapshot.chapterId
		} else if (playbackController.playback.value == TTSPlayback.Playing) {
			playbackController.ensurePlaying()
		}
		shown.set(true)
		playing = playbackController.playback.value == TTSPlayback.Playing
		title = title.ifBlank { playbackController.title }
		novelId = if (novelId >= 0) novelId else playbackController.novelId
		chapterId = if (chapterId >= 0) chapterId else playbackController.chapterId
		val speaker = playbackController.speaker.value
		if (speaker != null) text = getString(R.string.reader_tts_speaking, speaker)
		publish()
	}

	private fun shutdown() {
		releaseWakeLock()
		abandonFocus()
		if (::mediaSession.isInitialized) mediaSession.isActive = false
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
		shown.set(false)
	}

	companion object {
		private const val ACTION_SHOW = "app.shosetsu.android.tts.SHOW"
		private const val ACTION_PAUSE = "app.shosetsu.android.tts.PAUSE"
		private const val ACTION_RESUME = "app.shosetsu.android.tts.RESUME"
		private const val ACTION_STOP = "app.shosetsu.android.tts.STOP"
		private const val ACTION_DISMISS = "app.shosetsu.android.tts.DISMISS"
		private const val ACTION_SKIP_NEXT = "app.shosetsu.android.tts.SKIP_NEXT"
		private const val ACTION_SKIP_PREVIOUS = "app.shosetsu.android.tts.SKIP_PREVIOUS"
		private const val ACTION_CHAPTER_NEXT = "app.shosetsu.android.tts.CHAPTER_NEXT"
		private const val ACTION_CHAPTER_PREVIOUS = "app.shosetsu.android.tts.CHAPTER_PREVIOUS"
		private const val ACTION_SPEED = "app.shosetsu.android.tts.SPEED"
		private const val ACTION_SLEEP = "app.shosetsu.android.tts.SLEEP"
		private const val EXTRA_PLAYING = "playing"
		private const val EXTRA_TITLE = "title"
		private const val EXTRA_TEXT = "text"
		private const val EXTRA_NOVEL = "novel"
		private const val EXTRA_CHAPTER = "chapter"
		private const val WAKE_TIMEOUT_MS = 12L * 60L * 60L * 1000L

		private val shown = AtomicBoolean(false)

		fun show(
			context: Context,
			playing: Boolean,
			title: String,
			text: String,
			novelId: Int,
			chapterId: Int,
		) {
			shown.set(true)
			val intent = Intent(context, NarrationService::class.java).apply {
				action = ACTION_SHOW
				putExtra(EXTRA_PLAYING, playing)
				putExtra(EXTRA_TITLE, title)
				putExtra(EXTRA_TEXT, text)
				putExtra(EXTRA_NOVEL, novelId)
				putExtra(EXTRA_CHAPTER, chapterId)
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun dismiss(context: Context) {
			if (!shown.compareAndSet(true, false)) return
			try {
				context.startService(
					Intent(context, NarrationService::class.java).setAction(ACTION_DISMISS)
				)
			} catch (_: IllegalStateException) {
			}
		}
	}
}
