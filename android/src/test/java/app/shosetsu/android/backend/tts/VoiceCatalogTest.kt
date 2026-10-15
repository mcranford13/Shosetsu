package app.shosetsu.android.backend.tts

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class VoiceCatalogTest {
	private val catalog: VoiceCatalog by lazy {
		val file = listOf(
			File("src/main/assets/voices.json"),
			File("android/src/main/assets/voices.json"),
		).first { it.isFile }
		Json.decodeFromString<VoiceCatalog>(file.readText())
	}

	@Test
	fun piperVoicesStaySeparateAndKokoroSharesOneModel() {
		val piper = catalog.voices.filter { it.engine == VoiceEngine.PIPER }
		val kokoro = catalog.voices.filter { it.engine == VoiceEngine.KOKORO }
		assertEquals(true, piper.isNotEmpty())
		assertEquals(piper.map { it.id }, piper.map { it.modelKey() })
		assertEquals(11, kokoro.size)
		assertEquals(setOf("kokoro_en"), kokoro.map { it.modelKey() }.toSet())
		assertEquals((0..10).toList(), kokoro.map { it.speakerId })
	}

	@Test
	fun catalogEntriesHaveValidUniqueMetadata() {
		assertTrue("The catalog must contain voices", catalog.voices.isNotEmpty())
		assertEquals(
			"Voice IDs must be unique",
			catalog.voices.size,
			catalog.voices.map { it.id }.toSet().size,
		)
		catalog.voices.forEach { voice ->
			assertTrue("${voice.id} has a blank name", voice.name.isNotBlank())
			assertTrue("${voice.id} has a blank language", voice.language.isNotBlank())
			assertTrue("${voice.id} must have a positive download size", voice.sizeMb > 0)
			assertTrue("${voice.id} has an invalid URL", isHttpsUrl(voice.url))
			assertTrue(
				"${voice.id} has an unsupported engine",
				voice.engine == VoiceEngine.PIPER || voice.engine == VoiceEngine.KOKORO,
			)
			assertTrue("${voice.id} has a negative speaker ID", voice.speakerId >= 0)
		}
		assertTrue("espeak-ng-data must have a positive download size", catalog.espeakNgData.sizeMb > 0)
		assertTrue("espeak-ng-data has an invalid URL", isHttpsUrl(catalog.espeakNgData.url))
	}

	@Test
	fun voicesSharingAModelHaveConsistentDownloadMetadata() {
		catalog.voices.groupBy { it.modelKey() }.forEach { (modelId, voices) ->
			assertEquals("$modelId uses multiple URLs", 1, voices.map { it.url }.toSet().size)
			assertEquals("$modelId reports multiple sizes", 1, voices.map { it.sizeMb }.toSet().size)
			assertEquals("$modelId mixes engines", 1, voices.map { it.engine }.toSet().size)
			assertEquals(
				"$modelId repeats a speaker ID",
				voices.size,
				voices.map { it.speakerId }.toSet().size,
			)
		}
	}

	private fun isHttpsUrl(value: String): Boolean = runCatching {
		val uri = URI(value)
		uri.scheme == "https" && !uri.host.isNullOrBlank()
	}.getOrDefault(false)
}
