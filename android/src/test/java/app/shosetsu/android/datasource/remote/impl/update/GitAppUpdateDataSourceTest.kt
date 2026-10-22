package app.shosetsu.android.datasource.remote.impl.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GitAppUpdateDataSourceTest {
	private val source = GitAppUpdateDataSource(OkHttpClient())

	@Test
	fun choosesNewestStableReleaseAndStandardAudiobookApk() {
		val older = release("v2.5.3", "Shosetsu-2.5.3.apk")
		val prerelease = release("v9.0.0-beta", "Shosetsu-9.0.0.apk", prerelease = true)
		val newest = GitAppUpdateDataSource.GitHubRelease(
			tagName = "v2.5.4",
			body = "Version code 51",
			assets = listOf(
				asset("Shosetsu-2.5.4-play.apk"),
				asset("Shosetsu-2.5.4-arm64-v8a.apk"),
				asset("Shosetsu-2.5.4-audiobook.apk"),
			),
		)

		val selected = source.chooseRelease(listOf(older, prerelease, newest))

		assertNotNull(selected)
		assertEquals("v2.5.4", selected!!.first.tagName)
		assertEquals("Shosetsu-2.5.4-audiobook.apk", selected.second.name)
	}

	@Test
	fun parsesVersionCodeCaseInsensitively() {
		assertEquals(51, GitAppUpdateDataSource.versionCodeFrom("Release\nVERSION CODE 51"))
		assertEquals(-1, GitAppUpdateDataSource.versionCodeFrom("No code metadata"))
	}

	private fun release(
		tag: String,
		apk: String,
		prerelease: Boolean = false,
	) = GitAppUpdateDataSource.GitHubRelease(
		tagName = tag,
		prerelease = prerelease,
		assets = listOf(asset(apk)),
	)

	private fun asset(name: String) = GitAppUpdateDataSource.GitHubAsset(
		name = name,
		browserDownloadUrl = "https://example.test/$name",
	)
}
