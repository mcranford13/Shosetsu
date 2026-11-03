package app.shosetsu.android.ui.migration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shosetsu.android.R
import app.shosetsu.android.common.enums.AppThemes
import app.shosetsu.android.common.ext.viewModelDi
import app.shosetsu.android.domain.model.local.MigrationCandidate
import app.shosetsu.android.domain.model.local.MigrationComparison
import app.shosetsu.android.domain.model.local.MigrationNovelSummary
import app.shosetsu.android.ui.theme.ShosetsuTheme
import app.shosetsu.android.view.compose.ImageLoadingError
import app.shosetsu.android.view.compose.NavigateBackButton
import app.shosetsu.android.view.compose.placeholder
import app.shosetsu.android.view.uimodels.model.MigrationExtensionUI
import app.shosetsu.android.view.uimodels.model.MigrationNovelUI
import app.shosetsu.android.viewmodel.abstracted.AMigrationViewModel
import app.shosetsu.lib.Novel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.collections.immutable.ImmutableList

/*
 * This file is part of Shosetsu.
 *
 * Shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 * ====================================================================
 */


/**
 * Shosetsu
 * 9 / June / 2019
 *
 * @author github.com/doomsdayrs
 */

@Composable
fun MigrationView(
	novelIds: List<Int>,
	onBack: () -> Unit,
) {
	val viewModel: AMigrationViewModel = viewModelDi()
	val hostState = remember { SnackbarHostState() }
	val context = LocalContext.current
	LaunchedEffect(novelIds) {
		viewModel.setNovels(novelIds)
	}
	LaunchedEffect(viewModel) {
		viewModel.migrated.collect { id ->
			val result = hostState.showSnackbar(
				message = context.getString(R.string.migration_done),
				actionLabel = context.getString(R.string.migration_undo)
			)
			if (result == SnackbarResult.ActionPerformed) viewModel.undo(id)
			if (!viewModel.hasRemainingNovels()) onBack()
		}
	}

	val comparison by viewModel.comparison.collectAsState()
	MigrationContent(
		viewModel = viewModel,
		hostState = hostState,
		onBack = {
			if (comparison != null) viewModel.dismissComparison() else onBack()
		}
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationContent(
	viewModel: AMigrationViewModel,
	hostState: SnackbarHostState,
	onBack: () -> Unit,
) {
	val novelList by viewModel.novels.collectAsState()
	val extensionsToSelect by viewModel.extensions.collectAsState()
	val currentQuery by viewModel.currentQuery.collectAsState()
	val results by viewModel.results.collectAsState()
	val hasSearched by viewModel.hasSearched.collectAsState()
	val isWorking by viewModel.isWorking.collectAsState()
	val error by viewModel.error.collectAsState()
	val comparison by viewModel.comparison.collectAsState()
	val selectedIndex = novelList.indexOfFirst { it.isSelected }

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.migrate_source)) },
				navigationIcon = { NavigateBackButton(onBack) }
			)
		},
		snackbarHost = { SnackbarHost(hostState) }
	) { padding ->
		if (comparison != null) {
			MigrationComparisonContent(
				comparison = comparison!!,
				isWorking = isWorking,
				error = error,
				onConfirm = viewModel::confirm,
				modifier = Modifier.padding(padding)
			)
		} else {
			Column(
				modifier = Modifier
					.padding(padding)
					.fillMaxSize()
			) {
				if (novelList.size > 1 && selectedIndex >= 0) {
					Text(
						text = stringResource(
							R.string.migration_queue,
							selectedIndex + 1,
							novelList.size
						),
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
						style = MaterialTheme.typography.titleMedium
					)
				}
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(180.dp)
				) {
					MigrationNovelsContent(list = novelList) {
						viewModel.setWorkingOn(it.id)
					}
				}
				OutlinedTextField(
					value = currentQuery,
					onValueChange = viewModel::setQuery,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					label = { Text(stringResource(R.string.migration_with_name)) },
					singleLine = true
				)
				Text(
					text = stringResource(R.string.migration_in),
					modifier = Modifier.padding(start = 16.dp, top = 12.dp)
				)
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(88.dp)
				) {
					if (extensionsToSelect.isEmpty()) {
						Text(
							text = stringResource(R.string.migration_no_extensions),
							modifier = Modifier.padding(16.dp)
						)
					} else {
						MigrationExtensionsContent(
							list = extensionsToSelect,
							onClick = viewModel::setSelectedExtension
						)
					}
				}
				Button(
					onClick = viewModel::search,
					enabled = !isWorking && extensionsToSelect.isNotEmpty(),
					modifier = Modifier
						.padding(horizontal = 16.dp, vertical = 8.dp)
						.align(Alignment.End)
				) {
					Text(stringResource(R.string.search))
				}
				if (isWorking) {
					CircularProgressIndicator(
						modifier = Modifier
							.padding(16.dp)
							.align(Alignment.CenterHorizontally)
					)
				}
				if (!error.isNullOrBlank()) {
					Text(
						text = error!!,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
					)
				}
				when {
					results.isNotEmpty() -> MigrationResultsContent(
						results = results,
						onClick = viewModel::selectCandidate,
						modifier = Modifier.weight(1f)
					)
					hasSearched && !isWorking -> Text(
						text = stringResource(R.string.migration_no_results),
						modifier = Modifier.padding(16.dp)
					)
					else -> Text(
						text = stringResource(R.string.migration_search_hint),
						modifier = Modifier.padding(16.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun MigrationResultsContent(
	results: List<MigrationCandidate>,
	onClick: (MigrationCandidate) -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier.fillMaxWidth(),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		items(results) { candidate ->
			Card(onClick = { onClick(candidate) }, modifier = Modifier.fillMaxWidth()) {
				Row(
					modifier = Modifier.padding(8.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					MigrationCover(candidate.imageURL, candidate.title, Modifier.size(56.dp))
					Text(
						text = candidate.title,
						modifier = Modifier.padding(start = 12.dp),
						style = MaterialTheme.typography.titleMedium
					)
				}
			}
		}
	}
}

@Composable
private fun MigrationComparisonContent(
	comparison: MigrationComparison,
	isWorking: Boolean,
	error: String?,
	onConfirm: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(stringResource(R.string.migration_downloads_stay))
		Text(
			text = stringResource(
				R.string.migration_read_match,
				comparison.matchedReadChapters,
				comparison.readChapters
			),
			style = MaterialTheme.typography.titleMedium
		)
		if (comparison.alreadyInLibrary) {
			Text(stringResource(R.string.migration_already_in_library))
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			MigrationSummaryColumn(
				heading = stringResource(R.string.migration_from),
				summary = comparison.source,
				modifier = Modifier.weight(1f)
			)
			MigrationSummaryColumn(
				heading = stringResource(R.string.migration_to),
				summary = comparison.destination,
				modifier = Modifier.weight(1f)
			)
		}
		if (!error.isNullOrBlank()) {
			Text(text = error, color = MaterialTheme.colorScheme.error)
		}
		Button(
			onClick = onConfirm,
			enabled = !isWorking,
			modifier = Modifier.fillMaxWidth()
		) {
			Text(stringResource(R.string.migration_confirm))
		}
		if (isWorking) {
			CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
		}
	}
}

@Composable
private fun MigrationSummaryColumn(
	heading: String,
	summary: MigrationNovelSummary,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(heading, fontWeight = FontWeight.Bold)
		MigrationCover(summary.imageURL, summary.title, Modifier
			.fillMaxWidth()
			.height(140.dp))
		Text(summary.title, fontWeight = FontWeight.Bold)
		if (summary.sourceName.isNotBlank()) {
			Text(stringResource(R.string.migration_source, summary.sourceName))
		}
		Text(stringResource(R.string.migration_status, novelStatusLabel(summary.status)))
		if (summary.authors.isNotBlank()) {
			Text(stringResource(R.string.migration_authors, summary.authors))
		}
		Text(stringResource(R.string.migration_chapters, summary.chapterCount))
		if (summary.latestChapterTitle.isBlank()) {
			Text(stringResource(R.string.migration_latest_unknown))
		} else {
			Text(stringResource(R.string.migration_latest, summary.latestChapterTitle))
			if (summary.latestReleaseDate.isNotBlank()) Text(summary.latestReleaseDate)
		}
	}
}

@Composable
private fun novelStatusLabel(status: Novel.Status): String = when (status) {
	Novel.Status.PUBLISHING -> stringResource(R.string.publishing)
	Novel.Status.COMPLETED -> stringResource(R.string.completed)
	Novel.Status.PAUSED -> stringResource(R.string.paused)
	Novel.Status.UNKNOWN -> stringResource(R.string.unknown)
}

@Composable
private fun MigrationCover(imageURL: String, title: String, modifier: Modifier) {
	if (imageURL.isNotEmpty()) {
		SubcomposeAsyncImage(
			ImageRequest.Builder(LocalContext.current)
				.data(imageURL)
				.crossfade(true)
				.build(),
			contentDescription = stringResource(R.string.novel_image_content_description, title),
			modifier = modifier,
			error = { ImageLoadingError() },
			loading = { Box(Modifier.placeholder(true)) }
		)
	} else {
		ImageLoadingError(title, modifier)
	}
}

@Composable
fun MigrationExtensionsContent(
	list: ImmutableList<MigrationExtensionUI>,
	onClick: (MigrationExtensionUI) -> Unit
) {
	LazyRow(
		horizontalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxWidth()
	) {
		items(items = list, key = { it.id }) { extensionUI ->
			MigrationExtensionItemContent(extensionUI, onClick = onClick)
		}
	}
}

@ExperimentalMaterial3Api
@Preview
@Composable
fun PreviewMigrationExtensionItemContent() = ShosetsuTheme(AppThemes.LIGHT) {
	val item by remember {
		mutableStateOf(
			MigrationExtensionUI(
				0,
				"This is a novel",
				"",
				false
			)
		)
	}
	Box(modifier = Modifier.height(200.dp)) {
		MigrationExtensionItemContent(item = item) {
			println("Test")
		}
	}
}

@Composable
fun MigrationExtensionItemContent(
	item: MigrationExtensionUI,
	onClick: (MigrationExtensionUI) -> Unit
) {
	Card(
		modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
		shape = RoundedCornerShape(16.dp),
		border =
			if (item.isSelected) {
				BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
			} else {
				null
			},

		onClick = { onClick(item) },
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (item.imageURL.isNotEmpty()) {
				SubcomposeAsyncImage(
					ImageRequest.Builder(LocalContext.current)
						.data(item.imageURL)
						.crossfade(true)
						.build(),
					contentDescription = stringResource(R.string.novel_image_content_description, item.name),
					modifier = Modifier.size(64.dp),
					error = {
						ImageLoadingError()
					},
					loading = {
						Box(Modifier.placeholder(true))
					}
				)
			} else {
				ImageLoadingError(Modifier.size(64.dp))
			}
			Text(
				text = item.name,
				modifier = Modifier.padding(end = 16.dp)
			)
		}
	}
}

@Composable
fun MigrationNovelsContent(
	list: ImmutableList<MigrationNovelUI>,
	onClick: (MigrationNovelUI) -> Unit
) {
	LazyRow(
		horizontalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxWidth()
	) {
		items(items = list, key = { it.id }) { novelUI ->
			MigrationNovelItemContent(item = novelUI, onClick = onClick)
		}
	}
}

@ExperimentalMaterial3Api
@Composable
@Preview
fun PreviewMigrationNovelItemRowContent() = ShosetsuTheme(AppThemes.LIGHT) {
	val item by remember {
		mutableStateOf(
			MigrationNovelUI(
				0,
				"This is a novel",
				"",
				false
			)
		)
	}
	Row(
		modifier = Modifier
			.height(200.dp)
			.width(600.dp)
	) {
		MigrationNovelItemContent(item = item) {
			println("Test")
		}
		MigrationNovelItemContent(item = item) {
			println("Test")
		}
	}
}

@ExperimentalMaterial3Api
@Composable
@Preview
fun PreviewMigrationNovelItemContent() = ShosetsuTheme(AppThemes.LIGHT) {
	val item by remember {
		mutableStateOf(
			MigrationNovelUI(
				0,
				"This is a novel",
				"",
				false
			)
		)
	}
	Box(modifier = Modifier.height(200.dp)) {
		MigrationNovelItemContent(item = item) {
			println("Test")
		}
	}
}

@Composable
fun MigrationNovelItemContent(item: MigrationNovelUI, onClick: (MigrationNovelUI) -> Unit) {
	Card(
		onClick = { onClick(item) },
		border =
			if (item.isSelected) {
				BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
			} else {
				null
			},
		modifier = Modifier.aspectRatio(.70f)
	) {
		val blackTrans = colorResource(id = R.color.black_trans)
		Box {
			val modifier = Modifier
				.fillMaxSize()
				.drawWithContent {
					drawContent()
					drawRect(
						Brush.verticalGradient(
							colors = listOf(
								Color.Transparent,
								blackTrans
							),
						)
					)
				}
			if (item.imageURL.isNotEmpty()) {
				SubcomposeAsyncImage(
					ImageRequest.Builder(LocalContext.current)
						.data(item.imageURL)
						.crossfade(true)
						.build(),
					contentDescription = stringResource(R.string.novel_image_content_description, item.title),
					modifier = modifier,
					error = {
						ImageLoadingError(item.title)
					},
					loading = {
						Box(Modifier.placeholder(true))
					}
				)
			} else {
				ImageLoadingError(item.title, modifier)
			}

			Text(
				text = item.title,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(8.dp),
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center
			)
		}
	}
}
