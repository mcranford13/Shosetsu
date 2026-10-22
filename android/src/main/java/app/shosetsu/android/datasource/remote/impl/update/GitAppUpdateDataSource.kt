package app.shosetsu.android.datasource.remote.impl.update

import app.shosetsu.android.common.EmptyResponseBodyException
import app.shosetsu.android.common.consts.URL_APP_RELEASES
import app.shosetsu.android.common.ext.decodeSafeFromStream
import app.shosetsu.android.common.ext.quickie
import app.shosetsu.android.common.utils.archURL
import app.shosetsu.android.datasource.remote.base.IRemoteAppUpdateDataSource
import app.shosetsu.android.domain.model.local.AppUpdateEntity
import app.shosetsu.lib.Version
import app.shosetsu.lib.exceptions.HTTPException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream

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

/**
 * shosetsu
 * 07 / 09 / 2020
 *
 * Standard releases from the GitHub releases API.
 */
class GitAppUpdateDataSource(
	private val okHttpClient: OkHttpClient
) : IRemoteAppUpdateDataSource, IRemoteAppUpdateDataSource.Downloadable {
	private val json by lazy {
		Json {
			ignoreUnknownKeys = true
		}
	}

	@Serializable
	internal data class GitHubRelease(
		@SerialName("tag_name")
		val tagName: String = "",
		val body: String? = null,
		val draft: Boolean = false,
		val prerelease: Boolean = false,
		@SerialName("published_at")
		val publishedAt: String? = null,
		val assets: List<GitHubAsset> = emptyList(),
	)

	@Serializable
	internal data class GitHubAsset(
		val name: String = "",
		@SerialName("browser_download_url")
		val browserDownloadUrl: String = "",
	)

	@OptIn(ExperimentalSerializationApi::class)
	@Throws(
		EmptyResponseBodyException::class,
		HTTPException::class,
		IOException::class
	)
	override suspend fun loadAppUpdate(): AppUpdateEntity {
		okHttpClient.quickie(URL_APP_RELEASES).use { response ->
			if (!response.isSuccessful) throw HTTPException(response.code)
			val releases = response.body?.use { responseBody ->
				responseBody.byteStream().use {
					json.decodeSafeFromStream<List<GitHubRelease>>(it)
				}
			} ?: throw EmptyResponseBodyException(URL_APP_RELEASES)

			if (releases.isEmpty()) throw EmptyResponseBodyException(URL_APP_RELEASES)

			val (release, asset) = chooseRelease(releases)
				?: throw IOException("No standard APK found in $URL_APP_RELEASES")

			return release.toUpdate(asset)
		}
	}

	@Throws(
		EmptyResponseBodyException::class,
		HTTPException::class,
		IOException::class
	)
	override suspend fun downloadAppUpdate(update: AppUpdateEntity): InputStream {
		okHttpClient.quickie(update.archURL()).let { response ->
			if (response.isSuccessful) {
				return response.body?.byteStream()
					?: throw EmptyResponseBodyException(update.archURL())
			} else throw HTTPException(response.code)
		}
	}

	internal fun GitHubRelease.toUpdate(asset: GitHubAsset) = AppUpdateEntity(
		version = tagName.removePrefix("v").removePrefix("V"),
		versionCode = versionCodeFrom(body),
		url = asset.browserDownloadUrl,
		notes = body.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
	)

	internal fun chooseRelease(
		releases: List<GitHubRelease>
	): Pair<GitHubRelease, GitHubAsset>? {
		var best: Triple<GitHubRelease, GitHubAsset, Version>? = null
		for (release in releases) {
			if (release.draft || release.prerelease) continue
			val version = comparableVersion(release.tagName) ?: continue
			val asset = selectStandardApk(release.assets) ?: continue
			val current = best
			if (current == null) {
				best = Triple(release, asset, version)
				continue
			}
			val compared = version.compareTo(current.third)
			val newerPublished = compared == 0 &&
				release.publishedAt.orEmpty() > current.first.publishedAt.orEmpty()
			if (compared > 0 || newerPublished) {
				best = Triple(release, asset, version)
			}
		}
		return best?.let { it.first to it.second }
	}

	companion object {
		private val versionCodePattern = Regex(
			"""version code\s+(\d+)""",
			RegexOption.IGNORE_CASE
		)

		/** Flavor and debug artifacts must not be installed over the standard package. */
		private val excludedApkTokens = setOf(
			"debug",
			"fdroid",
			"uptodown",
			"play",
			"playstore",
		)

		/** ABI splits lose to a universal APK when both are attached. */
		private val abiApkTokens = setOf(
			"armeabi",
			"arm64",
			"arm64v8a",
			"x86",
			"x8664",
		)

		internal fun versionCodeFrom(body: String?): Int =
			body?.let { versionCodePattern.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
				?: -1

		private fun comparableVersion(tag: String): Version? = runCatching {
			Version(tag.removePrefix("v").removePrefix("V").substringBefore("-"))
		}.getOrNull()

		private fun apkTokens(name: String): List<String> =
			name.lowercase()
				.removeSuffix(".apk")
				.split(Regex("[^a-z0-9]+"))
				.filter { it.isNotEmpty() }

		/**
		 * Standard APKs are named like Shosetsu-2.5.3-audiobook.apk.
		 * Debug, Play, F-Droid, and Uptodown filenames are skipped.
		 */
		private fun isStandardApk(name: String): Boolean {
			if (!name.endsWith(".apk", ignoreCase = true)) return false
			val tokens = apkTokens(name)
			if ("shosetsu" !in tokens) return false
			return tokens.none { it in excludedApkTokens }
		}

		private fun selectStandardApk(assets: List<GitHubAsset>): GitHubAsset? {
			val matches = assets.filter { asset ->
				asset.browserDownloadUrl.isNotBlank() && isStandardApk(asset.name)
			}
			if (matches.isEmpty()) return null
			val withoutAbi = matches.filter { asset ->
				apkTokens(asset.name).none { it in abiApkTokens }
			}
			val pool = withoutAbi.ifEmpty { matches }
			return pool.firstOrNull { "audiobook" in apkTokens(it.name) } ?: pool.first()
		}
	}
}
