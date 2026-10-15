package app.shosetsu.android.common.utils.tts

import app.shosetsu.android.view.uimodels.model.reader.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueSegmenterTest {
	@Test
	fun taggedDialogueAndNarration() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Alice said "Hello there." Then she left."""))
		)
		assertEquals(listOf("narrator", "alice", "narrator"), segments.map { it.characterKey })
		assertEquals("Hello there.", segments[1].text)
		assertTrue(segments[1].dialogue)
		assertFalse(segments[0].dialogue)
		assertEquals("a", segments[1].elementId)
	}

	@Test
	fun untaggedQuoteKeepsThePreviousSpeaker() {
		val quote = "Hello ".repeat(30).trim()
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Bob said "$quote" "Again." """))
		)
		val spoken = segments.filter { it.dialogue }
		assertEquals(2, spoken.size)
		assertEquals(DialogueSegmenter.characterKey("Bob"), spoken[0].characterKey)
		assertEquals(spoken[0].characterKey, spoken[1].characterKey)
		assertEquals("Again.", spoken[1].text)
	}

	@Test
	fun narrationOnly() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("p", "The sun set over the harbor."))
		)
		assertEquals(1, segments.size)
		assertEquals(DialogueSegmenter.NARRATOR, segments[0].characterKey)
		assertEquals("The sun set over the harbor.", segments[0].text)
		assertFalse(segments[0].dialogue)
	}

	@Test
	fun japaneseBrackets() {
		val tagged = DialogueSegmenter.segment(
			listOf(SegmentSource("a", "Alice said 「Hello」"))
		)
		assertEquals("alice", tagged.last { it.dialogue }.characterKey)
		assertEquals("Hello", tagged.last { it.dialogue }.text)

		val bare = DialogueSegmenter.segment(
			listOf(SegmentSource("b", "「こんにちは」"))
		)
		assertEquals(DialogueSegmenter.UNKNOWN, bare.single { it.dialogue }.characterKey)
		assertEquals("こんにちは", bare.single { it.dialogue }.text)
	}

	@Test
	fun nameKeyStaysStable() {
		assertEquals("alice", DialogueSegmenter.characterKey("Alice"))
		assertEquals("alice", DialogueSegmenter.characterKey(" alice "))
		assertEquals("alice smith", DialogueSegmenter.characterKey("Alice   Smith"))
		assertEquals("name:narrator", DialogueSegmenter.characterKey("Narrator"))

		val segments = DialogueSegmenter.segment(
			listOf(
				SegmentSource("a", """Alice said "One." """),
				SegmentSource("b", """Alice asked "Two." """),
			)
		)
		val keys = segments.filter { it.dialogue }.map { it.characterKey }
		assertEquals(listOf("alice", "alice"), keys)
	}

	@Test
	fun speechTagAfterTheQuote() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """"Stop right there," whispered Clara."""))
		)
		val line = segments.single { it.dialogue }
		assertEquals("clara", line.characterKey)
		assertEquals("Clara", line.displayName)
	}

	@Test
	fun nameCanBeSeparatedFromTheSpeechVerb() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Alice looked up and said "Hello there." """))
		)
		assertEquals("alice", segments.single { it.dialogue }.characterKey)

		val after = DialogueSegmenter.segment(
			listOf(SegmentSource("b", """"Come in," said softly Clara."""))
		)
		assertEquals("clara", after.single { it.dialogue }.characterKey)
		assertEquals("Clara", after.single { it.dialogue }.displayName)
	}

	@Test
	fun singleQuotesKeepApostrophesInsideTheLine() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", "Alice said 'I don't know.' Then Bob replied 'Try again.'"))
		)
		val spoken = segments.filter { it.dialogue }
		assertEquals(listOf("alice", "bob"), spoken.map { it.characterKey })
		assertEquals("I don't know.", spoken[0].text)
		assertEquals("Try again.", spoken[1].text)
	}

	@Test
	fun pronounTagKeepsTheNamedSpeaker() {
		val beat = "word ".repeat(30).trim()
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Alice said "First." $beat "Second," she added."""))
		)
		val spoken = segments.filter { it.dialogue }
		assertEquals(listOf("alice", "alice"), spoken.map { it.characterKey })
	}

	@Test
	fun colonIntroducesTheSpeaker() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Clara: "Wait for me." """))
		)
		assertEquals("clara", segments.single { it.dialogue }.characterKey)
	}

	@Test
	fun closestSpeechVerbWins() {
		val segments = DialogueSegmenter.segment(
			listOf(SegmentSource("a", """Alice said "Hi." Bob said "Hey." """))
		)
		assertEquals(listOf("alice", "bob"), segments.filter { it.dialogue }.map { it.characterKey })
	}
}
