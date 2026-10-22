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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
 * Keeps audiobook playback alive after the screen turns off.
 * The reader still owns the speech loop. This service holds a partial wake lock,
 * media audio focus, and a foreground notification so the process is not frozen.
 */
class NarrationService : Service(), DIAware {
	override val di: DI by closestDI()
	private val playbackController by instance<NarrationPlaybackController>()
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	private lateinit var audioManager: AudioManager
	private lateinit var session: MediaSession
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

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
		val power = getSystemService(POWER_SERVICE) as PowerManager
		wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shosetsu:narration").apply {
			setReferenceCounted(false)
		}
		session = MediaSession(this, "shosetsu-narration").apply {
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
			})
		}
		serviceScope.launch {
			combine(playbackController.playback, playbackController.speaker) { state, speaker ->
				state to speaker
			}.collect { (state, speaker) ->
				if (!shown.get()) return@collect
				playing = state == TTSPlayback.Playing
				text = speaker?.let { getString(R.string.reader_tts_speaking, it) }
					?: if (state == TTSPlayback.Paused) getString(R.string.reader_tts_paused) else text
				publish()
			}
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
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
			}

			ACTION_DISMISS -> {
				playbackController.stop()
				shutdown()
			}

			else -> {
				playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
				title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
				text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
				novelId = intent?.getIntExtra(EXTRA_NOVEL, -1) ?: -1
				chapterId = intent?.getIntExtra(EXTRA_CHAPTER, -1) ?: -1
				publish()
			}
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		if (playbackController.playback.value == TTSPlayback.Playing) {
			playbackController.pause()
		}
		releaseWakeLock()
		abandonFocus()
		if (::session.isInitialized) {
			session.isActive = false
			session.release()
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
		session.setPlaybackState(
			PlaybackState.Builder()
				.setActions(
					PlaybackState.ACTION_PLAY or
						PlaybackState.ACTION_PAUSE or
						PlaybackState.ACTION_STOP
				)
				.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
				.build()
		)
		session.isActive = true
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
		val toggle = if (playing) {
			action(android.R.drawable.ic_media_pause, getString(R.string.pause), ACTION_PAUSE)
		} else {
			action(android.R.drawable.ic_media_play, getString(R.string.resume), ACTION_RESUME)
		}
		return NotificationCompat.Builder(this, CHANNEL_NARRATION)
			.setSmallIcon(R.drawable.ic_narration_notification)
			.setContentTitle(title.ifBlank { getString(R.string.notification_channel_name_narration) })
			.setContentText(text)
			.setContentIntent(open)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.addAction(toggle)
			.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.reader_tts_stop), ACTION_STOP))
			.build()
	}

	private fun action(icon: Int, label: String, action: String): NotificationCompat.Action {
		val intent = PendingIntent.getService(
			this,
			action.hashCode(),
			Intent(this, NarrationService::class.java).setAction(action),
			pendingFlags(),
		)
		return NotificationCompat.Action(icon, label, intent)
	}

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

	private fun shutdown() {
		releaseWakeLock()
		abandonFocus()
		if (::session.isInitialized) session.isActive = false
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
