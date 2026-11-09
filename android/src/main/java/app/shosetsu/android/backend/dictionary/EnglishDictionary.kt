package app.shosetsu.android.backend.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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
 * API that returns the definition to the calling app. This downloads the GNU
 * Collaborative International Dictionary of English (Webster's plus later
 * additions) and indexes it locally.
 */
class EnglishDictionary(
	context: Context,
	sharedClient: OkHttpClient,
) {
	private val root = File(context.filesDir, "dictionary")
	private val databaseFile = File(root, "gcide.sqlite")
	private val buildingFile = File(root, "gcide.sqlite.building")
	private val archiveFile = File(root, "gcide-0.54.tar.gz")
	private val client = OkHttpClient.Builder()
		.proxy(sharedClient.proxy)
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(2, TimeUnit.MINUTES)
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
		if (databaseFile.isFile) {
			scope.launch {
				mutex.withLock {
					if (database != null || _status.value !is DictionaryStatus.Ready) return@withLock
					val opened = openReadOnly()
					if (opened == null) {
						databaseFile.delete()
						_status.value = DictionaryStatus.Missing
					} else {
						synchronized(dbLock) { database = opened }
					}
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
					val freeSpace = root.usableSpace.takeIf { it > 0L } ?: root.parentFile?.usableSpace ?: 0L
					if (freeSpace < MIN_FREE_BYTES) error("Not enough free space for the dictionary")
					_status.value = DictionaryStatus.Downloading(0)
					downloadArchive()
					coroutineContext.ensureActive()
					_status.value = DictionaryStatus.Indexing(0)
					importArchive()
				} catch (e: CancellationException) {
					buildingFile.delete()
					archiveFile.delete()
					if (_status.value !is DictionaryStatus.Missing) {
						_status.value = DictionaryStatus.Missing
					}
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Dictionary install failed", e)
					buildingFile.delete()
					archiveFile.delete()
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
				databaseFile.delete()
				buildingFile.delete()
				archiveFile.delete()
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

	private suspend fun downloadArchive() {
		var lastError: Exception? = null
		for (url in SOURCES) {
			try {
				downloadFrom(url)
				val hash = archiveFile.sha256()
				if (!hash.equals(SHA256, ignoreCase = true)) {
					archiveFile.delete()
					error("Dictionary download was corrupted")
				}
				return
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				lastError = e
				archiveFile.delete()
			}
		}
		throw lastError ?: IllegalStateException("Could not download the dictionary")
	}

	private fun downloadFrom(url: String) {
		val request = Request.Builder().url(url).build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) error("Download failed (${response.code})")
			val body = response.body ?: error("Empty dictionary download")
			val total = body.contentLength()
			archiveFile.outputStream().use { output ->
				body.byteStream().use { input ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var readTotal = 0L
					var lastPercent = -1
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						output.write(buffer, 0, count)
						readTotal += count
						if (total > 0) {
							val percent = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
							if (percent != lastPercent) {
								lastPercent = percent
								_status.value = DictionaryStatus.Downloading(percent)
							}
						}
					}
				}
			}
		}
	}

	private suspend fun importArchive() {
		buildingFile.delete()
		val db = SQLiteDatabase.openOrCreateDatabase(buildingFile, null)
		try {
			db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
			db.execSQL("PRAGMA synchronous=OFF")
			db.execSQL(
				"CREATE TABLE entries (" +
					"word_key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, definition TEXT NOT NULL)"
			)
			val insert = db.compileStatement(
				"INSERT INTO entries (word_key, word, pos, definition) VALUES (?, ?, ?, ?)"
			)
			var files = 0
			GzipCompressorInputStream(archiveFile.inputStream()).use { gzip ->
				TarArchiveInputStream(gzip).use { tar ->
					var entry = tar.nextEntry
					while (entry != null) {
						coroutineContext.ensureActive()
						val name = entry.name.substringAfterLast('/')
						if (!entry.isDirectory && name.matches(CORPUS_FILE)) {
							val text = tar.readEntry(entry.size).toString(Charsets.ISO_8859_1)
							db.beginTransaction()
							try {
								parseGcide(text).forEach { parsed ->
									parsed.keys.forEach { key ->
										insert.bindString(1, key)
										insert.bindString(2, parsed.word)
										if (parsed.pos == null) insert.bindNull(3) else insert.bindString(3, parsed.pos)
										insert.bindString(4, parsed.definition)
										insert.executeInsert()
										insert.clearBindings()
									}
								}
								db.setTransactionSuccessful()
							} finally {
								db.endTransaction()
							}
							files++
							_status.value = DictionaryStatus.Indexing(((files * 100) / CORPUS_COUNT).coerceIn(0, 99))
						} else if (!entry.isDirectory) {
							tar.drain()
						}
						entry = tar.nextEntry
					}
				}
			}
			if (files == 0) error("Dictionary archive did not contain any entries")
			db.execSQL("CREATE INDEX idx_entries_key ON entries(word_key)")
		} catch (e: Throwable) {
			db.close()
			buildingFile.delete()
			throw e
		}
		db.close()
		synchronized(dbLock) {
			database?.close()
			database = null
			if (databaseFile.exists() && !databaseFile.delete()) {
				buildingFile.delete()
				error("Could not replace the installed dictionary")
			}
			if (!buildingFile.renameTo(databaseFile)) error("Could not install the dictionary")
			database = openReadOnly() ?: error("Could not open the dictionary")
		}
		archiveFile.delete()
		_status.value = DictionaryStatus.Ready
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

	private fun File.sha256(): String {
		val digest = MessageDigest.getInstance("SHA-256")
		inputStream().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private fun TarArchiveInputStream.readEntry(size: Long): ByteArray {
		if (size < 0L || size > MAX_CORPUS_BYTES) {
			drain()
			return ByteArray(0)
		}
		val out = ByteArray(size.toInt())
		var offset = 0
		while (offset < out.size) {
			val count = read(out, offset, out.size - offset)
			if (count < 0) return out.copyOf(offset)
			offset += count
		}
		return out
	}

	private fun TarArchiveInputStream.drain() {
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		while (read(buffer) >= 0) Unit
	}

	private companion object {
		const val TAG = "EnglishDictionary"
		const val SHA256 = "ae9b0187a1d1e7ee86218c707503b5082894c0ba61eeee26d226bca169e7fbd0"
		const val CORPUS_COUNT = 26
		const val MAX_CORPUS_BYTES = 16L * 1024 * 1024
		const val MIN_FREE_BYTES = 160L * 1024 * 1024
		val CORPUS_FILE = Regex("CIDE\\.[A-Z]")
		val SOURCES = listOf(
			"https://ftp.gnu.org/gnu/gcide/gcide-0.54.tar.gz",
			"https://ftpmirror.gnu.org/gcide/gcide-0.54.tar.gz",
		)
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
