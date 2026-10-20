package io.github.springthief1123.lovelyspace.disguise

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import io.github.springthief1123.lovelyspace.R

/** ホーム画面のショートカットに描くシンボル。どれも白い 24dp のベクター（`ic_symbol_*`）。 */
enum class DisguiseSymbol(@DrawableRes val drawable: Int, val label: String) {
    CALCULATE(R.drawable.ic_symbol_calculate, "電卓"),
    EDIT_NOTE(R.drawable.ic_symbol_edit_note, "メモ"),
    WB_SUNNY(R.drawable.ic_symbol_wb_sunny, "天気"),
    CALENDAR_MONTH(R.drawable.ic_symbol_calendar_month, "カレンダー"),
    SCHEDULE(R.drawable.ic_symbol_schedule, "時計"),
    PHOTO_CAMERA(R.drawable.ic_symbol_photo_camera, "カメラ"),
    MUSIC_NOTE(R.drawable.ic_symbol_music_note, "音楽"),
    SETTINGS(R.drawable.ic_symbol_settings, "歯車"),
    FOLDER(R.drawable.ic_symbol_folder, "フォルダ"),
    MAIL(R.drawable.ic_symbol_mail, "メール"),
    MENU_BOOK(R.drawable.ic_symbol_menu_book, "本"),
    PUBLIC(R.drawable.ic_symbol_public, "地球"),
    CLOUD(R.drawable.ic_symbol_cloud, "雲"),
    SPORTS_ESPORTS(R.drawable.ic_symbol_sports_esports, "ゲーム"),
    MAP(R.drawable.ic_symbol_map, "地図"),
    SHOPPING_CART(R.drawable.ic_symbol_shopping_cart, "買い物"),
    TRANSLATE(R.drawable.ic_symbol_translate, "翻訳"),
    FLASHLIGHT_ON(R.drawable.ic_symbol_flashlight_on, "ライト"),
}

/**
 * アプリ一覧（とホーム画面の元のアイコン）の見た目。マニフェストの activity-alias と 1 対 1 で、どれか 1 つだけを有効にする。
 * ランチャーのアイコンは実行時に描けないので、色とシンボルを自由に組むのはホーム画面のショートカット（[ShortcutDisguise]）で行う。
 */
enum class LauncherPreset(
    val aliasName: String,
    @StringRes val label: Int,
    /** マニフェストの alias と同じアイコン（最近のアプリの一覧にも使う）。 */
    @DrawableRes val icon: Int,
    @ColorRes val background: Int,
    /** null は元のアイコン（ic_launcher_foreground）。 */
    val symbol: DisguiseSymbol?,
) {
    DEFAULT("LauncherDefault", R.string.app_name, R.mipmap.ic_launcher, R.color.ic_launcher_background, null),
    CALCULATOR("LauncherCalculator", R.string.disguise_calculator, R.mipmap.ic_disguise_calculator, R.color.disguise_calculator_background, DisguiseSymbol.CALCULATE),
    NOTES("LauncherNotes", R.string.disguise_notes, R.mipmap.ic_disguise_notes, R.color.disguise_notes_background, DisguiseSymbol.EDIT_NOTE),
    WEATHER("LauncherWeather", R.string.disguise_weather, R.mipmap.ic_disguise_weather, R.color.disguise_weather_background, DisguiseSymbol.WB_SUNNY),
    CALENDAR("LauncherCalendar", R.string.disguise_calendar, R.mipmap.ic_disguise_calendar, R.color.disguise_calendar_background, DisguiseSymbol.CALENDAR_MONTH),
    CLOCK("LauncherClock", R.string.disguise_clock, R.mipmap.ic_disguise_clock, R.color.disguise_clock_background, DisguiseSymbol.SCHEDULE),
}

/** ホーム画面のショートカットの見た目。[color] は不透明な ARGB。 */
data class ShortcutDisguise(
    val color: Int = DEFAULT_COLOR,
    val symbol: DisguiseSymbol = DisguiseSymbol.CALCULATE,
    val label: String = "電卓",
) {
    companion object {
        const val DEFAULT_COLOR = 0xFF5F6368.toInt()
        /** ランチャーは長い名前を省略するので、入力もこの長さまでにする。 */
        const val MAX_LABEL_LENGTH = 20
    }
}

/** 背景 [background] の上で読めるシンボルの色（白か濃い灰色）。明るい背景では濃い色にする。 */
fun symbolColorOn(background: Int): Int =
    if (ColorUtils.calculateLuminance(background) > 0.55) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()

/** アプリ一覧の見た目の切り替えと、ホーム画面のショートカットの追加・更新。 */
class LauncherDisguise(context: Context) {
    private val context = context.applicationContext
    private val packageManager = this.context.packageManager

    private fun component(preset: LauncherPreset) = ComponentName(context, "${context.packageName}.${preset.aliasName}")

    /** いま有効なアプリ一覧の見た目。未設定の alias はマニフェストの既定（[LauncherPreset.DEFAULT] だけ有効）に従う。 */
    fun currentPreset(): LauncherPreset = LauncherPreset.entries.firstOrNull { preset ->
        when (packageManager.getComponentEnabledSetting(component(preset))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> preset == LauncherPreset.DEFAULT
            else -> false
        }
    } ?: LauncherPreset.DEFAULT

    /**
     * アプリ一覧の見た目を [preset] に切り替える。先に新しい alias を有効にしてから他を止め、起動できる入口が無い瞬間を作らない。
     * ホーム画面のショートカットは有効な alias を開くので、切り替えた先へ付け替える。
     */
    fun applyPreset(preset: LauncherPreset) {
        setEnabled(preset, true)
        // 止める前に付け替える。止めた alias に結び付いたままだと、ホーム画面アプリがショートカットを無効にすることがある。
        pinnedShortcut()?.let { ShortcutManagerCompat.updateShortcuts(context, listOf(it.rebuild(preset))) }
        LauncherPreset.entries.filter { it != preset }.forEach { setEnabled(it, false) }
    }

    private fun setEnabled(preset: LauncherPreset, enabled: Boolean) {
        // 既定の状態に戻すとマニフェストの値（DEFAULT だけ有効）に頼ることになるので、常に明示する。
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(component(preset), state, PackageManager.DONT_KILL_APP)
    }

    /** ホーム画面アプリがショートカットの追加に対応しているか。 */
    fun isPinSupported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    private fun pinnedShortcut(): ShortcutInfoCompat? =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).firstOrNull { it.id == SHORTCUT_ID }

    /** カモフラージュのショートカットがホーム画面に置かれているか。利用者がホーム画面から外すと false に戻る。 */
    fun isShortcutPinned(): Boolean = pinnedShortcut() != null

    /**
     * ショートカットをホーム画面に置く（置いてあれば見た目と名前を変える）。
     * 置くときはホーム画面アプリの確認が出るので、置けたかどうかは後で [isShortcutPinned] で確かめる。
     * 戻り値は、確認を出せた・更新できたか。
     */
    fun pinShortcut(design: ShortcutDisguise): Boolean {
        val info = shortcutInfo(design, currentPreset())
        return if (isShortcutPinned()) {
            ShortcutManagerCompat.updateShortcuts(context, listOf(info))
        } else {
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }
    }

    private fun shortcutInfo(design: ShortcutDisguise, preset: LauncherPreset): ShortcutInfoCompat {
        val label = design.label.trim().ifEmpty { design.symbol.label }
        return ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(renderIcon(context, design)))
            .setActivity(component(preset))
            .setIntent(launchIntent(preset))
            .build()
    }

    /** 置いてあるショートカットの見た目のまま、開く先だけを [preset] に変える（アイコンは渡さなければ元のまま）。 */
    private fun ShortcutInfoCompat.rebuild(preset: LauncherPreset): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel ?: shortLabel)
            .setActivity(component(preset))
            .setIntent(launchIntent(preset))
            .build()

    /**
     * アプリ一覧のアイコンと同じ起動の仕方にする。同じ alias・同じ intent にしておくと、開いているアプリへ戻るだけで、
     * 画面が二重に開かない。
     */
    internal fun launchIntent(preset: LauncherPreset): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component(preset))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    companion object {
        const val SHORTCUT_ID = "disguise"
        /** アダプティブアイコンの 108dp を 4 倍で描く。 */
        private const val ICON_SIZE_PX = 432
        /** シンボルの上下左右の余白。アプリ一覧の見た目（mipmap の inset 30%）とそろえ、丸く切り抜かれても欠けない大きさ。 */
        private const val SYMBOL_INSET = 0.30f

        /** 色の背景にシンボルを描いたアダプティブアイコン用の画像。ランチャーが角丸や円に切り抜く。 */
        fun renderIcon(context: Context, design: ShortcutDisguise): Bitmap {
            val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(design.color or 0xFF000000.toInt())
            val inset = (ICON_SIZE_PX * SYMBOL_INSET).toInt()
            ContextCompat.getDrawable(context, design.symbol.drawable)?.mutate()?.apply {
                setTint(symbolColorOn(design.color or 0xFF000000.toInt()))
                setBounds(inset, inset, ICON_SIZE_PX - inset, ICON_SIZE_PX - inset)
                draw(canvas)
            }
            return bitmap
        }
    }
}

/**
 * 最近のアプリの一覧に出す名前とアイコンを、アプリ一覧の見た目にそろえる。
 * 通知から開いたときは元の Activity（Lovely Space の名前）から始まるため、毎回明示する。
 */
fun Activity.applyDisguiseTaskDescription(preset: LauncherPreset = LauncherDisguise(this).currentPreset()) {
    val label = getString(preset.label)
    val description = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            ActivityManager.TaskDescription.Builder().setLabel(label).setIcon(preset.icon).build()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            @Suppress("DEPRECATION") ActivityManager.TaskDescription(label, preset.icon, 0)
        else -> @Suppress("DEPRECATION") ActivityManager.TaskDescription(label)
    }
    setTaskDescription(description)
}
