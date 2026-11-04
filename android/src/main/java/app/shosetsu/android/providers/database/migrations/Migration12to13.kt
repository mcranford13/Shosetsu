package app.shosetsu.android.providers.database.migrations

import android.database.SQLException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val CREATE_NOVEL_MIGRATIONS_TABLE_SQL = """
	CREATE TABLE IF NOT EXISTS `novel_migrations` (
		`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
		`fromNovelId` INTEGER NOT NULL,
		`toNovelId` INTEGER NOT NULL,
		`toWasBookmarked` INTEGER NOT NULL,
		`fromPinned` INTEGER NOT NULL,
		`toPinned` INTEGER NOT NULL,
		`fromCategoryIds` TEXT NOT NULL,
		`toCategoryIds` TEXT NOT NULL,
		`transfersJson` TEXT NOT NULL,
		`createdAt` INTEGER NOT NULL,
		FOREIGN KEY(`fromNovelId`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
		FOREIGN KEY(`toNovelId`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
	)
"""

internal const val CREATE_NOVEL_MIGRATIONS_FROM_INDEX_SQL =
	"CREATE INDEX IF NOT EXISTS `index_novel_migrations_fromNovelId` ON `novel_migrations` (`fromNovelId`)"

internal const val CREATE_NOVEL_MIGRATIONS_TO_INDEX_SQL =
	"CREATE INDEX IF NOT EXISTS `index_novel_migrations_toNovelId` ON `novel_migrations` (`toNovelId`)"

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

object Migration12to13 : Migration(12, 13) {
	@Throws(SQLException::class)
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(CREATE_NOVEL_MIGRATIONS_TABLE_SQL.trimIndent())
		db.execSQL(CREATE_NOVEL_MIGRATIONS_FROM_INDEX_SQL)
		db.execSQL(CREATE_NOVEL_MIGRATIONS_TO_INDEX_SQL)
	}
}
