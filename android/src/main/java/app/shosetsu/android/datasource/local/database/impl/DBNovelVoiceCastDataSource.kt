package app.shosetsu.android.datasource.local.database.impl

import app.shosetsu.android.datasource.local.database.base.IDBNovelVoiceCastDataSource
import app.shosetsu.android.domain.model.database.DBNovelVoiceCastEntity
import app.shosetsu.android.domain.model.local.NovelVoiceCastEntity
import app.shosetsu.android.providers.database.dao.NovelVoiceCastDao
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

class DBNovelVoiceCastDataSource(
	private val dao: NovelVoiceCastDao,
) : IDBNovelVoiceCastDataSource {
	override fun getFlow(novelId: Int): Flow<List<NovelVoiceCastEntity>> =
		dao.getFlow(novelId).map { rows -> rows.map { it.convertTo() } }

	override suspend fun getAll(novelId: Int): List<NovelVoiceCastEntity> =
		dao.getAll(novelId).map { it.convertTo() }

	override suspend fun get(novelId: Int, characterKey: String): NovelVoiceCastEntity? =
		dao.get(novelId, characterKey)?.convertTo()

	override suspend fun upsert(entity: NovelVoiceCastEntity) {
		dao.insertReplace(
			DBNovelVoiceCastEntity(
				novelId = entity.novelId,
				characterKey = entity.characterKey,
				displayName = entity.displayName,
				voicePackId = entity.voicePackId,
			)
		)
	}
}
