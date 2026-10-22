package app.shosetsu.android.backend.workers.onetime

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadForegroundServiceTypeTest {
	@Test
	fun modernPlatformsUseDataSync() {
		assertEquals(
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
			downloadForegroundServiceType(Build.VERSION_CODES.Q),
		)
		assertEquals(
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
			downloadForegroundServiceType(36),
		)
	}

	@Test
	fun legacyPlatformsOmitTheType() {
		assertEquals(0, downloadForegroundServiceType(Build.VERSION_CODES.P))
	}
}
