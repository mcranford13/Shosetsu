package app.shosetsu.android.viewmodel.impl

import android.app.Application
import app.shosetsu.android.R
import app.shosetsu.android.common.ext.onIO
import app.shosetsu.android.domain.model.local.MigrationCandidate
import app.shosetsu.android.domain.model.local.MigrationComparison
import app.shosetsu.android.domain.usecases.get.GetNovelUIUseCase
import app.shosetsu.android.domain.usecases.load.LoadBrowseExtensionsUseCase
import app.shosetsu.android.domain.usecases.migrate.MigrateNovelUseCase
import app.shosetsu.android.view.uimodels.model.MigrationExtensionUI
import app.shosetsu.android.view.uimodels.model.MigrationNovelUI
import app.shosetsu.android.viewmodel.abstracted.AMigrationViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * Shosetsu
 *
 * @since 04 / 08 / 2021
 * @author Doomsdayrs
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationViewModel(
	private val getNovelUI: GetNovelUIUseCase,
	private val loadBrowseExtensionsFlow: LoadBrowseExtensionsUseCase,
	private val migrateNovel: MigrateNovelUseCase,
	private val application: Application,
) : AMigrationViewModel() {
	private val novelIds = MutableStateFlow<List<Int>>(emptyList())
	private val query = MutableStateFlow("")
	private val selectedExtensionId = MutableStateFlow(-1)
	private var queryTouched = false

	override val currentQuery: StateFlow<String> = query

	override val which = MutableStateFlow(-1)

	override val results = MutableStateFlow<List<MigrationCandidate>>(emptyList())

	override val hasSearched = MutableStateFlow(false)

	override val isWorking = MutableStateFlow(false)

	override val error = MutableStateFlow<String?>(null)

	override val comparison = MutableStateFlow<MigrationComparison?>(null)

	private val migratedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
	override val migrated: SharedFlow<Int> = migratedEvents

	override val novels: StateFlow<ImmutableList<MigrationNovelUI>> by lazy {
		novelIds.flatMapLatest { ids ->
			when {
				ids.isEmpty() -> flowOf(emptyList())
				ids.size == 1 -> getNovelUI(ids.first()).map { listOfNotNull(it) }
				else -> combine(ids.map { getNovelUI(it) }) { array -> array.filterNotNull() }
			}
		}.combine(which) { result, id ->
			result.map { novel ->
				MigrationNovelUI(
					id = novel.id,
					title = novel.title,
					imageURL = novel.imageURL,
					extensionId = novel.extID,
					isSelected = novel.id == id,
				)
			}.toImmutableList()
		}.onEach { list ->
			if (!queryTouched) {
				list.find { it.isSelected }?.let { selected ->
					if (query.value != selected.title) query.value = selected.title
				}
			}
		}.onIO().stateIn(viewModelScopeIO, SharingStarted.Lazily, persistentListOf())
	}

	override val extensions: StateFlow<ImmutableList<MigrationExtensionUI>> by lazy {
		combine(
			loadBrowseExtensionsFlow(),
			novels,
			selectedExtensionId
		) { browse, novelList, selectedId ->
			val currentExtension = novelList.find { it.isSelected }?.extensionId ?: -1
			val installed = browse.filter { it.isInstalled && it.id != currentExtension }
			val effective = if (installed.any { it.id == selectedId }) {
				selectedId
			} else {
				installed.firstOrNull()?.id ?: -1
			}
			val items = installed.map { extension ->
				MigrationExtensionUI(
					id = extension.id,
					name = extension.name,
					imageURL = extension.imageURL,
					isSelected = extension.id == effective,
				)
			}.toImmutableList()
			items to effective
		}.onEach { (_, effective) ->
			if (selectedExtensionId.value != effective) selectedExtensionId.value = effective
		}.map { it.first }
			.onIO()
			.stateIn(viewModelScopeIO, SharingStarted.Lazily, persistentListOf())
	}

	override fun setWorkingOn(novelId: Int) {
		which.value = novelId
		queryTouched = false
		clearSearch()
	}

	override fun setNovels(array: List<Int>) {
		if (novelIds.value == array) return
		novelIds.value = array
		if (which.value !in array) which.value = array.firstOrNull() ?: -1
	}

	override fun setSelectedExtension(extensionUI: MigrationExtensionUI) {
		selectedExtensionId.value = extensionUI.id
		clearSearch()
	}

	override fun setQuery(newQuery: String) {
		queryTouched = true
		query.value = newQuery
	}

	override fun search() {
		if (isWorking.value) return
		val extensionId = selectedExtensionId.value
		if (extensionId <= 0) {
			error.value = application.getString(R.string.migration_no_extensions)
			return
		}
		viewModelScopeIO.launch {
			isWorking.value = true
			error.value = null
			try {
				results.value = migrateNovel.search(extensionId, query.value)
				hasSearched.value = true
			} catch (e: Exception) {
				results.value = emptyList()
				hasSearched.value = true
				error.value = e.userMessage()
			} finally {
				isWorking.value = false
			}
		}
	}

	override fun selectCandidate(candidate: MigrationCandidate) {
		if (isWorking.value) return
		val extensionId = selectedExtensionId.value
		val sourceId = which.value
		if (extensionId <= 0 || sourceId <= 0) return
		viewModelScopeIO.launch {
			isWorking.value = true
			error.value = null
			try {
				comparison.value = migrateNovel.prepare(sourceId, extensionId, candidate.url)
			} catch (e: Exception) {
				error.value = e.userMessage()
			} finally {
				isWorking.value = false
			}
		}
	}

	override fun dismissComparison() {
		comparison.value = null
	}

	override fun confirm() {
		if (isWorking.value) return
		val current = comparison.value ?: return
		viewModelScopeIO.launch {
			isWorking.value = true
			error.value = null
			try {
				val id = migrateNovel.commit(current.source.novelId, current.destination.novelId)
				comparison.value = null
				clearSearch()
				val remaining = novelIds.value.filterNot { it == current.source.novelId }
				novelIds.value = remaining
				which.value = remaining.firstOrNull() ?: -1
				queryTouched = false
				migratedEvents.tryEmit(id)
			} catch (e: Exception) {
				error.value = e.userMessage()
			} finally {
				isWorking.value = false
			}
		}
	}

	override fun undo(migrationId: Int) {
		viewModelScopeIO.launch {
			try {
				migrateNovel.undo(migrationId)
			} catch (e: Exception) {
				error.value = e.userMessage()
			}
		}
	}

	override fun hasRemainingNovels(): Boolean = novelIds.value.isNotEmpty()

	private fun clearSearch() {
		results.value = emptyList()
		hasSearched.value = false
		comparison.value = null
		error.value = null
	}

	private fun Throwable.userMessage(): String =
		message?.takeIf { it.isNotBlank() } ?: application.getString(R.string.migration_failed)
}
