package io.github.springthief1123.lovelyspace.disguise

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LauncherDisguiseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val disguise = LauncherDisguise(context)

    private fun state(preset: LauncherPreset) = context.packageManager.getComponentEnabledSetting(
        ComponentName(context, "${context.packageName}.${preset.aliasName}"),
    )

    /** 何も切り替えていなければ、マニフェストの既定どおり元のアイコン。 */
    @Test fun startsWithTheOriginalIcon() {
        assertEquals(LauncherPreset.DEFAULT, disguise.currentPreset())
    }

    /** 切り替えると選んだ alias だけが有効になり、元に戻すこともできる（起動の入口が 1 つだけ残る）。 */
    @Test fun switchingEnablesExactlyOneAlias() {
        disguise.applyPreset(LauncherPreset.CALCULATOR)
        assertEquals(LauncherPreset.CALCULATOR, disguise.currentPreset())
        LauncherPreset.entries.forEach { preset ->
            val expected = if (preset == LauncherPreset.CALCULATOR) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            assertEquals(preset.name, expected, state(preset))
        }
        disguise.applyPreset(LauncherPreset.DEFAULT)
        assertEquals(LauncherPreset.DEFAULT, disguise.currentPreset())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherPreset.CALCULATOR))
    }

    /** ショートカットは有効な alias を開き、アプリ一覧を切り替えると付け替わる。 */
    @Test fun pinnedShortcutFollowsTheEnabledAlias() {
        shadowOf(context.getSystemService(ShortcutManager::class.java)).setIsRequestPinShortcutSupported(true)
        assertFalse(disguise.isShortcutPinned())
        assertTrue(disguise.pinShortcut(ShortcutDisguise(color = 0xFF1A73E8.toInt(), symbol = DisguiseSymbol.MAP, label = "地図")))
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).single()
        assertEquals("地図", pinned.shortLabel.toString())
        assertEquals(LauncherPreset.DEFAULT.aliasName, pinned.intent.component?.className?.substringAfterLast('.'))

        disguise.applyPreset(LauncherPreset.WEATHER)
        val moved = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).single()
        assertEquals(LauncherPreset.WEATHER.aliasName, moved.intent.component?.className?.substringAfterLast('.'))
        assertEquals("地図", moved.shortLabel.toString())
    }

    @Test fun rendersAnAdaptiveIconSizedBitmap() {
        val bitmap = LauncherDisguise.renderIcon(context, ShortcutDisguise())
        assertEquals(432, bitmap.width)
        assertEquals(432, bitmap.height)
    }

    /** 明るい背景には濃いシンボル、暗い背景には白いシンボル。 */
    @Test fun symbolKeepsContrastOnLightAndDarkColors() {
        assertEquals(0xFF202124.toInt(), symbolColorOn(0xFFFFFFFF.toInt()))
        assertEquals(0xFF202124.toInt(), symbolColorOn(0xFFFFD54F.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), symbolColorOn(0xFF202124.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), symbolColorOn(0xFF1A73E8.toInt()))
    }
}
