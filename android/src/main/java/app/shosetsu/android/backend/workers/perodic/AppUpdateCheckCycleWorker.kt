package app.shosetsu.android.backend.workers.perodic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import app.shosetsu.android.backend.workers.CoroutineWorkerManager
import app.shosetsu.android.common.consts.WorkerTags.APP_UPDATE_CYCLE_WORK_ID
import app.shosetsu.android.common.ext.logI
import app.shosetsu.android.common.utils.await

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
 * 06 / 09 / 2020
 */
class AppUpdateCheckCycleWorker(
	appContext: Context,
	params: WorkerParameters
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		// Automatic checks are disabled. A previously scheduled run must not
		// look for an app update; that happens only from the About settings button.
		logI("Skipping automatic app update check")
		return Result.success()
	}


	/**
	 * Manager of [AppUpdateCheckCycleWorker]
	 */
	class Manager(context: Context) : CoroutineWorkerManager(context) {
		/**
		 * Returns the status of the service.
		 *
		 * @return true if the service is running, false otherwise.
		 */
		override suspend fun isRunning(): Boolean = try {
			getWorkerState() == WorkInfo.State.RUNNING
		} catch (e: Exception) {
			false
		}

		override suspend fun getWorkerState(index: Int) =
			getWorkerInfoList().getOrNull(index)?.state

		override suspend fun getWorkerInfoList(): List<WorkInfo> =
			workerManager.getWorkInfosForUniqueWork(APP_UPDATE_CYCLE_WORK_ID).await()

		override suspend fun getCount(): Int =
			getWorkerInfoList().size

		/**
		 * Refuses to schedule a check. App updates start from the About settings button.
		 */
		override fun start(data: Data) {
			logI("Refusing to schedule an automatic app update check")
			stop()
		}

		/**
		 * Stops the service.
		 */
		override fun stop(): Operation = workerManager.cancelUniqueWork(APP_UPDATE_CYCLE_WORK_ID)
	}
}
