package io.github.springthief1123.lovelyspace.ui.create

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.springthief1123.lovelyspace.core.Genres
import io.github.springthief1123.lovelyspace.data.PresetDatabase
import io.github.springthief1123.lovelyspace.data.PresetRepository
import io.github.springthief1123.lovelyspace.settings.SettingsRepository
import io.github.springthief1123.lovelyspace.ui.theme.LovelySpaceTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CreateRoomFormTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, PresetDatabase::class.java).build()
    @After fun close() = db.close()

    @Test fun visibilityAndOptionalEmailAreAvailableBeforeContinuing() {
        compose.setContent {
            LovelySpaceTheme {
                val settings = SettingsRepository(context)
                val vm = androidx.lifecycle.viewmodel.compose.viewModel<CreateRoomViewModel>(
                    factory = androidx.lifecycle.viewmodel.viewModelFactory {
                        androidx.lifecycle.viewmodel.initializer { CreateRoomViewModel(settings, PresetRepository(db, settings)) }
                    })
                CreateRoomForm(Genres.default, CreateRoomUiState(isLoaded = true), vm, {}, Modifier)
            }
        }
        compose.onNodeWithText("公開ルームとして作成され、誰でも会話を覗けます。非公開にできるのは、本家の条件を満たす会員のみです。").assertExists()
        compose.onNodeWithText("メールアドレス（任意）").assertExists()
    }
}
