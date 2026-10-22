package app.shosetsu.android.common.utils.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunkerTest {
	@Test
	fun splitsSentencesWithoutBreakingNames() {
		val chunks = SpeechChunker.chunks("Mr. Smith went home. He sat down!")
		assertEquals(listOf("Mr. Smith went home.", "He sat down!"), chunks)
	}

	@Test
	fun turnsDashesIntoPauses() {
		val chunks = SpeechChunker.chunks("She stopped—then she ran.")
		assertEquals(listOf("She stopped, then she ran."), chunks)
	}

	@Test
	fun splitsAVeryLongSentence() {
		val sentence = "word, ".repeat(120).trim().trimEnd(',') + "."
		val chunks = SpeechChunker.chunks(sentence)
		assertEquals(true, chunks.size > 1)
		assertEquals(true, chunks.all { it.length <= 420 })
	}

	@Test
	fun removesBracketControlsButKeepsTheirWords() {
		assertEquals(
			listOf("The sign read Hello aloud."),
			SpeechChunker.chunks("The sign read [Hello] aloud."),
		)
		assertEquals(listOf("Hello"), SpeechChunker.chunks("[Hello"))
		assertEquals(listOf("Hello"), SpeechChunker.chunks("]Hello["))
		assertEquals(listOf("Warning She ran."), SpeechChunker.chunks("【Warning】 She ran."))
	}

	@Test
	fun removesBracketedCitationMarkers() {
		assertEquals(listOf("See and go."), SpeechChunker.chunks("See [1] and go."))
		assertEquals(listOf("Continue."), SpeechChunker.chunks("Continue. 【*】"))
	}

	@Test
	fun nestedBracketsCannotEnterEspeakPhonemeMode() {
		val chunks = SpeechChunker.chunks("[[h@'loU]]")
		assertEquals(listOf("h@'loU"), chunks)
	}
}
