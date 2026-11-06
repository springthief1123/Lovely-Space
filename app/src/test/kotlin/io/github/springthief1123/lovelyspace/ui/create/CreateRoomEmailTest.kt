package io.github.springthief1123.lovelyspace.ui.create

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.settings.RoomDetails
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.ui.web.prefillFormScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CreateRoomEmailTest {
    @Test fun optionalEmailSurvivesReloadAndIsIncludedInTheActualPrefill() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(context)
        val db = Room.inMemoryDatabaseBuilder(context, PresetDatabase::class.java).build()
        try {
            settings.setLastRoomDetails(RoomDetails(null, "合成の募集文", "saved@example.invalid"))
            val presets = PresetRepository(db, settings)
            val vm = CreateRoomViewModel(settings, presets)
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.isLoaded } } }
            assertEquals("saved@example.invalid", vm.state.value.email)
            vm.setName("合成の名前")
            vm.setEmail("  next@example.invalid  ")
            assertEquals("next@example.invalid", vm.state.value.formFields["email"])
            assertTrue(prefillFormScript("makeroom", vm.state.value.formFields).contains("set(\"email\", \"next@example.invalid\")"))
            vm.saveInputs()
            withContext(Dispatchers.Default) { withTimeout(10_000) { settings.lastRoomDetails.first { it.email == "next@example.invalid" } } }
            val restored = CreateRoomViewModel(SettingsRepository(context), presets)
            withContext(Dispatchers.Default) { withTimeout(10_000) { restored.state.first { it.isLoaded } } }
            assertEquals("next@example.invalid", restored.state.value.email)
            restored.setEmail("")
            assertTrue(restored.state.value.canContinue)
            assertEquals("", restored.state.value.formFields["email"])
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
