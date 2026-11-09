package app.shosetsu.android.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.shosetsu.android.R
import app.shosetsu.android.backend.dictionary.DictionaryEntry
import app.shosetsu.android.backend.dictionary.DictionaryStatus
import app.shosetsu.android.backend.dictionary.EnglishDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class DefinitionAnchor(
	val text: String,
	val left: Int,
	val top: Int,
	val right: Int,
	val bottom: Int,
)

@Composable
fun DictionaryBubble(
	anchor: DefinitionAnchor,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val di by closestDI(context)
	val dictionary: EnglishDictionary by di.instance()
	val status by dictionary.status.collectAsState()
	var entries by remember(anchor.text) { mutableStateOf<List<DictionaryEntry>?>(null) }
	val tooLong = remember(anchor.text) {
		anchor.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } > 4
	}

	LaunchedEffect(anchor.text, status) {
		entries = if (status is DictionaryStatus.Ready && !tooLong) {
			withContext(Dispatchers.IO) { dictionary.lookup(anchor.text) }
		} else {
			null
		}
	}

	Popup(
		popupPositionProvider = remember(anchor) {
			AnchorPopupPositionProvider(anchor.left, anchor.top, anchor.bottom)
		},
		onDismissRequest = onDismiss,
		properties = PopupProperties(
			focusable = true,
			dismissOnBackPress = true,
			dismissOnClickOutside = true,
		),
	) {
		Surface(
			shape = MaterialTheme.shapes.large,
			tonalElevation = 6.dp,
			shadowElevation = 8.dp,
			modifier = Modifier
				.padding(8.dp)
				.widthIn(min = 220.dp, max = 320.dp),
		) {
			Column(Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = entries?.firstOrNull()?.word ?: anchor.text.trim(),
						style = MaterialTheme.typography.titleMedium,
						modifier = Modifier
							.weight(1f)
							.padding(top = 8.dp),
						maxLines = 2,
					)
					IconButton(onClick = onDismiss) {
						Icon(Icons.Filled.Close, stringResource(R.string.dictionary_close))
					}
				}
				DictionaryBubbleBody(
					status = status,
					entries = entries,
					tooLong = tooLong,
					query = anchor.text.trim(),
					onDownload = dictionary::startDownload,
				)
			}
		}
	}
}

@Composable
private fun DictionaryBubbleBody(
	status: DictionaryStatus,
	entries: List<DictionaryEntry>?,
	tooLong: Boolean,
	query: String,
	onDownload: () -> Unit,
) {
	when {
		tooLong -> Text(
			stringResource(R.string.dictionary_select_word),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(end = 12.dp),
		)
		status is DictionaryStatus.Missing || status is DictionaryStatus.Failed -> {
			Text(
				text = if (status is DictionaryStatus.Failed) status.message
				else stringResource(R.string.dictionary_missing_bubble),
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
			)
			Button(
				onClick = onDownload,
				modifier = Modifier.padding(end = 12.dp),
			) {
				Text(stringResource(R.string.dictionary_download))
			}
		}
		status is DictionaryStatus.Downloading || status is DictionaryStatus.Indexing -> {
			val label = if (status is DictionaryStatus.Downloading) {
				stringResource(R.string.dictionary_desc_downloading, status.percent)
			} else {
				stringResource(R.string.dictionary_desc_indexing, (status as DictionaryStatus.Indexing).percent)
			}
			Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
			LinearProgressIndicator(
				progress = {
					val percent = when (status) {
						is DictionaryStatus.Downloading -> status.percent
						is DictionaryStatus.Indexing -> status.percent
						else -> 0
					}
					percent / 100f
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 8.dp, end = 12.dp),
			)
		}
		entries == null -> Row(
			modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
			Text(stringResource(R.string.dictionary_working))
		}
		entries.isEmpty() -> Text(
			stringResource(R.string.dictionary_none, query),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(end = 12.dp),
		)
		else -> {
			Column(
				Modifier
					.heightIn(max = 220.dp)
					.verticalScroll(rememberScrollState())
					.padding(end = 12.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				entries.forEach { entry ->
					Column {
						if (!entry.pos.isNullOrBlank()) {
							Text(
								entry.pos,
								style = MaterialTheme.typography.labelLarge,
								fontStyle = FontStyle.Italic,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						Text(entry.definition, style = MaterialTheme.typography.bodyMedium)
					}
				}
			}
			Text(
				stringResource(R.string.dictionary_credit),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 8.dp, end = 12.dp),
			)
		}
	}
}

private class AnchorPopupPositionProvider(
	private val left: Int,
	private val top: Int,
	private val bottom: Int,
) : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val margin = 12
		val gap = 8
		val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
		val x = left.coerceIn(margin, maxX)
		val below = bottom + gap
		val above = top - popupContentSize.height - gap
		val y = when {
			below + popupContentSize.height <= windowSize.height - margin -> below
			above >= margin -> above
			else -> (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
		}
		return IntOffset(x, y)
	}
}
