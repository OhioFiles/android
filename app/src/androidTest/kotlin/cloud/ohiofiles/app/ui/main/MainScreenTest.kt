package cloud.ohiofiles.app.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.ohiofiles.app.ui.components.AppTab
import cloud.ohiofiles.app.ui.components.OhioBottomBar
import cloud.ohiofiles.app.ui.theme.OhioFilesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeNavigationHasFourClearDestinations() {
        var selected = AppTab.FILES
        compose.setContent { OhioFilesTheme { OhioBottomBar(AppTab.FILES) { selected = it } } }
        listOf("Workspace", "Community", "Chats", "You").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Chats").performClick()
        assertEquals(AppTab.CHATS, selected)
    }
}
