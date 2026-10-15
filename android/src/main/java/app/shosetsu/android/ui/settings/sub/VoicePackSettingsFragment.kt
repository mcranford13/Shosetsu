package app.shosetsu.android.ui.settings.sub

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shosetsu.android.R
import app.shosetsu.android.common.ext.viewModelDi
import app.shosetsu.android.view.compose.NavigateBackButton
import app.shosetsu.android.viewmodel.abstracted.settings.AReaderSettingsViewModel
import app.shosetsu.android.viewmodel.impl.settings.readerVoicePacks

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with shosetsu. If not, see <https://www.gnu.org/licenses/>.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePackSettingsView(onBack: () -> Unit) {
	val viewModel: AReaderSettingsViewModel = viewModelDi()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.reader_tts_voice_packs)) },
				navigationIcon = { NavigateBackButton(onBack) },
			)
		},
	) { paddingValues ->
		LazyColumn(
			contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp),
			modifier = Modifier.padding(paddingValues),
		) {
			item { viewModel.readerVoicePacks() }
		}
	}
}
