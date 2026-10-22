package app.shosetsu.android.backend.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedLruCacheTest {
	@Test
	fun doesNotDisposeLeasedValuesWhenCapacityIsExceeded() {
		val disposed = mutableListOf<String>()
		val cache = PinnedLruCache<String, String>(2, disposed::add)
		val first = cache.acquire("first") { "first" }
		val second = cache.acquire("second") { "second" }

		val third = cache.acquire("third") { "third" }

		assertEquals(emptyList<String>(), disposed)
		third.close()
		assertEquals(listOf("third"), disposed)
		first.close()
		second.close()
	}

	@Test
	fun closeDefersDisposalUntilLastLeaseEnds() {
		val disposed = mutableListOf<String>()
		val cache = PinnedLruCache<String, String>(2, disposed::add)
		val lease = cache.acquire("model") { "model" }

		cache.close()

		assertFalse("model" in disposed)
		lease.close()
		assertTrue("model" in disposed)
	}

	@Test
	fun duplicateCloseDoesNotReleaseAnotherLease() {
		val disposed = mutableListOf<String>()
		val cache = PinnedLruCache<String, String>(1, disposed::add)
		val first = cache.acquire("model") { "model" }
		val second = cache.acquire("model") { error("must reuse model") }

		first.close()
		first.close()
		cache.close()
		assertTrue(disposed.isEmpty())

		second.close()
		assertEquals(listOf("model"), disposed)
	}
}
