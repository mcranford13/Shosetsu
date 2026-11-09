package app.shosetsu.android.viewmodel.impl.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.shosetsu.android.R
import app.shosetsu.android.backend.dictionary.DictionaryStatus
import app.shosetsu.android.backend.dictionary.EnglishDictionary
import app.shosetsu.android.view.compose.setting.widget.TextPreferenceWidget
import app.shosetsu.android.viewmodel.base.ExposedSettingsRepoViewModel
import org.kodein.di.android.closestDI
import org.kodein.di.instance

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
fun ExposedSettingsRepoViewModel.readerDictionaryOption() {
	val context = LocalContext.current
	val di by closestDI(context)
	val dictionary: EnglishDictionary by di.instance()
	val status by dictionary.status.collectAsState()
	var confirmRemove by remember { mutableStateOf(false) }

	TextPreferenceWidget(
		title = stringResource(R.string.dictionary_title),
		subtitle = dictionarySubtitle(status),
		modifier = Modifier.fillMaxWidth(),
		iconDescription = null,
		onPreferenceClick = {
			when (status) {
				DictionaryStatus.Ready -> confirmRemove = true
				is DictionaryStatus.Downloading, is DictionaryStatus.Indexing -> Unit
				else -> dictionary.startDownload()
			}
		},
	)

	if (confirmRemove) {
		AlertDialog(
			onDismissRequest = { confirmRemove = false },
			title = { Text(stringResource(R.string.dictionary_remove)) },
			text = { Text(stringResource(R.string.dictionary_remove_desc)) },
			confirmButton = {
				TextButton(onClick = {
					confirmRemove = false
					dictionary.remove()
				}) {
					Text(stringResource(R.string.dictionary_remove))
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmRemove = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

@Composable
private fun dictionarySubtitle(status: DictionaryStatus): String = when (status) {
	DictionaryStatus.Missing -> stringResource(R.string.dictionary_desc_missing)
	is DictionaryStatus.Downloading -> stringResource(R.string.dictionary_desc_downloading, status.percent)
	is DictionaryStatus.Indexing -> stringResource(R.string.dictionary_desc_indexing, status.percent)
	DictionaryStatus.Ready -> stringResource(R.string.dictionary_desc_ready)
	is DictionaryStatus.Failed -> status.message
}
