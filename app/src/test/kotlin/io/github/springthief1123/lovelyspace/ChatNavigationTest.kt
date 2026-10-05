package io.github.springthief1123.lovelyspace

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatNavigationTest {
    private fun controller(): TestNavHostController {
        val nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        nav.setViewModelStore(ViewModelStore())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        val origin = navArgument("origin") { type = NavType.StringType; defaultValue = Routes.ROOMS }
        nav.graph = nav.createGraph(startDestination = Routes.ROOMS) {
            composable(Routes.ROOMS) { }
            composable(Routes.SEARCH) { }
            composable(Routes.FAVORITES) { }
            composable(Routes.ENTRY, arguments = listOf(
                navArgument("host") { type = NavType.StringType }, navArgument("genre") { type = NavType.StringType },
                navArgument("roomId") { type = NavType.LongType }, origin,
            )) { }
            composable(Routes.CHAT, arguments = listOf(navArgument("session") { type = NavType.StringType }, origin)) { }
        }
        return nav
    }
    @Test fun entryFromSearchPreservesTheSameSearchEntryAndItsStateAfterChat() {
        val nav = controller()
        nav.navigate(Routes.SEARCH)
        val search = nav.getBackStackEntry(Routes.SEARCH)
        search.savedStateHandle["loaded-page"] = 3
        search.savedStateHandle["criteria"] = "合成の検索語"
        nav.navigate(Routes.entry("chat.shalove.net", "zenkoku", 123, Routes.SEARCH))
        val origin = nav.currentBackStackEntry!!.arguments!!.getString("origin")!!
        nav.navigateToChat("synthetic-session", origin)
        assertEquals(Routes.CHAT, nav.currentDestination!!.route)
        assertSame(search, nav.getBackStackEntry(Routes.SEARCH))
        nav.returnFromChat(origin)
        assertEquals(Routes.SEARCH, nav.currentDestination!!.route)
        assertSame(search, nav.currentBackStackEntry)
        assertEquals(3, nav.currentBackStackEntry!!.savedStateHandle.get<Int>("loaded-page"))
        assertEquals("合成の検索語", nav.currentBackStackEntry!!.savedStateHandle.get<String>("criteria"))
    }
    @Test fun entryFromFavoritesReturnsToFavoritesAfterChat() {
        val nav = controller()
        nav.navigate(Routes.FAVORITES)
        val favorites = nav.getBackStackEntry(Routes.FAVORITES)
        nav.navigate(Routes.entry("chat.shalove.net", "zenkoku", 321, Routes.FAVORITES))
        val origin = nav.currentBackStackEntry!!.arguments!!.getString("origin")!!
        nav.navigateToChat("favorite-session", origin)
        assertSame(favorites, nav.getBackStackEntry(Routes.FAVORITES))
        nav.returnFromChat(origin)
        assertEquals(Routes.FAVORITES, nav.currentDestination!!.route)
        assertSame(favorites, nav.currentBackStackEntry)
    }

    @Test fun defaultEntryStillReturnsToRoomsAndNeverIncludesRoomPassword() {
        val nav = controller()
        nav.navigate(Routes.entry("chat.shalove.net", "zenkoku", 123))
        assertEquals(Routes.ROOMS, nav.currentBackStackEntry!!.arguments!!.getString("origin"))
        nav.navigateToChat("synthetic-session")
        assertNull(nav.currentBackStackEntry!!.arguments!!.getString("pwd"))
        nav.returnFromChat(Routes.ROOMS)
        assertEquals(Routes.ROOMS, nav.currentDestination!!.route)
    }
}
