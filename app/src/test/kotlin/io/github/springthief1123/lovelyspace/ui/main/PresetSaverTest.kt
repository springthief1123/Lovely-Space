package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.runtime.saveable.SaverScope
import io.github.springthief1123.lovelyspace.data.ProfilePreset
import io.github.springthief1123.lovelyspace.data.MessagePreset
import org.junit.Assert.*
import org.junit.Test

class PresetSaverTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    @Test fun restoresEditedIdentityAndSecretValuesBeforeDatabaseLoads() {
        val profile = ProfilePreset("existing-id", "設定", "合成の名前", 2, null, null, true)
        val saved = with(ProfilePresetSaver) { scope.save(profile) }!!
        assertEquals(profile, ProfilePresetSaver.restore(saved))
        val message = MessagePreset("existing-message", "募集文", "合成の文", true)
        val savedMessage = with(MessagePresetSaver) { scope.save(message) }!!
        assertEquals(message, MessagePresetSaver.restore(savedMessage))
    }
}
