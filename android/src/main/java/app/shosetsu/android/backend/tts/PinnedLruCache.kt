package app.shosetsu.android.backend.tts

/**
 * A small LRU cache whose leased values cannot be evicted. The cache may
 * temporarily exceed [maxSize] when every resident value is in use.
 */
internal class PinnedLruCache<K, V>(
	private val maxSize: Int,
	private val dispose: (V) -> Unit,
) : AutoCloseable {
	private data class Entry<V>(
		val value: V,
		var leases: Int,
		var lastUsed: Long,
		var disposeWhenReleased: Boolean = false,
	)

	internal class Lease<V>(
		val value: V,
		private val releaser: () -> Unit,
	) : AutoCloseable {
		private var released = false

		override fun close() {
			if (released) return
			released = true
			releaser()
		}
	}

	private val entries = LinkedHashMap<K, Entry<V>>()
	private var closed = false
	private var clock = 0L

	init {
		require(maxSize > 0) { "maxSize must be positive" }
	}

	@Synchronized
	fun acquire(key: K, create: () -> V): Lease<V> {
		check(!closed) { "Cache is closed" }
		val entry = entries[key] ?: run {
			evictIdleUntilRoom()
			Entry(create(), leases = 0, lastUsed = ++clock).also { entries[key] = it }
		}
		entry.leases++
		entry.lastUsed = ++clock
		return Lease(entry.value) { release(key, entry) }
	}

	@Synchronized
	private fun release(key: K, expected: Entry<V>) {
		val entry = entries[key]
		if (entry !== expected) return
		check(entry.leases > 0) { "Lease released too many times" }
		entry.leases--
		entry.lastUsed = ++clock
		if (entry.leases == 0 && entry.disposeWhenReleased) {
			entries.remove(key)
			dispose(entry.value)
		} else {
			trimIdleEntries()
		}
	}

	private fun evictIdleUntilRoom() {
		while (entries.size >= maxSize) {
			val victim = entries.entries
				.filter { it.value.leases == 0 }
				.minByOrNull { it.value.lastUsed }
				?: return
			entries.remove(victim.key)
			dispose(victim.value.value)
		}
	}

	private fun trimIdleEntries() {
		while (entries.size > maxSize) {
			val victim = entries.entries
				.filter { it.value.leases == 0 }
				.minByOrNull { it.value.lastUsed }
				?: return
			entries.remove(victim.key)
			dispose(victim.value.value)
		}
	}

	@Synchronized
	override fun close() {
		if (closed) return
		closed = true
		entries.entries.toList().forEach { (key, entry) ->
			if (entry.leases == 0) {
				entries.remove(key)
				dispose(entry.value)
			} else {
				entry.disposeWhenReleased = true
			}
		}
	}
}
