package app.shosetsu.android.backend.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NarrationResumeTest {
	@Test
	fun savedCursorRestoresTheNextSentence() = runBlocking {
		val directory = File(System.getProperty("java.io.tmpdir"), "narration-${System.nanoTime()}")
		directory.mkdirs()
		try {
			val store = NarrationResumeStore(File(directory, "resume.json"))
			val firstEngine = FakeEngine(holdOn = "two")
			val first = NarrationPlaybackController(firstEngine, store)
			first.submit(plan())
			waitUntil { firstEngine.played.contains("two") }

			val saved = store.read()
			assertEquals(1, saved?.cursor)
			assertEquals(9, saved?.novelId)
			assertEquals(4, saved?.chapterId)
			assertEquals("Chapter", saved?.title)
			assertTrue(saved?.playing == true)
			first.pause()
			assertFalse(store.read()?.playing == true)

			val secondEngine = FakeEngine()
			val second = NarrationPlaybackController(secondEngine, store)
			second.restore(saved!!, startPlayback = true)
			waitUntil { secondEngine.played.contains("two") }
			assertEquals(listOf("two"), secondEngine.played)

			second.stop()
			assertNull(store.read())
		} finally {
			directory.deleteRecursively()
		}
	}

	@Test
	fun corruptSnapshotIsIgnored() {
		val directory = File(System.getProperty("java.io.tmpdir"), "narration-${System.nanoTime()}")
		directory.mkdirs()
		try {
			val file = File(directory, "resume.json")
			file.writeText("{not json")
			assertNull(NarrationResumeStore(file).read())
		} finally {
			directory.deleteRecursively()
		}
	}

	private fun plan(): NarrationPlan = NarrationPlan(
		chapterId = 4,
		novelId = 9,
		title = "Chapter",
		items = listOf(item("one"), item("two")),
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
		private val hold = CompletableDeferred<Unit>()
		private var holding = false

		override suspend fun play(request: SpeechRequest, backend: String): SpeechResult {
			synchronized(played) { played += request.text }
			if (request.text == holdOn) {
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
