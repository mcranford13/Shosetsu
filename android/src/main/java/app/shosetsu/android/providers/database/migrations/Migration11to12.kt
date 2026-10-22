package app.shosetsu.android.providers.database.migrations

import android.database.SQLException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val CREATE_NOVEL_VOICE_CAST_TABLE_SQL = """
	CREATE TABLE IF NOT EXISTS `novel_voice_cast` (
		`novelId` INTEGER NOT NULL,
		`characterKey` TEXT NOT NULL,
		`displayName` TEXT NOT NULL,
		`voicePackId` TEXT NOT NULL,
		PRIMARY KEY(`novelId`, `characterKey`),
		FOREIGN KEY(`novelId`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
	)
"""

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

object Migration11to12 : Migration(11, 12) {
	@Throws(SQLException::class)
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(CREATE_NOVEL_VOICE_CAST_TABLE_SQL.trimIndent())
	}
}
