package app.shosetsu.android.backend.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsImageSearchTest {
	@Test
	fun keepsBitmapThumbsInSearchOrderAndSkipsOtherFiles() {
		val images = parseCommonsImages(
			"""
			{"query":{"pages":[
				{"index":2,"title":"File:Second.jpg","imageinfo":[{"mime":"image/jpeg","thumburl":"https://upload.wikimedia.org/second.jpg"}]},
				{"index":1,"title":"File:First.png","imageinfo":[{"mime":"image/png","thumburl":"https://upload.wikimedia.org/first.png"}]},
				{"index":3,"title":"File:Diagram.svg","imageinfo":[{"mime":"image/svg+xml","thumburl":"https://upload.wikimedia.org/diagram.svg"}]},
				{"index":4,"title":"File:Notes.pdf","imageinfo":[{"mime":"application/pdf","url":"https://upload.wikimedia.org/notes.pdf"}]}
			]}}
			""".trimIndent()
		)

		assertEquals(
			listOf(
				"https://upload.wikimedia.org/first.png",
				"https://upload.wikimedia.org/second.jpg",
			),
			images.map { it.thumbUrl },
		)
	}

	@Test
	fun usesTheFullImageWhenThereIsNoThumbnail() {
		val images = parseCommonsImages(
			"""
			{"query":{"pages":[
				{"index":1,"title":"File:Castle.jpg","imageinfo":[{"mime":"image/jpeg","url":"https://upload.wikimedia.org/castle.jpg"}]}
			]}}
			""".trimIndent()
		)
		assertEquals("https://upload.wikimedia.org/castle.jpg", images.single().thumbUrl)
	}

	@Test
	fun buildsACommonsSearchForTheHighlightedWord() {
		assertEquals("Castle", imageQuery("  \"Castle!\"  "))
		assertEquals("stone castle", imageQuery("stone   castle"))
		assertNull(imageQuery("this is a very long selection of words"))
		assertNull(imageQuery("   "))
		val url = commonsImageSearchUrl("castle").toString()
		assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?"))
		assertTrue("gsrsearch=castle%20filetype%3Abitmap" in url || "gsrsearch=castle+filetype%3Abitmap" in url)
		assertTrue("gsrnamespace=6" in url)
	}
}
