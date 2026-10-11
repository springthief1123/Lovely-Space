package io.github.springthief1123.lovelyspace.ai

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** debug APKの起動アイコンは通常アプリひとつ。開発用Activityは外部公開しない。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DebugLauncherManifestTest {
    @Test fun onlyMainActivityHasLauncherIcon() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launcherNames = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }
            .toSet()

        assertEquals(setOf(MainActivity::class.java.name), launcherNames)
        val main = pm.getActivityInfo(
            ComponentName(context.packageName, MainActivity::class.java.name), 0,
        )
        assertTrue(main.exported)
    }

    @Test fun debugToolsAreInternalActivitiesWithoutLauncherEntries() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        for (type in listOf(EmbeddingPocActivity::class.java, ExistingSnapshotSearchActivity::class.java)) {
            val info = pm.getActivityInfo(ComponentName(context.packageName, type.name), 0)
            assertFalse("${type.simpleName} は外部アプリへ公開しない", info.exported)
        }
    }
}
