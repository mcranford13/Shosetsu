package app.shosetsu.android.backend.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiktionaryJsonTest {
	@Test
	fun keepsGlossesAndSkipsTheRestOfTheEntry() {
		val translations = (1..40).joinToString(",") {
			"""{"lang":"fr","word":"mot$it","sense":"a \"quoted\" sense"}"""
		}
		val entry = parseWiktionaryLine(
			"""
			{"pos":"noun","head_templates":[{"name":"en-noun","args":{"1":""}}],
			"translations":[$translations],
			"senses":[
				{"glosses":["A reference work.","A reference work."],"tags":["broadly"]},
				{"glosses":["The vocabulary of a person."]}
			],
			"lang_code":"en","word":"dictionary"}
			""".trimIndent()
		)

		requireNotNull(entry)
		assertEquals("dictionary", entry.word)
		assertEquals("dictionary", entry.key)
		assertEquals("noun", entry.pos)
		assertEquals("A reference work.\nThe vocabulary of a person.", entry.definition)
	}

	@Test
	fun dropsInflectedEntriesSoLookupCanReachTheLemma() {
		assertNull(
			parseWiktionaryLine(
				"""
				{"word":"running","pos":"verb","lang_code":"en","senses":[
					{"glosses":["present participle and gerund of run"],"tags":["form-of"],
					"form_of":[{"word":"run"}]}
				]}
				""".trimIndent()
			)
		)
		assertNull(
			parseWiktionaryLine(
				"""{"word":"colour","pos":"noun","lang_code":"en","senses":[{"glosses":["Obsolete form of color."]}]}"""
			)
		)

		val noun = parseWiktionaryLine(
			"""
			{"word":"running","pos":"noun","lang_code":"en","senses":[
				{"glosses":["The act of running."]},
				{"glosses":["present participle and gerund of run"],"tags":["form-of"]}
			]}
			""".trimIndent()
		)
		requireNotNull(noun)
		assertEquals("The act of running.", noun.definition)
	}

	@Test
	fun skipsOtherLanguagesAffixesAndBrokenLines() {
		assertNull(
			parseWiktionaryLine(
				"""{"word":"chat","pos":"noun","lang_code":"fr","senses":[{"glosses":["A cat."]}]}"""
			)
		)
		assertNull(
			parseWiktionaryLine(
				"""{"word":"un-","pos":"prefix","lang_code":"en","senses":[{"glosses":["Not."]}]}"""
			)
		)
		assertNull(parseWiktionaryLine("{"))
		assertNull(parseWiktionaryLine(""))
	}

	@Test
	fun decodesEscapesAndFoldsAccentsIntoTheLookupKey() {
		val entry = parseWiktionaryLine(
			"""{"word":"caf\u00e9","pos":"noun","lang_code":"en","senses":[{"glosses":["A small restaurant. To say \"hello\"."]}]}"""
		)
		requireNotNull(entry)
		assertEquals("café", entry.word)
		assertEquals("cafe", entry.key)
		assertEquals("A small restaurant. To say \"hello\".", entry.definition)
	}

	@Test
	fun keepsARealDefinitionThatMentionsForm() {
		val entry = parseWiktionaryLine(
			"""{"word":"democracy","pos":"noun","lang_code":"en","senses":[{"glosses":["A form of government."]}]}"""
		)
		requireNotNull(entry)
		assertEquals("A form of government.", entry.definition)
		assertEquals("noun", entry.pos)
	}

	@Test
	fun mapsShortPartOfSpeechNames() {
		val entry = parseWiktionaryLine(
			"""{"word":"quickly","pos":"adv","lang_code":"en","senses":[{"glosses":["In a quick manner."]}]}"""
		)
		requireNotNull(entry)
		assertEquals("adverb", entry.pos)
	}

	@Test
	fun buildsLookupCandidatesFromAHighlightedWord() {
		assertEquals(listOf("hello"), lookupCandidates("\"Hello!\""))
		assertEquals("in spite of", lookupCandidates("in spite of").first())
		assertTrue("well known" in lookupCandidates("well-known"))
		assertTrue("cat" in lookupCandidates("cats"))
		assertTrue("run" in lookupCandidates("running"))
		assertTrue(lookupCandidates("this is a very long selection of words").isEmpty())
	}
}
