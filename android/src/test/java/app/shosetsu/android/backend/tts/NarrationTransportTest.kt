package app.shosetsu.android.backend.tts

import app.shosetsu.android.view.uimodels.model.reader.CharacterGender
import app.shosetsu.android.view.uimodels.model.reader.SpeechSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTransportTest {
	@Test
	fun skipForwardLeavesTheCurrentSentence() = runBlocking {
		val engine = FakeEngine(holdOn = "two")
		val controller = NarrationPlaybackController(engine)
		controller.submit(plan("one", "two", "three"))
		waitUntil { engine.played.contains("two") }
		controller.skipForward()
		waitUntil { engine.played.contains("three") }
		assertEquals(listOf("one", "two", "three"), engine.played)
	}

	@Test
	fun skipBackwardRestartsAtThePreviousSentence() = runBlocking {
		val engine = FakeEngine(holdOn = "two")
		val controller = NarrationPlaybackController(engine)
		controller.submit(plan("one", "two", "three"))
		waitUntil { engine.played.contains("two") }
		controller.skipBackward()
		waitUntil { engine.played.count { it == "one" } >= 2 }
		assertEquals(listOf("one", "two", "one"), engine.played.take(3))
	}

	@Test
	fun sessionSpeedScalesTheQueuedRequest() = runBlocking {
		val engine = FakeEngine(holdOn = "one")
		val controller = NarrationPlaybackController(engine)
		assertEquals(1.25f, controller.cycleSpeed())
		controller.submit(plan("one"))
		waitUntil { engine.speeds.isNotEmpty() }
		assertEquals(1.25f, engine.speeds.single())
		assertEquals(1.5f, controller.cycleSpeed())
	}

	@Test
	fun sleepTimerPausesPlayback() = runBlocking {
		val engine = FakeEngine(holdOn = "one")
		val controller = NarrationPlaybackController(engine)
		controller.submit(plan("one", "two"))
		waitUntil { engine.played.contains("one") }
		controller.armSleep(100)
		waitUntil { controller.playback.value == app.shosetsu.android.view.uimodels.model.reader.TTSPlayback.Paused }
		assertEquals(0L, controller.sleepUntilEpochMs.value)
	}

	@Test
	fun queueKeepsParagraphPausesAndDropsEmptyText() {
		val items = runBlocking {
			buildNarrationQueue(
				segments = listOf(
					SpeechSegment("a", "e1", "Hello. There.", "sunless", "Sunless", true, CharacterGender.MALE),
					SpeechSegment("b", "e2", "   ", "narrator", "Narrator", false),
					SpeechSegment("c", "e3", "Next.", "narrator", "Narrator", false),
				),
				startSegmentIndex = 0,
				startChunkIndex = 0,
				speed = 1f,
				pitch = 1f,
				backend = "neural",
				linePauseMs = 10,
				paragraphPauseMs = 50,
				packId = { "voice" },
			)
		}
		assertTrue(items.size >= 2)
		assertEquals("Next.", items.last().request.text)
		assertTrue(items.any { it.pauseAfterMs == 50L })
		assertTrue(items.none { it.request.text.isBlank() })
	}

	@Test
	fun htmlChapterBecomesSpeech() {
		val segments = segmentsFromChapterHtml(
			"<html><body><p>Master Sunless said hello.</p></body></html>",
			dedupTitles = true,
		)
		assertTrue(segments.any { it.text.contains("hello") })
	}

	private fun plan(vararg text: String) = NarrationPlan(
		chapterId = 4,
		novelId = 9,
		title = "Chapter",
		items = text.map { item(it) },
		systemEngine = "system",
		systemLanguage = "en",
		systemVoice = "default",
	)

	private fun item(text: String) = NarrationQueueItem(
		segmentId = text,
		elementId = text,
		speaker = "Sunless",
		request = SpeechRequest(text, 1f, 1f, "kokoro_adam"),
		backend = "neural",
		pauseAfterMs = 0,
	)

	private fun waitUntil(condition: () -> Boolean) {
		val deadline = System.currentTimeMillis() + 3_000
		while (!condition()) {
			if (System.currentTimeMillis() > deadline) error("timed out waiting for playback")
			Thread.sleep(20)
		}
	}

	private class FakeEngine(private val holdOn: String? = null) : NarrationEngine {
		override var systemEngine: String = ""
		override var systemLanguage: String = ""
		override var systemVoice: String = ""
		val played = ArrayList<String>()
		val speeds = ArrayList<Float>()
		private val hold = CompletableDeferred<Unit>()
		private var holding = false

		override suspend fun play(request: SpeechRequest, backend: String): SpeechResult {
			synchronized(played) {
				played += request.text
				speeds += request.speed
			}
			if (request.text == holdOn && !holding) {
				holding = true
				hold.await()
			}
			return SpeechResult.Completed
		}

		override fun prefetch(request: SpeechRequest, backend: String) = Unit

		override fun stop() {
			if (holding && !hold.isCompleted) hold.complete(Unit)
		}
	}
}
