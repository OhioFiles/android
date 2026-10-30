package cloud.ohiofiles.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class AppTab(val title: String, val icon: ImageVector) {
    FILES("Workspace", Icons.Outlined.FolderCopy), COMMUNITY("Community", Icons.Outlined.Explore),
    CHATS("Chats", Icons.Outlined.Forum), PROFILE("You", Icons.Outlined.Person),
    UPLOAD("Upload", Icons.Outlined.CloudUpload), SETTINGS("Settings", Icons.Outlined.Tune), NOTIFICATIONS("Notifications", Icons.Outlined.Notifications)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OhioTopBar(title: String, nickname: String?, onProfileClick: () -> Unit, onUpload: () -> Unit = {}, onNotifications: () -> Unit = {}, onSettings: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    TopAppBar(title = { Text(if (onBack == null) "ohiofiles" else title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } },
        actions = {
            IconButton(onUpload) { Icon(Icons.Outlined.AddCircleOutline, "Upload files") }
            IconButton(onNotifications) { Icon(Icons.Outlined.NotificationsNone, "Notifications") }
            IconButton(onSettings) { Icon(Icons.Outlined.Tune, "Settings") }
            FilledTonalIconButton(onProfileClick, shape = CircleShape, modifier = Modifier.padding(end = 12.dp)) { Text(nickname?.firstOrNull()?.uppercase() ?: "O", fontWeight = FontWeight.Bold) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}
@Composable
fun OhioBottomBar(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        listOf(AppTab.FILES, AppTab.COMMUNITY, AppTab.CHATS, AppTab.PROFILE).forEach { tab ->
            NavigationBarItem(selected = currentTab == tab, onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTabSelected(tab) },
                icon = { Icon(tab.icon, null) }, label = { Text(tab.title, fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Medium) })
        }
    }
}
