package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.*

/** 履歴の記録時点の条件。文字列の同名だけでは選択肢を混同しないようにする。 */
internal fun radarPlanDescription(preset: SearchPreset): String {
    val c = preset.criteria
    return listOfNotNull(Genres[preset.genreKey]?.label ?: preset.genreKey,
        c.text.takeIf { it.isNotBlank() }?.let { "検索「$it」" }, c.name.takeIf { it.isNotBlank() }?.let { "名前「$it」" },
        c.message.takeIf { it.isNotBlank() }?.let { "待機メッセージ「$it」" },
        c.excluded.takeIf { it.isNotBlank() }?.let { "除外「$it」" },
        c.gender?.let { if (it == Gender.FEMALE) "女性" else if (it == Gender.MALE) "男性" else "性別未設定" },
        if (c.minAge != null || c.maxAge != null) "年齢 ${c.minAge ?: "下限なし"}〜${c.maxAge ?: "上限なし"}" else null,
        if (!c.includeUnknownAge) "年齢不明を除外" else null, c.area,
        c.waitingOnly?.let { if (it) "待機中" else "満室" }, c.publicOnly?.let { if (it) "公開" else "非公開" },
        if (c.keywordMode == KeywordMode.ALL) "すべての語に一致" else "いずれかの語に一致").joinToString(" · ")
}
