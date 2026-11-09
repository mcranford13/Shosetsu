package app.shosetsu.android.backend.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.coroutines.coroutineContext

/*
 * This file is part of shosetsu.
 *
 * shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Offline English dictionary.
 *
 * Android can send a word to another app with ACTION_DEFINE, but it has no
 * API that returns the definition to the calling app. This streams an English
 * Wiktionary extract (kaikki.org / wiktextract) and keeps the word, part of
 * speech, and glosses in a local database. The text is Wiktionary's, under
 * CC BY-SA 4.0 and the GFDL.
 */
class EnglishDictionary(
	context: Context,
	sharedClient: OkHttpClient,
) {
	private val root = File(context.filesDir, "dictionary")
	private val databaseFile = File(root, "wiktionary.sqlite")
	private val buildingFile = File(root, "wiktionary.sqlite.building")
	private val client = OkHttpClient.Builder()
		.proxy(sharedClient.proxy)
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(5, TimeUnit.MINUTES)
		.build()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private val dbLock = Any()
	private var database: SQLiteDatabase? = null
	private var downloadJob: Job? = null

	private val _status = MutableStateFlow<DictionaryStatus>(
		if (databaseFile.isFile) DictionaryStatus.Ready else DictionaryStatus.Missing
	)
	val status: StateFlow<DictionaryStatus> = _status.asStateFlow()

	init {
		scope.launch {
			deleteLegacyGcide()
			if (!databaseFile.isFile) return@launch
			mutex.withLock {
				if (database != null || _status.value !is DictionaryStatus.Ready) return@withLock
				val opened = openReadOnly()
				if (opened == null) {
					deleteDatabase(databaseFile)
					_status.value = DictionaryStatus.Missing
				} else {
					synchronized(dbLock) { database = opened }
				}
			}
		}
	}

	fun startDownload() {
		if (downloadJob?.isActive == true) return
		if (_status.value is DictionaryStatus.Ready) return
		downloadJob = scope.launch {
			mutex.withLock {
				try {
					root.mkdirs()
					deleteLegacyGcide()
					val freeSpace = root.usableSpace.takeIf { it > 0L }
						?: root.parentFile?.usableSpace
						?: 0L
					if (freeSpace < MIN_FREE_BYTES) {
						error("Not enough free space. About 2 GB is needed while the dictionary installs.")
					}
					_status.value = DictionaryStatus.Downloading(0)
					install()
				} catch (e: Exception) {
					deleteDatabase(buildingFile)
					if (e is CancellationException || coroutineContext[Job]?.isActive == false) {
						_status.value = DictionaryStatus.Missing
						if (e is CancellationException) throw e
						throw CancellationException("Dictionary install cancelled", e)
					}
					Log.w(TAG, "Dictionary install failed", e)
					_status.value = DictionaryStatus.Failed(
						e.message?.take(180) ?: "Could not install the dictionary"
					)
				}
			}
		}
	}

	fun remove() {
		downloadJob?.cancel()
		scope.launch {
			mutex.withLock {
				synchronized(dbLock) {
					database?.close()
					database = null
				}
				deleteDatabase(databaseFile)
				deleteDatabase(buildingFile)
				_status.value = DictionaryStatus.Missing
			}
		}
	}

	fun lookup(selection: String): List<DictionaryEntry> {
		val db = databaseOrOpen() ?: return emptyList()
		return try {
			val found = mutableListOf<DictionaryEntry>()
			for (candidate in lookupCandidates(selection)) {
				db.query(
					"entries",
					arrayOf("word", "pos", "definition"),
					"word_key = ?",
					arrayOf(candidate),
					null,
					null,
					"rowid",
					"4",
				).use { cursor ->
					val wordColumn = cursor.getColumnIndexOrThrow("word")
					val posColumn = cursor.getColumnIndexOrThrow("pos")
					val definitionColumn = cursor.getColumnIndexOrThrow("definition")
					while (cursor.moveToNext()) {
						found += DictionaryEntry(
							word = cursor.getString(wordColumn),
							pos = cursor.getString(posColumn),
							definition = cursor.getString(definitionColumn),
						)
					}
				}
				if (found.isNotEmpty()) return found
			}
			emptyList()
		} catch (e: Exception) {
			Log.w(TAG, "Dictionary lookup failed", e)
			emptyList()
		}
	}

	private suspend fun install() {
		deleteDatabase(buildingFile)
		val db = SQLiteDatabase.openOrCreateDatabase(buildingFile, null)
		try {
			db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
			db.execSQL("PRAGMA synchronous=OFF")
			db.execSQL(
				"CREATE TABLE entries (" +
					"word_key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, definition TEXT NOT NULL)"
			)
			val inserted = streamEntries(db)
			if (inserted < MIN_ENTRIES) error("Dictionary download did not contain enough entries")
			_status.value = DictionaryStatus.Indexing(99)
			db.execSQL("CREATE INDEX idx_entries_key ON entries(word_key)")
			Log.i(TAG, "Indexed $inserted dictionary entries")
		} catch (t: Throwable) {
			db.close()
			deleteDatabase(buildingFile)
			if (t is ZipException && coroutineContext[Job]?.isActive != false) {
				throw IllegalStateException("Dictionary download was incomplete", t)
			}
			throw t
		}
		db.close()
		synchronized(dbLock) {
			database?.close()
			database = null
			deleteDatabase(databaseFile)
			if (!buildingFile.renameTo(databaseFile)) error("Could not install the dictionary")
			database = openReadOnly() ?: run {
				deleteDatabase(databaseFile)
				error("Could not open the dictionary")
			}
		}
		_status.value = DictionaryStatus.Ready
	}

	private suspend fun streamEntries(db: SQLiteDatabase): Int = coroutineScope {
		val request = Request.Builder()
			.url(SOURCE)
			.header("Accept-Encoding", "identity")
			.header("User-Agent", "Shosetsu")
			.build()
		val call = client.newCall(request)
		val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				awaitCancellation()
			} finally {
				call.cancel()
			}
		}
		try {
			call.execute().use { response ->
				if (!response.isSuccessful) error("Download failed (${response.code})")
				val body = response.body ?: error("Empty dictionary download")
				val total = body.contentLength()
				if (total in 1 until MIN_ARCHIVE_BYTES) error("Dictionary download was incomplete")
				val counting = CountingInputStream(body.byteStream())
				val inserted = GZIPInputStream(counting, GZIP_BUFFER).use { gzip ->
					InputStreamReader(gzip, Charsets.UTF_8).buffered(GZIP_BUFFER).use { text ->
						insertEntries(db, text, counting, total)
					}
				}
				if (counting.count < MIN_ARCHIVE_BYTES || (total > 0L && counting.count != total)) {
					error("Dictionary download was incomplete")
				}
				inserted
			}
		} finally {
			watcher.cancel()
		}
	}

	private suspend fun insertEntries(
		db: SQLiteDatabase,
		text: Reader,
		counting: CountingInputStream,
		total: Long,
	): Int {
		val insert = db.compileStatement(
			"INSERT INTO entries (word_key, word, pos, definition) VALUES (?, ?, ?, ?)"
		)
		val lines = CappedLineReader(text, MAX_LINE_CHARS)
		var inserted = 0
		var batch = 0
		var lastPercent = -1
		insert.use {
			db.beginTransaction()
			try {
				while (true) {
					val line = lines.readLine() ?: break
					if (line.isEmpty()) continue
					val record = parseWiktionaryLine(line) ?: continue
					insert.bindString(1, record.key)
					insert.bindString(2, record.word)
					if (record.pos == null) insert.bindNull(3) else insert.bindString(3, record.pos)
					insert.bindString(4, record.definition)
					insert.executeInsert()
					insert.clearBindings()
					inserted++
					batch++
					if (batch >= BATCH) {
						db.setTransactionSuccessful()
						db.endTransaction()
						db.beginTransaction()
						batch = 0
						val percent = if (total > 0L) {
							((counting.count * 100) / total).toInt().coerceIn(0, 99)
						} else {
							0
						}
						if (percent != lastPercent) {
							lastPercent = percent
							_status.value = DictionaryStatus.Downloading(percent)
						}
						coroutineContext.ensureActive()
					}
				}
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
		}
		return inserted
	}

	private fun databaseOrOpen(): SQLiteDatabase? = synchronized(dbLock) {
		database?.let { return it }
		if (!databaseFile.isFile || _status.value !is DictionaryStatus.Ready) return null
		openReadOnly()?.also { database = it }
	}

	private fun openReadOnly(): SQLiteDatabase? =
		if (!databaseFile.isFile) null
		else runCatching {
			SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
		}.getOrNull()

	private fun deleteLegacyGcide() {
		listOf("gcide.sqlite", "gcide.sqlite.building", "gcide-0.54.tar.gz").forEach { name ->
			deleteDatabase(File(root, name))
		}
	}

	private fun deleteDatabase(file: File) {
		file.delete()
		File(file.path + "-journal").delete()
		File(file.path + "-wal").delete()
		File(file.path + "-shm").delete()
	}

	private class CountingInputStream(private val inner: InputStream) : InputStream() {
		var count = 0L
			private set

		override fun read(): Int {
			val value = inner.read()
			if (value >= 0) count++
			return value
		}

		override fun read(b: ByteArray, off: Int, len: Int): Int {
			val n = inner.read(b, off, len)
			if (n > 0) count += n
			return n
		}

		override fun close() {
			inner.close()
		}
	}

	private class CappedLineReader(
		private val reader: Reader,
		private val cap: Int,
	) {
		private val buf = CharArray(64 * 1024)
		private val sb = StringBuilder(1024)
		private var pos = 0
		private var limit = 0

		fun readLine(): String? {
			sb.setLength(0)
			var overflow = false
			var saw = false
			while (true) {
				if (pos >= limit) {
					limit = reader.read(buf)
					pos = 0
					if (limit < 0) {
						if (!saw) return null
						return if (overflow) "" else sb.toString()
					}
				}
				val c = buf[pos++]
				saw = true
				when (c) {
					'\n' -> return if (overflow) "" else sb.toString()
					'\r' -> Unit
					else -> if (overflow) {
						Unit
					} else if (sb.length >= cap) {
						overflow = true
						sb.setLength(0)
					} else {
						sb.append(c)
					}
				}
			}
		}
	}

	private companion object {
		const val TAG = "EnglishDictionary"
		const val SOURCE =
			"https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz"
		const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
		const val MIN_ARCHIVE_BYTES = 100L * 1024 * 1024
		const val MIN_ENTRIES = 100_000
		const val BATCH = 4_000
		const val MAX_LINE_CHARS = 4_000_000
		const val GZIP_BUFFER = 64 * 1024
	}
}

data class DictionaryEntry(
	val word: String,
	val pos: String?,
	val definition: String,
)

sealed interface DictionaryStatus {
	data object Missing : DictionaryStatus
	data class Downloading(val percent: Int) : DictionaryStatus
	data class Indexing(val percent: Int) : DictionaryStatus
	data object Ready : DictionaryStatus
	data class Failed(val message: String) : DictionaryStatus
}
