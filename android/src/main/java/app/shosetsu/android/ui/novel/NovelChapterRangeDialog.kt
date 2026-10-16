package app.shosetsu.android.ui.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shosetsu.android.R
import app.shosetsu.android.common.enums.ReadingStatus
import app.shosetsu.android.view.compose.StandardDialog

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

@Composable
fun NovelChapterRangeDialog(
	chapterCount: Int,
	onDismissRequest: () -> Unit,
	onMark: (first: Int, last: Int, status: ReadingStatus) -> Unit,
) {
	var firstText by rememberSaveable { mutableStateOf("") }
	var lastText by rememberSaveable { mutableStateOf("") }
	var markAsRead by rememberSaveable { mutableStateOf(true) }
	val status = if (markAsRead) ReadingStatus.READ else ReadingStatus.UNREAD
	val first = firstText.toIntOrNull()
	val last = lastText.toIntOrNull()
	val valid =
		first != null && first in 1..chapterCount &&
			last != null && last in 1..chapterCount

	StandardDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(R.string.fragment_novel_mark_range)) },
		onConfirm = {
			if (valid) {
				onMark(first!!, last!!, status)
				onDismissRequest()
			}
		},
		confirmButton = { confirm ->
			TextButton(onClick = confirm, enabled = valid) {
				Text(stringResource(R.string.fragment_novel_apply_range))
			}
		},
	) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(stringResource(R.string.fragment_novel_range_help, chapterCount))
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxWidth(),
			) {
				OutlinedTextField(
					value = firstText,
					onValueChange = { firstText = it.filter(Char::isDigit) },
					label = { Text(stringResource(R.string.fragment_novel_range_start)) },
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					singleLine = true,
					modifier = Modifier.weight(1f),
				)
				OutlinedTextField(
					value = lastText,
					onValueChange = { lastText = it.filter(Char::isDigit) },
					label = { Text(stringResource(R.string.fragment_novel_range_end)) },
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					singleLine = true,
					modifier = Modifier.weight(1f),
				)
			}
			ReadingStatusOption(
				label = stringResource(R.string.read),
				selected = markAsRead,
				onClick = { markAsRead = true },
			)
			ReadingStatusOption(
				label = stringResource(R.string.unread),
				selected = !markAsRead,
				onClick = { markAsRead = false },
			)
		}
	}
}

@Composable
private fun ReadingStatusOption(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		RadioButton(selected = selected, onClick = onClick)
		Text(label)
	}
}
