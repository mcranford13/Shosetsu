package app.shosetsu.android.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shosetsu.android.R
import app.shosetsu.android.view.compose.setting.widget.ListPreferenceWidget
import app.shosetsu.android.view.uimodels.model.reader.ReaderVoiceCast
import app.shosetsu.android.view.uimodels.model.reader.ReaderVoiceOption

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReaderCastSheet(
	visible: Boolean,
	members: List<ReaderVoiceCast>,
	voices: List<ReaderVoiceOption>,
	onDismiss: () -> Unit,
	onVoiceSelected: (characterKey: String, voicePackId: String) -> Unit,
) {
	if (!visible) return
	val entries = voices.associate { it.id to it.label }
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = 24.dp)
		) {
			Text(
				text = stringResource(R.string.reader_tts_cast),
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			)
			if (voices.isEmpty()) {
				Text(
					text = stringResource(R.string.reader_tts_cast_empty),
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
				)
			}
			members.forEach { member ->
				ListPreferenceWidget(
					value = member.voicePackId,
					title = member.displayName,
					subtitle = entries[member.voicePackId]
						?: stringResource(R.string.reader_tts_missing),
					icon = null,
					iconDescription = null,
					entries = entries,
					onValueChange = { onVoiceSelected(member.characterKey, it) },
				)
			}
		}
	}
}
