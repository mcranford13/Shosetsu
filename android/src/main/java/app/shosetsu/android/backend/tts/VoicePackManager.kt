package app.shosetsu.android.backend.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream

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

enum class DownloadStatus { Missing, Downloading, Ready, Failed }

object VoiceEngine {
	const val PIPER = "piper"
	const val KOKORO = "kokoro"
}

data class VoiceStatus(
	val id: String,
	val name: String,
	val language: String,
	val description: String,
	val sizeMb: Int,
	val status: DownloadStatus,
	val engine: String = VoiceEngine.PIPER,
)

data class VoiceLibrary(
	val espeak: DownloadStatus,
	val espeakSizeMb: Int,
	val voices: List<VoiceStatus>,
	val packs: List<VoiceStatus> = emptyList(),
)

@Serializable
data class VoiceCatalog(
	val espeakNgData: ArchiveFile,
	val voices: List<VoiceDefinition>,
)

@Serializable
data class ArchiveFile(val url: String, val sizeMb: Int)

@Serializable
data class VoiceDefinition(
	val id: String,
	val name: String,
	val language: String,
	val description: String,
	val url: String,
	val sizeMb: Int,
	val engine: String = VoiceEngine.PIPER,
	val speakerId: Int = 0,
	/**
	 * Voices that share a model id are downloaded and loaded once.
	 * Empty means this voice is its own model, which is how the Piper packs are stored.
	 */
	val modelId: String = "",
) {
	fun modelKey(): String = modelId.ifBlank { id }
}

/**
 * Downloads Piper voice packs and the shared espeak-ng phoneme data.
 * Files live in app storage, not in the database.
 */
class VoicePackManager(
	context: Context,
	private val client: OkHttpClient,
) {
	private val root = File(context.filesDir, "tts")
	private val espeakDir = File(root, "espeak-ng-data")
	private val voiceRoot = File(root, "voices")
	private val json = Json { ignoreUnknownKeys = true }
	private val mutex = Mutex()

	val catalog: VoiceCatalog = context.assets.open("voices.json").use { stream ->
		json.decodeFromString(stream.bufferedReader().readText())
	}

	private val _library = MutableStateFlow(scan())
	val library: StateFlow<VoiceLibrary> = _library.asStateFlow()

	fun espeakReady(): Boolean = espeakDirReady(espeakDir)

	fun installedVoiceIds(): List<String> =
		catalog.voices.map { it.id }.filter { voiceReady(it) }

	fun definition(id: String): VoiceDefinition = catalog.voices.first { it.id == id }

	fun modelFile(id: String): File = File(modelDir(definition(id).modelKey()), "model.onnx")

	fun tokensFile(id: String): File = File(modelDir(definition(id).modelKey()), "tokens.txt")

	fun voicesFile(id: String): File = File(modelDir(definition(id).modelKey()), "voices.bin")

	fun espeakDirectory(): File = espeakDir

	suspend fun downloadEspeak() = withContext(Dispatchers.IO) {
		mutex.withLock {
			if (espeakReady()) {
				publish()
				return@withLock
			}
			setEspeak(DownloadStatus.Downloading)
			try {
				download(catalog.espeakNgData.url) { stream ->
					extract(stream) { name ->
						val marker = "espeak-ng-data/"
						val index = name.indexOf(marker)
						if (index < 0 || name.endsWith("/")) return@extract null
						val relative = name.substring(index + marker.length)
						if (relative.isBlank()) return@extract null
						safeChild(espeakDir, relative)
					}
				}
				if (!espeakReady()) error("Phoneme data archive did not contain espeak-ng-data")
				setEspeak(DownloadStatus.Ready)
			} catch (e: Exception) {
				espeakDir.deleteRecursively()
				setEspeak(DownloadStatus.Failed)
				throw e
			}
		}
	}

	suspend fun downloadVoice(id: String) = downloadPack(definition(id).modelKey())

	suspend fun downloadPack(modelId: String) = withContext(Dispatchers.IO) {
		val definition = catalog.voices.first { it.modelKey() == modelId }
		mutex.withLock {
			if (modelReady(modelId)) {
				publish()
				return@withLock
			}
			setModel(modelId, DownloadStatus.Downloading)
			val dir = modelDir(modelId)
			try {
				dir.mkdirs()
				download(definition.url) { stream ->
					var onnxSaved = false
					var tokensSaved = false
					var voicesSaved = false
					extract(stream) { name ->
						val base = name.substringAfterLast('/')
						when {
							!onnxSaved && base.endsWith(".onnx") && !name.contains("espeak-ng-data") -> {
								onnxSaved = true
								File(dir, "model.onnx")
							}
							!tokensSaved && base == "tokens.txt" && !name.contains("espeak-ng-data") -> {
								tokensSaved = true
								File(dir, "tokens.txt")
							}
							!voicesSaved && base == "voices.bin" -> {
								voicesSaved = true
								File(dir, "voices.bin")
							}
							else -> null
						}
					}
				}
				if (!modelReady(modelId)) error("Voice archive for $modelId did not contain a model")
				setModel(modelId, DownloadStatus.Ready)
			} catch (e: Exception) {
				dir.deleteRecursively()
				setModel(modelId, DownloadStatus.Failed)
				throw e
			}
		}
	}

	fun deleteVoice(id: String) {
		deletePack(definition(id).modelKey())
	}

	fun deletePack(modelId: String) {
		modelDir(modelId).deleteRecursively()
		publish()
	}

	fun deleteEspeak() {
		espeakDir.deleteRecursively()
		publish()
	}

	private fun modelDir(modelId: String) = File(voiceRoot, modelId)

	private fun voiceReady(id: String): Boolean = modelReady(definition(id).modelKey())

	private fun modelReady(modelId: String): Boolean {
		val engine = catalog.voices.first { it.modelKey() == modelId }.engine
		val dir = modelDir(modelId)
		val model = File(dir, "model.onnx")
		val tokens = File(dir, "tokens.txt")
		if (!model.isFile || model.length() == 0L || !tokens.isFile || tokens.length() == 0L) return false
		if (engine == VoiceEngine.KOKORO) {
			val voices = File(dir, "voices.bin")
			return voices.isFile && voices.length() > 0L
		}
		return true
	}

	private fun scan(): VoiceLibrary {
		val voices = catalog.voices.map { voice ->
			VoiceStatus(
				id = voice.id,
				name = voice.name,
				language = voice.language,
				description = voice.description,
				sizeMb = voice.sizeMb,
				status = if (voiceReady(voice.id)) DownloadStatus.Ready else DownloadStatus.Missing,
				engine = voice.engine,
			)
		}
		val packs = catalog.voices.groupBy { it.modelKey() }.map { (modelId, group) ->
			val first = group.first()
			val kokoro = first.engine == VoiceEngine.KOKORO
			VoiceStatus(
				id = modelId,
				name = if (kokoro) "Kokoro high-quality" else first.name,
				language = if (kokoro) "en" else first.language,
				description = if (kokoro) {
					"More natural narration. ${group.size} English voices."
				} else {
					first.description
				},
				sizeMb = first.sizeMb,
				status = if (modelReady(modelId)) DownloadStatus.Ready else DownloadStatus.Missing,
				engine = first.engine,
			)
		}
		return VoiceLibrary(
			espeak = if (espeakReady()) DownloadStatus.Ready else DownloadStatus.Missing,
			espeakSizeMb = catalog.espeakNgData.sizeMb,
			voices = voices,
			packs = packs,
		)
	}

	private fun publish() {
		_library.value = scan()
	}

	private fun setEspeak(status: DownloadStatus) {
		_library.value = _library.value.copy(espeak = status)
	}

	private fun setModel(modelId: String, status: DownloadStatus) {
		val voiceIds = catalog.voices.filter { it.modelKey() == modelId }.map { it.id }.toSet()
		_library.value = _library.value.copy(
			voices = _library.value.voices.map { voice ->
				if (voice.id in voiceIds) voice.copy(status = status) else voice
			},
			packs = _library.value.packs.map { pack ->
				if (pack.id == modelId) pack.copy(status = status) else pack
			},
		)
	}

	private fun download(url: String, consume: (InputStream) -> Unit) {
		val request = Request.Builder().url(url).build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) error("Download failed (${response.code}) for $url")
			val body = response.body ?: error("Empty download for $url")
			body.byteStream().use(consume)
		}
	}

	private fun extract(stream: InputStream, destination: (String) -> File?) {
		BZip2CompressorInputStream(stream).use { compressed ->
			TarArchiveInputStream(compressed).use { tar ->
				var entry = tar.nextEntry
				while (entry != null) {
					if (!entry.isDirectory) {
						destination(entry.name)?.let { dest ->
							dest.parentFile?.mkdirs()
							dest.outputStream().use { output -> tar.copyTo(output) }
						}
					}
					entry = tar.nextEntry
				}
			}
		}
	}

	private fun safeChild(parent: File, relative: String): File? {
		val child = File(parent, relative)
		val parentPath = parent.canonicalFile.toPath()
		val childPath = child.canonicalFile.toPath()
		if (!childPath.startsWith(parentPath)) return null
		return child
	}

	private fun espeakDirReady(dir: File): Boolean =
		File(dir, "phontab").isFile || File(dir, "phondata").isFile
}
