package app.shosetsu.android.viewmodel.abstracted

import app.shosetsu.android.domain.model.local.MigrationCandidate
import app.shosetsu.android.domain.model.local.MigrationComparison
import app.shosetsu.android.view.uimodels.model.MigrationExtensionUI
import app.shosetsu.android.view.uimodels.model.MigrationNovelUI
import app.shosetsu.android.viewmodel.base.ShosetsuViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
abstract class AMigrationViewModel : ShosetsuViewModel() {

	abstract val currentQuery: StateFlow<String>

	abstract val extensions: StateFlow<ImmutableList<MigrationExtensionUI>>

	abstract val novels: StateFlow<ImmutableList<MigrationNovelUI>>

	abstract val which: StateFlow<Int>

	abstract val results: StateFlow<List<MigrationCandidate>>

	abstract val hasSearched: StateFlow<Boolean>

	abstract val isWorking: StateFlow<Boolean>

	abstract val error: StateFlow<String?>

	abstract val comparison: StateFlow<MigrationComparison?>

	/** Id of a migration that just finished, so the screen can offer Undo. */
	abstract val migrated: SharedFlow<Int>

	abstract fun setWorkingOn(novelId: Int)

	abstract fun setNovels(array: List<Int>)

	abstract fun setSelectedExtension(extensionUI: MigrationExtensionUI)

	abstract fun setQuery(newQuery: String)

	abstract fun search()

	abstract fun selectCandidate(candidate: MigrationCandidate)

	abstract fun dismissComparison()

	abstract fun confirm()

	abstract fun undo(migrationId: Int)

	abstract fun hasRemainingNovels(): Boolean
}
