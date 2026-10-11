package io.github.springthief1123.lovelyspace.ai

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Androidが各ホームアイコンを同一タスクへ戻してしまう退行を防ぐ。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DebugLauncherManifestTest {
    @Test fun threeLauncherIconsOpenDistinctTasks() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val activities = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.name }
            .toSet()

        val names = listOf(
            MainActivity::class.java.name,
            EmbeddingPocActivity::class.java.name,
            ExistingSnapshotSearchActivity::class.java.name,
        )
        assertTrue("3つのランチャーActivityが必要", activities.containsAll(names))

        val affinities = names.map { name ->
            val info = pm.getActivityInfo(ComponentName(context.packageName, name), 0)
            assertTrue("Launcherはexportedが必要: ${name}", info.exported)
            info.taskAffinity
        }
        assertEquals("各ランチャーは異なるタスク所属先を持つ", 3, affinities.toSet().size)
        assertNotEquals(affinities[0], affinities[1])
        assertNotEquals(affinities[0], affinities[2])
        assertNotEquals(affinities[1], affinities[2])
    }
}
