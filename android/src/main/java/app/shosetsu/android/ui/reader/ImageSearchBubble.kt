package app.shosetsu.android.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.shosetsu.android.R
import app.shosetsu.android.backend.images.CommonsImageSearch
import app.shosetsu.android.backend.images.MAX_IMAGE_CHARS
import app.shosetsu.android.backend.images.MAX_IMAGE_WORDS
import app.shosetsu.android.backend.images.WordImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
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

@Composable
fun ImageSearchBubble(
	anchor: DefinitionAnchor,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val di by closestDI(context)
	val search: CommonsImageSearch by di.instance()
	val query = anchor.text.trim()
	val tooLong = remember(query) {
		val words = query.split(Regex("\\s+")).count { it.isNotEmpty() }
		words > MAX_IMAGE_WORDS || query.length > MAX_IMAGE_CHARS
	}
	var images by remember(query) { mutableStateOf<List<WordImage>?>(null) }
	var failed by remember(query) { mutableStateOf(false) }

	LaunchedEffect(query, tooLong) {
		if (tooLong) return@LaunchedEffect
		failed = false
		images = null
		val result = withContext(Dispatchers.IO) {
			runCatching { search.search(query) }
		}
		failed = result.isFailure
		images = result.getOrDefault(emptyList())
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
				.widthIn(min = 220.dp, max = 340.dp),
		) {
			Column(Modifier.padding(16.dp)) {
				Text(
					text = query,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 2,
				)
				ImageSearchBody(
					query = query,
					tooLong = tooLong,
					images = images,
					failed = failed,
				)
			}
		}
	}
}

@Composable
private fun ImageSearchBody(
	query: String,
	tooLong: Boolean,
	images: List<WordImage>?,
	failed: Boolean,
) {
	when {
		tooLong -> Text(
			stringResource(R.string.dictionary_select_word),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 8.dp),
		)
		failed -> Text(
			stringResource(R.string.image_search_failed),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 8.dp),
		)
		images == null -> Row(
			modifier = Modifier.padding(top = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
			Text(stringResource(R.string.image_search_working))
		}
		images.isEmpty() -> Text(
			stringResource(R.string.image_search_none, query),
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 8.dp),
		)
		else -> {
			Row(
				Modifier
					.padding(top = 8.dp)
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				val description = stringResource(R.string.image_search_of, query)
				images.forEach { image ->
					SubcomposeAsyncImage(
						model = ImageRequest.Builder(LocalContext.current)
							.data(image.thumbUrl)
							.crossfade(true)
							.build(),
						contentDescription = description,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.width(148.dp)
							.height(148.dp)
							.clip(MaterialTheme.shapes.medium),
						loading = {
							CircularProgressIndicator(
								Modifier
									.size(18.dp)
									.align(Alignment.Center),
								strokeWidth = 2.dp,
							)
						},
					)
				}
			}
			Text(
				stringResource(R.string.image_search_credit),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 8.dp),
			)
		}
	}
}
