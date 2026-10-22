package app.shosetsu.android.domain.model.local.backup

import app.shosetsu.android.common.consts.VERSION_BACKUP
import kotlinx.serialization.Serializable

/**
 * @param repos that must be added
 * @param extensions is a tree to lower redundant data duplication
 */
@Serializable
data class FleshedBackupEntity(
	val version: String = VERSION_BACKUP,
	val repos: List<BackupRepositoryEntity> = emptyList(),
	val extensions: List<BackupExtensionEntity> = emptyList(),
	val categories: List<BackupCategoryEntity> = emptyList(),
	val audiobookSettings: BackupAudiobookSettings = BackupAudiobookSettings(),
)

@Serializable
data class BackupAudiobookSettings(
	val backend: String? = null,
	val narratorVoice: String? = null,
	val unknownVoice: String? = null,
	val linePause: Int? = null,
	val paragraphPause: Int? = null,
	val nextChapter: Boolean? = null,
	val nextChapterAlert: Boolean? = null,
	val systemEngine: String? = null,
	val systemLanguage: String? = null,
	val systemVoice: String? = null,
	val speed: Float? = null,
	val pitch: Float? = null,
)

@Serializable
data class BackupVoiceCastEntity(
	val characterKey: String,
	val displayName: String,
	val voicePackId: String,
)

@Serializable
data class MetaBackupEntity(
	val version: String?,
)