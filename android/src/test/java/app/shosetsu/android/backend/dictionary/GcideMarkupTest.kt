package app.shosetsu.android.backend.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcideMarkupTest {
	@Test
	fun parsesWrappedSensesAndSkipsParagraphsWithoutAHeadword() {
		val entries = parseGcide(
			"""
			<p><ent>0</ent><br/
			<hw>0</hw> <pos>adj.</pos> <sn>1.</sn> <def>indicating the absence of
			any or all units under consideration; -- representing the number zero as
			an Arabic numeral</def><br/
			[<source>WordNet 1.5</source>]</p>

			<p><q>Not a dictionary entry.</q></p>
			""".trimIndent()
		).toList()

		assertEquals(1, entries.size)
		assertEquals("0", entries[0].word)
		assertEquals(listOf("0"), entries[0].keys)
		assertEquals("adj.", entries[0].pos)
		assertEquals(
			"1. indicating the absence of any or all units under consideration; — representing the number zero as an Arabic numeral",
			entries[0].definition,
		)
	}

	@Test
	fun decodesHeadwordEntitiesAndKeepsEachDefinition() {
		val entry = parseGcide(
			"""
			<p><ent>Cooperate</ent><br/
			<hw>Co<oum/perate</hw> <pos>v. i.</pos>
			<def>To act jointly with others.</def>
			<def>See <it>cooperation</it>.</def>
			<asp>co-operate</asp></p>
			""".trimIndent()
		).single()

		assertEquals("Coöperate", entry.word)
		assertEquals(listOf("cooperate", "co-operate"), entry.keys)
		assertEquals("v. i.", entry.pos)
		assertEquals("To act jointly with others.\nSee cooperation.", entry.definition)
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
