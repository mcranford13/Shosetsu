package app.shosetsu.android.viewmodel.impl.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.shosetsu.android.R
import app.shosetsu.android.backend.tts.DownloadStatus
import app.shosetsu.android.backend.tts.VoiceEngine
import app.shosetsu.android.backend.tts.VoicePackManager
import app.shosetsu.android.common.SettingKey
import app.shosetsu.android.common.ext.toast
import app.shosetsu.android.view.compose.setting.SliderSettingContent
import app.shosetsu.android.view.compose.setting.widget.ListPreferenceWidget
import app.shosetsu.android.view.compose.setting.widget.TextPreferenceWidget
import app.shosetsu.android.view.uimodels.StableHolder
import app.shosetsu.android.viewmodel.base.ExposedSettingsRepoViewModel
import kotlinx.coroutines.launch
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

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerTtsBackendOption() {
	val scope = rememberCoroutineScope()
	val backend by settingsRepo.getStringFlow(SettingKey.ReaderTtsBackend).collectAsState()
	val system = stringResource(R.string.reader_tts_backend_system)
	val neural = stringResource(R.string.reader_tts_backend_neural)
	val entries = mapOf(SettingKey.TTS_SYSTEM to system, SettingKey.TTS_NEURAL to neural)
	ListPreferenceWidget(
		value = backend,
		title = stringResource(R.string.reader_tts_backend),
		subtitle = entries[backend],
		icon = null,
		iconDescription = null,
		entries = entries,
		onValueChange = { selected ->
			scope.launch { settingsRepo.setString(SettingKey.ReaderTtsBackend, selected) }
		},
	)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerNeuralPitchNote() {
	val backend by settingsRepo.getStringFlow(SettingKey.ReaderTtsBackend).collectAsState()
	if (backend != SettingKey.TTS_NEURAL) return
	TextPreferenceWidget(
		title = stringResource(R.string.reader_tts_neural_pitch),
		modifier = Modifier.fillMaxWidth(),
		iconDescription = null,
	)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerTtsLinePauseOption() {
	SliderSettingContent(
		title = stringResource(R.string.reader_tts_line_pause),
		description = stringResource(R.string.reader_tts_line_pause_desc),
		valueRange = remember { StableHolder(0..1000) },
		parseValue = { "$it ms" },
		repo = settingsRepo,
		key = SettingKey.ReaderTtsLinePause,
		modifier = Modifier.fillMaxWidth(),
		haveSteps = false,
	)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerTtsParagraphPauseOption() {
	SliderSettingContent(
		title = stringResource(R.string.reader_tts_paragraph_pause),
		description = stringResource(R.string.reader_tts_paragraph_pause_desc),
		valueRange = remember { StableHolder(0..2000) },
		parseValue = { "$it ms" },
		repo = settingsRepo,
		key = SettingKey.ReaderTtsParagraphPause,
		modifier = Modifier.fillMaxWidth(),
		haveSteps = false,
	)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerVoicePackFolder(onClick: () -> Unit) {
	TextPreferenceWidget(
		title = stringResource(R.string.reader_tts_voice_packs),
		subtitle = stringResource(R.string.reader_tts_voice_packs_desc),
		modifier = Modifier.fillMaxWidth(),
		iconDescription = null,
		onPreferenceClick = onClick,
	)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerVoicePacks() {
	val context = LocalContext.current
	val di by closestDI(context)
	val packs: VoicePackManager by di.instance()
	val library by packs.library.collectAsState()
	val scope = rememberCoroutineScope()

	TextPreferenceWidget(
		title = stringResource(R.string.reader_tts_espeak),
		subtitle = espeakSubtitle(library.espeak, library.espeakSizeMb),
		modifier = Modifier.fillMaxWidth(),
		iconDescription = null,
		onPreferenceClick = {
			scope.launch {
				try {
					when (library.espeak) {
						DownloadStatus.Ready -> packs.deleteEspeak()
						DownloadStatus.Downloading -> Unit
						else -> packs.downloadEspeak()
					}
				} catch (e: Exception) {
					context.toast(e.message ?: context.getString(R.string.reader_tts_failed))
				}
			}
		},
	)

	library.packs.forEach { pack ->
		TextPreferenceWidget(
			title = if (pack.engine == VoiceEngine.KOKORO) pack.name else "${pack.name} (${pack.language})",
			subtitle = voiceSubtitle(pack.status, pack.description, pack.sizeMb),
			modifier = Modifier.fillMaxWidth(),
			iconDescription = null,
			onPreferenceClick = {
				scope.launch {
					try {
						when (pack.status) {
							DownloadStatus.Ready -> packs.deletePack(pack.id)
							DownloadStatus.Downloading -> Unit
							else -> packs.downloadPack(pack.id)
						}
					} catch (e: Exception) {
						context.toast(e.message ?: context.getString(R.string.reader_tts_failed))
					}
				}
			},
		)
	}
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerNarratorVoiceOption() {
	readerDefaultVoiceOption(stringResource(R.string.reader_tts_narrator_voice), SettingKey.ReaderNarratorVoice)
}

@SuppressLint("ComposableNaming")
@Composable
fun ExposedSettingsRepoViewModel.readerUnknownVoiceOption() {
	readerDefaultVoiceOption(stringResource(R.string.reader_tts_unknown_voice), SettingKey.ReaderUnknownVoice)
}

@Composable
private fun ExposedSettingsRepoViewModel.readerDefaultVoiceOption(
	title: String,
	key: SettingKey<String>,
) {
	val context = LocalContext.current
	val di by closestDI(context)
	val packs: VoicePackManager by di.instance()
	val library by packs.library.collectAsState()
	val scope = rememberCoroutineScope()
	val installed = library.voices.filter { it.status == DownloadStatus.Ready }
	val selection by settingsRepo.getStringFlow(key).collectAsState()
	if (installed.isEmpty()) {
		TextPreferenceWidget(
			title = title,
			subtitle = stringResource(R.string.reader_tts_need_voice),
			modifier = Modifier.fillMaxWidth(),
			iconDescription = null,
		)
		return
	}
	val entries = installed.associate { it.id to "${it.name} (${it.language})" }
	val value = selection.takeIf { it in entries } ?: installed.first().id
	ListPreferenceWidget(
		value = value,
		title = title,
		subtitle = entries[value],
		icon = null,
		iconDescription = null,
		entries = entries,
		onValueChange = { selected ->
			scope.launch { settingsRepo.setString(key, selected) }
		},
	)
}

@Composable
private fun espeakSubtitle(status: DownloadStatus, sizeMb: Int): String {
	val size = stringResource(R.string.reader_tts_about_size, sizeMb)
	val state = statusLabel(status)
	return "$size. $state"
}

@Composable
private fun voiceSubtitle(status: DownloadStatus, description: String, sizeMb: Int): String {
	val size = stringResource(R.string.reader_tts_about_size, sizeMb)
	return "$description. $size. ${statusLabel(status)}"
}

@Composable
private fun statusLabel(status: DownloadStatus): String = when (status) {
	DownloadStatus.Ready -> stringResource(R.string.reader_tts_installed)
	DownloadStatus.Downloading -> stringResource(R.string.reader_tts_downloading)
	DownloadStatus.Failed -> stringResource(R.string.reader_tts_failed)
	DownloadStatus.Missing -> stringResource(R.string.reader_tts_missing)
}
