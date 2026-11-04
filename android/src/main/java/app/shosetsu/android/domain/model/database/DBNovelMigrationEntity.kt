package app.shosetsu.android.domain.model.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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

@Entity(
	tableName = "novel_migrations",
	foreignKeys = [
		ForeignKey(
			entity = DBNovelEntity::class,
			parentColumns = ["id"],
			childColumns = ["fromNovelId"],
			onDelete = ForeignKey.CASCADE
		),
		ForeignKey(
			entity = DBNovelEntity::class,
			parentColumns = ["id"],
			childColumns = ["toNovelId"],
			onDelete = ForeignKey.CASCADE
		),
	],
	indices = [
		Index("fromNovelId"),
		Index("toNovelId"),
	]
)
data class DBNovelMigrationEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Int = 0,
	val fromNovelId: Int,
	val toNovelId: Int,
	val toWasBookmarked: Boolean,
	val fromPinned: Boolean,
	val toPinned: Boolean,
	val fromCategoryIds: String,
	val toCategoryIds: String,
	val transfersJson: String,
	val createdAt: Long,
)
