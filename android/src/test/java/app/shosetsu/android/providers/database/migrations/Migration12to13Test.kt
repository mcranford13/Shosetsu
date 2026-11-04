package app.shosetsu.android.providers.database.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration12to13Test {
	@Test
	fun migrationTargetsExpectedVersions() {
		assertEquals(12, Migration12to13.startVersion)
		assertEquals(13, Migration12to13.endVersion)
	}

	@Test
	fun migrationTableCascadesWhenEitherNovelIsDeleted() {
		val sql = CREATE_NOVEL_MIGRATIONS_TABLE_SQL

		assertTrue(sql.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
		assertTrue(sql.contains("FOREIGN KEY(`fromNovelId`) REFERENCES `novels`(`id`)"))
		assertTrue(sql.contains("FOREIGN KEY(`toNovelId`) REFERENCES `novels`(`id`)"))
		assertTrue(sql.contains("ON DELETE CASCADE"))
		assertTrue(sql.contains("`transfersJson` TEXT NOT NULL"))
		assertTrue(CREATE_NOVEL_MIGRATIONS_FROM_INDEX_SQL.contains("index_novel_migrations_fromNovelId"))
		assertTrue(CREATE_NOVEL_MIGRATIONS_TO_INDEX_SQL.contains("index_novel_migrations_toNovelId"))
	}
}
