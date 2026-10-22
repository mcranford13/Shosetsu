package app.shosetsu.android.domain.model.local.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBackupTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun oldBackupsDefaultToNoAudiobookSettings() {
		val backup = json.decodeFromString<FleshedBackupEntity>(
			"""{"version":"2","repos":[],"extensions":[],"categories":[]}"""
		)

		assertEquals(BackupAudiobookSettings(), backup.audiobookSettings)
	}

	@Test
	fun audiobookSettingsAndCastRoundTrip() {
		val original = FleshedBackupEntity(
			audiobookSettings = BackupAudiobookSettings(
				backend = "neural",
				narratorVoice = "en_us_lessac",
				linePause = 250,
			),
			extensions = listOf(
				BackupExtensionEntity(
					id = 1,
					repoId = 2,
					novels = listOf(
						BackupNovelEntity(
							url = "/novel",
							name = "Novel",
							voiceCast = listOf(
								BackupVoiceCastEntity("alice", "Alice", "en_gb_alba")
							),
						)
					),
				)
			),
		)

		val restored = json.decodeFromString<FleshedBackupEntity>(json.encodeToString(original))

		assertEquals(original.audiobookSettings, restored.audiobookSettings)
		assertTrue(restored.extensions.single().novels.single().voiceCast.isNotEmpty())
	}
}
