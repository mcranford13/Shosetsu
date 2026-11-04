package app.shosetsu.android.domain.repository.impl

import app.shosetsu.android.common.ext.onIO
import app.shosetsu.android.domain.model.local.NovelMigrationEntity
import app.shosetsu.android.domain.model.local.toLocal
import app.shosetsu.android.domain.repository.base.INovelMigrationsRepository
import app.shosetsu.android.providers.database.dao.NovelMigrationsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class NovelMigrationsRepository(
	private val dao: NovelMigrationsDao,
) : INovelMigrationsRepository {
	override suspend fun insert(entity: NovelMigrationEntity): Int =
		onIO { dao.insertAbort(entity.toDB()).toInt() }

	override suspend fun get(id: Int): NovelMigrationEntity? =
		onIO { dao.get(id)?.toLocal() }

	override suspend fun latestForDestination(novelId: Int): NovelMigrationEntity? =
		onIO { dao.latest(novelId)?.toLocal() }

	override fun latestForDestinationFlow(novelId: Int): Flow<NovelMigrationEntity?> =
		dao.latestFlow(novelId).map { it?.toLocal() }.onIO()

	override suspend fun delete(id: Int) {
		onIO { dao.deleteById(id) }
	}
}
