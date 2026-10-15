package app.shosetsu.android.domain.repository.impl

import app.shosetsu.android.common.ext.onIO
import app.shosetsu.android.datasource.local.database.base.IDBNovelVoiceCastDataSource
import app.shosetsu.android.domain.model.local.NovelVoiceCastEntity
import app.shosetsu.android.domain.repository.base.INovelVoiceCastRepository
import kotlinx.coroutines.flow.Flow

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

class NovelVoiceCastRepository(
	private val database: IDBNovelVoiceCastDataSource,
) : INovelVoiceCastRepository {
	override fun getFlow(novelId: Int): Flow<List<NovelVoiceCastEntity>> =
		database.getFlow(novelId).onIO()

	override suspend fun getAll(novelId: Int): List<NovelVoiceCastEntity> =
		onIO { database.getAll(novelId) }

	override suspend fun get(novelId: Int, characterKey: String): NovelVoiceCastEntity? =
		onIO { database.get(novelId, characterKey) }

	override suspend fun upsert(entity: NovelVoiceCastEntity) {
		onIO { database.upsert(entity) }
	}
}
