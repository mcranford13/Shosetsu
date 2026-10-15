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
}
