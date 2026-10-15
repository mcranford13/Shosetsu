package app.shosetsu.android.domain.model.database

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shosetsu.android.domain.model.local.NovelVoiceCastEntity
import app.shosetsu.android.dto.Convertible

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
	tableName = "novel_voice_cast",
	primaryKeys = ["novelId", "characterKey"],
	foreignKeys = [
		ForeignKey(
			entity = DBNovelEntity::class,
			parentColumns = ["id"],
			childColumns = ["novelId"],
			onDelete = ForeignKey.CASCADE
		),
	]
)
data class DBNovelVoiceCastEntity(
	val novelId: Int,
	val characterKey: String,
	val displayName: String,
	val voicePackId: String,
) : Convertible<NovelVoiceCastEntity> {
	override fun convertTo(): NovelVoiceCastEntity = NovelVoiceCastEntity(
		novelId = novelId,
		characterKey = characterKey,
		displayName = displayName,
		voicePackId = voicePackId,
	)
}
