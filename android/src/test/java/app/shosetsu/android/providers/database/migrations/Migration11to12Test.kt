package app.shosetsu.android.providers.database.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration11to12Test {
	@Test
	fun migrationTargetsExpectedVersions() {
		assertEquals(11, Migration11to12.startVersion)
		assertEquals(12, Migration11to12.endVersion)
	}

	@Test
	fun voiceCastSchemaRetainsCompositeKeyAndCascade() {
		val sql = CREATE_NOVEL_VOICE_CAST_TABLE_SQL

		assertTrue(sql.contains("PRIMARY KEY(`novelId`, `characterKey`)"))
		assertTrue(sql.contains("FOREIGN KEY(`novelId`) REFERENCES `novels`(`id`)"))
		assertTrue(sql.contains("ON DELETE CASCADE"))
		assertTrue(sql.contains("`voicePackId` TEXT NOT NULL"))
	}
}
