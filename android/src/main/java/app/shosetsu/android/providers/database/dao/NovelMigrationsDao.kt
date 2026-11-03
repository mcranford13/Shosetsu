package app.shosetsu.android.providers.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.shosetsu.android.domain.model.database.DBNovelMigrationEntity
import app.shosetsu.android.providers.database.dao.base.BaseDao
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

@Dao
interface NovelMigrationsDao : BaseDao<DBNovelMigrationEntity> {
	@Query("SELECT * FROM novel_migrations WHERE id = :id LIMIT 1")
	suspend fun get(id: Int): DBNovelMigrationEntity?

	@Query(
		"SELECT * FROM novel_migrations WHERE toNovelId = :novelId ORDER BY createdAt DESC LIMIT 1"
	)
	suspend fun latest(novelId: Int): DBNovelMigrationEntity?

	@Query(
		"SELECT * FROM novel_migrations WHERE toNovelId = :novelId ORDER BY createdAt DESC LIMIT 1"
	)
	fun latestFlow(novelId: Int): Flow<DBNovelMigrationEntity?>

	@Query("DELETE FROM novel_migrations WHERE id = :id")
	suspend fun deleteById(id: Int)
}
