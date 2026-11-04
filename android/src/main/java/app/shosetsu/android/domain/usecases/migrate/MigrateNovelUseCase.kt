package app.shosetsu.android.domain.usecases.migrate

import app.shosetsu.android.common.enums.ReadingStatus
import app.shosetsu.android.domain.model.local.ChapterEntity
import app.shosetsu.android.domain.model.local.MigrationCandidate
import app.shosetsu.android.domain.model.local.MigrationComparison
import app.shosetsu.android.domain.model.local.MigrationNovelSummary
import app.shosetsu.android.domain.model.local.NovelEntity
import app.shosetsu.android.domain.model.local.NovelMigrationEntity
import app.shosetsu.android.domain.repository.base.IChaptersRepository
import app.shosetsu.android.domain.repository.base.IExtensionsRepository
import app.shosetsu.android.domain.repository.base.INovelCategoryRepository
import app.shosetsu.android.domain.repository.base.INovelMigrationsRepository
import app.shosetsu.android.domain.repository.base.INovelPinsRepository
import app.shosetsu.android.domain.repository.base.INovelReaderSettingsRepository
import app.shosetsu.android.domain.repository.base.INovelSettingsRepository
import app.shosetsu.android.domain.repository.base.INovelVoiceCastRepository
import app.shosetsu.android.domain.repository.base.INovelsRepository
import app.shosetsu.android.domain.usecases.SetNovelCategoriesUseCase
import app.shosetsu.android.domain.usecases.get.GetExtensionUseCase
import app.shosetsu.android.domain.usecases.get.GetRemoteNovelUseCase
import app.shosetsu.lib.PAGE_INDEX
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Moves a library novel onto another installed source.
 * The original novel stays in the database, with its downloads, and is removed from the library.
 */
class MigrateNovelUseCase(
	private val novels: INovelsRepository,
	private val chapters: IChaptersRepository,
	private val getExtension: GetExtensionUseCase,
	private val getRemoteNovel: GetRemoteNovelUseCase,
	private val extensions: IExtensionsRepository,
	private val categories: INovelCategoryRepository,
	private val setCategories: SetNovelCategoriesUseCase,
	private val novelSettings: INovelSettingsRepository,
	private val readerSettings: INovelReaderSettingsRepository,
	private val voiceCast: INovelVoiceCastRepository,
	private val pins: INovelPinsRepository,
	private val migrations: INovelMigrationsRepository,
) {
	private val json = Json { ignoreUnknownKeys = true }

	suspend fun search(extensionId: Int, query: String): List<MigrationCandidate> {
		val extension = getExtension(extensionId)
			?: throw IllegalStateException("That source is not installed.")
		return novels.getCatalogueSearch(
			extension,
			query,
			mapOf<Int, Any>(PAGE_INDEX to extension.startIndex)
		).map { listing ->
			MigrationCandidate(
				url = listing.link,
				title = listing.title,
				imageURL = listing.imageURL,
			)
		}
	}

	/**
	 * Loads the chosen listing once so the comparison and the later confirm share the same chapters.
	 */
	suspend fun prepare(
		sourceNovelId: Int,
		extensionId: Int,
		url: String,
	): MigrationComparison {
		val source = novels.getNovel(sourceNovelId)
			?: throw IllegalStateException("The original novel is missing.")
		if (source.extensionID == extensionId && source.url == url) {
			throw IllegalStateException("That novel is already on this source.")
		}
		val existingId = novels.loadNovelId(url, extensionId)
		val destinationId = existingId ?: novels.insert(
			NovelEntity(
				url = url,
				extensionID = extensionId,
				title = source.title,
			)
		).toInt()
		if (destinationId == sourceNovelId) {
			throw IllegalStateException("That novel is already on this source.")
		}
		val destination = novels.getNovel(destinationId)
			?: throw IllegalStateException("The new novel could not be saved.")
		getRemoteNovel(destination)
		val loaded = novels.getNovel(destinationId) ?: destination
		return compare(source, loaded)
	}

	suspend fun commit(sourceId: Int, targetId: Int): Int {
		val source = novels.getNovel(sourceId)
			?: throw IllegalStateException("The original novel is missing.")
		val destination = novels.getNovel(targetId)
			?: throw IllegalStateException("The new novel is missing.")
		val destinationWasBookmarked = destination.bookmarked
		val fromCategories = categoryIds(sourceId)
		val toCategories = categoryIds(targetId)
		val fromPinned = pins.isPinned(sourceId)
		val toPinned = pins.isPinned(targetId)

		val sourceChapters = chapters.getChapters(sourceId).filter { it.id != null }
		val destinationChapters = chapters.getChapters(targetId).filter { it.id != null }
		val transfers = mutableListOf<ChapterTransfer>()
		for ((sourceChapter, destinationChapter) in MigrationChapterMatcher.match(
			sourceChapters,
			destinationChapters
		)) {
			val applied = MigrationChapterMatcher.progressToApply(
				sourceChapter,
				destinationChapter,
				destinationWasBookmarked
			) ?: continue
			val chapterId = destinationChapter.id ?: continue
			chapters.updateChapter(
				destinationChapter.copy(
					readingStatus = applied.status,
					readingPosition = applied.position,
					bookmarked = applied.bookmarked,
				)
			)
			transfers += ChapterTransfer(
				toChapterId = chapterId,
				previousStatus = destinationChapter.readingStatus.key,
				previousPosition = destinationChapter.readingPosition,
				previousBookmarked = destinationChapter.bookmarked,
				appliedStatus = applied.status.key,
				appliedPosition = applied.position,
				appliedBookmarked = applied.bookmarked,
			)
		}

		if (!destinationWasBookmarked) {
			copyNovelSettings(sourceId, targetId)
			copyReaderSettings(sourceId, targetId)
		}
		if (voiceCast.getAll(targetId).isEmpty()) {
			voiceCast.getAll(sourceId).forEach { cast ->
				voiceCast.upsert(cast.copy(novelId = targetId))
			}
		}

		val mergedCategories = (toCategories + fromCategories).distinct()
		setCategories(targetId, mergedCategories.toIntArray())
		setCategories(sourceId, intArrayOf())
		if (fromPinned) pins.setPinned(listOf(targetId), true)

		destination.bookmarked = true
		novels.update(destination)
		source.bookmarked = false
		novels.update(source)

		return migrations.insert(
			NovelMigrationEntity(
				fromNovelId = sourceId,
				toNovelId = targetId,
				toWasBookmarked = destinationWasBookmarked,
				fromPinned = fromPinned,
				toPinned = toPinned,
				fromCategoryIds = encodeIds(fromCategories),
				toCategoryIds = encodeIds(toCategories),
				transfersJson = json.encodeToString(transfers),
				createdAt = System.currentTimeMillis(),
			)
		)
	}

	suspend fun undo(migrationId: Int) {
		val record = migrations.get(migrationId) ?: return
		restore(record)
		migrations.delete(record.id)
	}

	suspend fun undoDestination(novelId: Int) {
		val record = migrations.latestForDestination(novelId) ?: return
		restore(record)
		migrations.delete(record.id)
	}

	fun observeDestination(novelId: Int): Flow<Boolean> =
		migrations.latestForDestinationFlow(novelId).map { it != null }

	private suspend fun restore(record: NovelMigrationEntity) {
		val source = novels.getNovel(record.fromNovelId)
		val destination = novels.getNovel(record.toNovelId)
		if (source != null) {
			source.bookmarked = true
			novels.update(source)
			setCategories(source.id!!, decodeIds(record.fromCategoryIds))
			pins.setPinned(listOf(source.id!!), record.fromPinned)
		}
		if (destination != null) {
			setCategories(destination.id!!, decodeIds(record.toCategoryIds))
			pins.setPinned(listOf(destination.id!!), record.toPinned)
			val destinationChapters = chapters.getChapters(destination.id!!).associateBy { it.id }
			val transfers = json.decodeFromString<List<ChapterTransfer>>(record.transfersJson)
			for (transfer in transfers) {
				val chapter = destinationChapters[transfer.toChapterId] ?: continue
				chapters.updateChapter(
					chapter.copy(
						readingStatus = ReadingStatus.fromInt(transfer.previousStatus),
						readingPosition = transfer.previousPosition,
						bookmarked = transfer.previousBookmarked,
					)
				)
			}
			if (!record.toWasBookmarked) {
				destination.bookmarked = false
				novels.update(destination)
			}
		}
	}

	private suspend fun compare(
		source: NovelEntity,
		destination: NovelEntity,
	): MigrationComparison {
		val sourceId = source.id ?: throw IllegalStateException("The original novel is missing.")
		val destinationId = destination.id
			?: throw IllegalStateException("The new novel could not be saved.")
		val sourceChapters = chapters.getChapters(sourceId)
		val destinationChapters = chapters.getChapters(destinationId)
		val readChapters = sourceChapters.filter { it.isInProgress() }
		val matchedIds = MigrationChapterMatcher.match(sourceChapters, destinationChapters)
			.mapNotNull { it.first.id }
			.toSet()
		val matchedRead = readChapters.count { it.id in matchedIds }
		return MigrationComparison(
			source = summarize(source, sourceChapters),
			destination = summarize(destination, destinationChapters),
			readChapters = readChapters.size,
			matchedReadChapters = matchedRead,
			alreadyInLibrary = destination.bookmarked,
		)
	}

	private suspend fun summarize(
		novel: NovelEntity,
		novelChapters: List<ChapterEntity>,
	): MigrationNovelSummary {
		val latest = MigrationChapterMatcher.latestChapter(novelChapters)
		val novelId = novel.id ?: -1
		return MigrationNovelSummary(
			novelId = novelId,
			title = novel.title,
			imageURL = novel.imageURL,
			sourceName = extensions.getInstalledExtension(novel.extensionID)?.name.orEmpty(),
			status = novel.status,
			authors = novel.authors.filter { it.isNotBlank() }.joinToString(", "),
			chapterCount = novelChapters.size,
			latestChapterTitle = latest?.title.orEmpty(),
			latestReleaseDate = latest?.releaseDate.orEmpty(),
		)
	}

	private suspend fun copyNovelSettings(fromId: Int, toId: Int) {
		val source = novelSettings.get(fromId) ?: return
		val copy = source.copy(novelID = toId)
		if (novelSettings.get(toId) == null) novelSettings.insert(copy)
		else novelSettings.update(copy)
	}

	private suspend fun copyReaderSettings(fromId: Int, toId: Int) {
		val source = readerSettings.get(fromId) ?: return
		val copy = source.copy(novelID = toId)
		if (readerSettings.get(toId) == null) readerSettings.insert(copy)
		else readerSettings.update(copy)
	}

	private suspend fun categoryIds(novelId: Int): List<Int> =
		categories.getNovelCategoriesFromNovel(novelId).map { it.categoryID }

	private fun ChapterEntity.isInProgress(): Boolean =
		readingStatus == ReadingStatus.READ || readingStatus == ReadingStatus.READING

	private fun encodeIds(ids: List<Int>): String = ids.joinToString(",")

	private fun decodeIds(raw: String): IntArray =
		raw.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()

	@Serializable
	private data class ChapterTransfer(
		val toChapterId: Int,
		val previousStatus: Int,
		val previousPosition: Double,
		val previousBookmarked: Boolean,
		val appliedStatus: Int,
		val appliedPosition: Double,
		val appliedBookmarked: Boolean,
	)
}
