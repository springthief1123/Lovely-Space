package io.github.springthief1123.lovelyspace.background

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    private fun periodic() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundSync.PERIODIC).get()

    @Test fun nothingIsRegisteredWhileNoBackgroundFeatureIsEnabled() {
        BackgroundSync.update(context, enabled = false)
        assertTrue(periodic().isEmpty())
    }

    @Test fun enablingTwiceKeepsOnePeriodicWorkAndDisablingCancelsIt() {
        BackgroundSync.update(context, enabled = true)
        BackgroundSync.update(context, enabled = true)
        val active = periodic().filter { !it.state.isFinished }
        assertEquals(1, active.size)
        BackgroundSync.update(context, enabled = false)
        assertEquals(listOf(WorkInfo.State.CANCELLED), periodic().map { it.state })
    }

    @Test fun changingTheIntervalUpdatesTheSameWorkInsteadOfAddingAnother() {
        BackgroundSync.update(context, enabled = true, intervalMinutes = 15)
        val id = periodic().single().id
        BackgroundSync.update(context, enabled = true, intervalMinutes = 60)
        val active = periodic().filter { !it.state.isFinished }
        assertEquals(listOf(id), active.map { it.id })
    }
}
