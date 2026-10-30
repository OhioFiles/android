package cloud.ohiofiles.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.model.AccentPalette
import cloud.ohiofiles.app.data.model.CornerStyle
import cloud.ohiofiles.app.data.model.ThemeMode
import cloud.ohiofiles.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by viewModel.appearanceConfig.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

    var customUrlInput by remember(serverUrl) { mutableStateOf(serverUrl) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme & Material Design 3 Header
        Text(
            text = "Make it yours.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Theme Mode Selector Card
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Theme Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                        ThemeMode.AMOLED to "AMOLED"
                    )

                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = config.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        // Color Palettes & Material You Card
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Material You & Color Palette",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Dynamic Monet switch (if Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Material You Dynamic Color", fontWeight = FontWeight.SemiBold)
                            Text("Sample accent colors from wallpaper", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.accentPalette == AccentPalette.DYNAMIC,
                            onCheckedChange = { isChecked ->
                                viewModel.setAccentPalette(if (isChecked) AccentPalette.DYNAMIC else AccentPalette.MONO)
                            }
                        )
                    }
                    HorizontalDivider()
                }

                Text(
                    text = "Color Accent Presets",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                // Horizontal list of Color Palettes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val palettes = listOf(
                        AccentPalette.MONO to ("Mono" to MonoDarkPrimary),
                        AccentPalette.EMERALD to ("Emerald" to EmeraldPrimary),
                        AccentPalette.BLUE to ("Blue" to BluePrimary),
                        AccentPalette.ROSE to ("Rose" to RosePrimary),
                        AccentPalette.AMBER to ("Amber" to AmberPrimary),
                        AccentPalette.PURPLE to ("Purple" to PurplePrimary),
                        AccentPalette.SUNSET to ("Sunset" to SunsetPrimary)
                    )

                    palettes.forEach { (palette, pair) ->
                        val (name, color) = pair
                        val isSelected = config.accentPalette == palette

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .clickable { viewModel.setAccentPalette(palette) }
                                .padding(vertical = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        )
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // Corner Style Card
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Corner Rounding",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val corners = listOf(
                        CornerStyle.SQUARY to "Subtle",
                        CornerStyle.MEDIUM to "Soft",
                        CornerStyle.ROUND to "Expressive"
                    )

                    corners.forEachIndexed { index, (style, label) ->
                        SegmentedButton(
                            selected = config.cornerStyle == style,
                            onClick = { viewModel.setCornerStyle(style) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = corners.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        // Server Configuration Card
        Text(
            text = "Backend Server",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = customUrlInput,
                    onValueChange = { customUrlInput = it },
                    label = { Text("OhioFiles API Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            customUrlInput = "https://api.ohiofiles.cloud"
                            viewModel.setServerUrl(customUrlInput)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Official Cloud")
                    }

                    Button(
                        onClick = {
                            viewModel.setServerUrl(customUrlInput)

                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save & Test")
                    }
                }

                if (connectionStatus != null) {
                    Text(
                        text = connectionStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionStatus!!.contains("error", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Uploads", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Show QR code after upload", Modifier.weight(1f)); Switch(config.autoShowQrOnUpload, viewModel::setAutoShowQr) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Detailed chunk progress", Modifier.weight(1f)); Switch(config.detailedChunkProgress, viewModel::setDetailedChunkProgress) }
            }
        }
        // App Info
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("OhioFiles for Android", fontWeight = FontWeight.Bold)
                Text("Native Material Design 3 + Material You client", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · Material 3 Expressive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
