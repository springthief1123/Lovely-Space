package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.runtime.Composable

/** release APKはAI検証ツールへの入口と実装を一切含めない。 */
internal object DeveloperTools {
    const val available = false

    @Composable
    fun Screen(@Suppress("UNUSED_PARAMETER") onBack: () -> Unit) = Unit
}
