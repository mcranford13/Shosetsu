package app.shosetsu.android.domain.model.local

import app.shosetsu.android.domain.model.database.DBNovelMigrationEntity

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

data class NovelMigrationEntity(
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
) {
	fun toDB(): DBNovelMigrationEntity = DBNovelMigrationEntity(
		id = id,
		fromNovelId = fromNovelId,
		toNovelId = toNovelId,
		toWasBookmarked = toWasBookmarked,
		fromPinned = fromPinned,
		toPinned = toPinned,
		fromCategoryIds = fromCategoryIds,
		toCategoryIds = toCategoryIds,
		transfersJson = transfersJson,
		createdAt = createdAt,
	)
}

fun DBNovelMigrationEntity.toLocal(): NovelMigrationEntity = NovelMigrationEntity(
	id = id,
	fromNovelId = fromNovelId,
	toNovelId = toNovelId,
	toWasBookmarked = toWasBookmarked,
	fromPinned = fromPinned,
	toPinned = toPinned,
	fromCategoryIds = fromCategoryIds,
	toCategoryIds = toCategoryIds,
	transfersJson = transfersJson,
	createdAt = createdAt,
)
