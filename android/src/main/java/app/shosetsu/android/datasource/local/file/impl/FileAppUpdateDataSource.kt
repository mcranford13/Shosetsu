package app.shosetsu.android.datasource.local.file.impl

import app.shosetsu.android.common.FileNotFoundException
import app.shosetsu.android.common.FilePermissionException
import app.shosetsu.android.common.consts.APP_UPDATE_CACHE_FILE
import app.shosetsu.android.common.enums.InternalFileDir.CACHE
import app.shosetsu.android.common.ext.logE
import app.shosetsu.android.common.ext.logV
import app.shosetsu.android.datasource.local.file.base.IFileCachedAppUpdateDataSource
import app.shosetsu.android.domain.model.local.AppUpdateEntity
import app.shosetsu.android.domain.model.remote.AppUpdateDTO
import app.shosetsu.android.providers.file.base.IFileSystemProvider
import kotlinx.serialization.json.Json
import org.acra.ACRA
import java.io.File
import java.io.IOException
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

/**
 * shosetsu
 * 07 / 09 / 2020
 */
class FileAppUpdateDataSource(
	private val iFileSystemProvider: IFileSystemProvider
) : IFileCachedAppUpdateDataSource {

	init {
		try {
			iFileSystemProvider.createDirectory(CACHE, updatesPath)
			logV("Created directory: `$updatesPath`")
		} catch (e: Exception) {
			logE("Failed to create directory", e)
		}
	}

	@Throws(FilePermissionException::class, IOException::class)
	private fun write(debugAppUpdate: AppUpdateDTO) =
		iFileSystemProvider.writeFile(
			CACHE,
			APP_UPDATE_CACHE_FILE,
			Json.encodeToString(debugAppUpdate).encodeToByteArray()
		)

	@Throws(FileNotFoundException::class, FilePermissionException::class)
	override suspend fun load(): AppUpdateEntity =
		Json.decodeFromString<AppUpdateDTO>(
			iFileSystemProvider.readFile(CACHE, APP_UPDATE_CACHE_FILE).decodeToString()
		).convertTo()


	@Throws(FilePermissionException::class, IOException::class)
	override suspend fun save(
		appUpdate: AppUpdateEntity
	) {
		val previous = try {
			load()
		} catch (e: Exception) {
			null
		}
		val sameUpdate = previous != null &&
			previous.version == appUpdate.version &&
			previous.versionCode == appUpdate.versionCode
		if (!sameUpdate) deleteApk()
		write(AppUpdateDTO.fromEntity(appUpdate))
	}

	override suspend fun delete() {
		try {
			iFileSystemProvider.deleteFile(CACHE, APP_UPDATE_CACHE_FILE)
		} catch (exception: FilePermissionException) {
			logE("Failed to delete update file", exception)
			ACRA.errorReporter.handleSilentException(exception)
		}
		deleteApk()
	}

	override fun existingApkPath(): String? {
		if (!iFileSystemProvider.doesFileExist(CACHE, updatesCPath)) return null
		return try {
			val path = iFileSystemProvider.retrievePath(CACHE, updatesCPath)
			path.takeIf { File(it).length() > 0L }
		} catch (e: Exception) {
			null
		}
	}

	@Throws(IOException::class, FilePermissionException::class, FileNotFoundException::class)
	override fun writeAPK(
		appUpdate: AppUpdateEntity,
		bytes: InputStream
	): String {
		deleteApk()
		deletePartialApks()
		// existingApkPath() treats updatesCPath as complete, so it must only appear
		// once fully written; an interrupted download must never land there.
		val partPath = "$updatesPath/update-${System.nanoTime()}$PARTIAL_SUFFIX"
		try {
			iFileSystemProvider.createFile(CACHE, partPath)
			iFileSystemProvider.writeFile(CACHE, partPath, bytes)
			iFileSystemProvider.moveFile(CACHE, partPath, updatesCPath)
		} catch (e: Exception) {
			deleteQuietly(partPath)
			throw e
		}
		if (!iFileSystemProvider.doesFileExist(CACHE, updatesCPath)) {
			deleteQuietly(partPath)
			throw IOException("Failed to move downloaded update into place")
		}

		return iFileSystemProvider.retrievePath(CACHE, updatesCPath)
	}

	private fun deletePartialApks() {
		iFileSystemProvider.listFiles(CACHE, updatesPath)
			.filter { it.endsWith(PARTIAL_SUFFIX) }
			.forEach { deleteQuietly("$updatesPath/$it") }
	}

	private fun deleteQuietly(path: String) {
		try {
			if (iFileSystemProvider.doesFileExist(CACHE, path))
				iFileSystemProvider.deleteFile(CACHE, path)
		} catch (e: Exception) {
			logE("Failed to delete $path", e)
		}
	}

	private fun deleteApk() {
		if (!iFileSystemProvider.doesFileExist(CACHE, updatesCPath)) return
		try {
			iFileSystemProvider.deleteFile(CACHE, updatesCPath)
		} catch (exception: FilePermissionException) {
			logE("Failed to delete update apk", exception)
			ACRA.errorReporter.handleSilentException(exception)
		}
	}

	companion object {
		const val updatesPath = "/updates/"
		const val updatesFile = "/update.apk"
		const val updatesCPath = "$updatesPath$updatesFile"
		private const val PARTIAL_SUFFIX = ".apk.part"
	}


}