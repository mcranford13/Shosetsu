package app.shosetsu.android.ui.reader.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shosetsu.android.R
import app.shosetsu.android.view.compose.DiscreteSlider
import app.shosetsu.android.view.compose.SimpleIconButton
import app.shosetsu.android.view.compose.setting.GenericBottomSettingLayout
import app.shosetsu.android.view.uimodels.StableHolder
import app.shosetsu.android.view.uimodels.model.NovelReaderSettingUI
import app.shosetsu.android.view.uimodels.model.reader.TTSPlayback
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReaderBottomSheetContent(
	scaffoldState: BottomSheetScaffoldState,
	ttsPlayback: TTSPlayback,
	isBookmarked: Boolean,
	isRotationLocked: Boolean,
	setting: NovelReaderSettingUI,

	toggleRotationLock: () -> Unit,
	toggleBookmark: () -> Unit,
	exit: () -> Unit,
	onPlayTTS: () -> Unit,
	onPauseTTS: () -> Unit,
	onStopTTS: () -> Unit,
	currentSpeaker: String? = null,
	onOpenCast: () -> Unit = {},
	updateSetting: (NovelReaderSettingUI) -> Unit,
	lowerSheet: LazyListScope.() -> Unit,
	toggleFocus: () -> Unit,
	onShowNavigation: (() -> Unit)?
) {
	val coroutineScope = rememberCoroutineScope()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		SimpleIconButton(
			Icons.AutoMirrored.Filled.ArrowBack,
			stringResource(R.string.reader_action_back),
			onClick = exit,
		)

		Row {
			SimpleIconButton(
				Icons.Outlined.VisibilityOff,
				stringResource(R.string.reader_action_focus),
				onClick = toggleFocus
			)
			SimpleIconButton(
				if (!isBookmarked) Icons.Outlined.BookmarkBorder
				else Icons.Outlined.Bookmark,
				stringResource(R.string.reader_action_bookmark),
				onClick = toggleBookmark
			)

			SimpleIconButton(
				if (!isRotationLocked) Icons.Outlined.ScreenRotation
				else Icons.Outlined.ScreenLockRotation,
				stringResource(R.string.reader_action_rotation),
				onClick = toggleRotationLock
			)

			if (ttsPlayback != TTSPlayback.Playing)
				SimpleIconButton(
					Icons.Outlined.Audiotrack,
					stringResource(R.string.reader_action_play),
					onClick = onPlayTTS
				)

			if (ttsPlayback == TTSPlayback.Playing)
				SimpleIconButton(
					Icons.Outlined.PauseCircle,
					stringResource(R.string.reader_action_pause),
					onClick = onPauseTTS
				)

			if (ttsPlayback != TTSPlayback.Stopped)
				SimpleIconButton(
					Icons.Outlined.StopCircle,
					stringResource(R.string.reader_tts_stop),
					onClick = onStopTTS
				)

			SimpleIconButton(
				Icons.Outlined.People,
				stringResource(R.string.reader_tts_cast),
				onClick = onOpenCast,
			)

			if (onShowNavigation != null) {
				SimpleIconButton(
					Icons.Outlined.UnfoldLess,
					stringResource(R.string.reader_action_navigation),
					onClick = onShowNavigation
				)
			}
		}

		SimpleIconButton(
			if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
				Icons.Outlined.ExpandMore
			} else {
				Icons.Outlined.ExpandLess
			},
			stringResource(R.string.reader_action_sheet),
			onClick = {
				coroutineScope.launch {
					if (scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded) {
						scaffoldState.bottomSheetState.expand()
					} else {
						scaffoldState.bottomSheetState.partialExpand()
					}
				}
			})
	}

	LazyColumn(
		contentPadding = PaddingValues(vertical = 16.dp)
	) {
		if (currentSpeaker != null) {
			item {
				Text(
					text = stringResource(R.string.reader_tts_speaking, currentSpeaker),
					modifier = Modifier.padding(horizontal = 16.dp),
				)
			}
		}
		item {
			GenericBottomSettingLayout(
				stringResource(R.string.paragraph_spacing),
				"",
				iconDescription = null // No icon
			) {
				DiscreteSlider(
					setting.paragraphSpacingSize,
					"${setting.paragraphSpacingSize}",
					{ it, a ->
						updateSetting(
							setting.copy(
								paragraphSpacingSize = if (!a)
									it.roundToInt().toFloat()
								else it
							)
						)
					},
					remember { StableHolder(0..10) },
				)
			}
		}

		item {
			GenericBottomSettingLayout(
				stringResource(R.string.paragraph_indent),
				"",
				iconDescription = null // No icon
			) {
				DiscreteSlider(
					setting.paragraphIndentSize,
					"${setting.paragraphIndentSize}",
					{ it, _ ->
						updateSetting(setting.copy(paragraphIndentSize = it))
					},
					remember { StableHolder(0..10) },
				)
			}
		}
		lowerSheet()
	}
}
