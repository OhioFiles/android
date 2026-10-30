package cloud.ohiofiles.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED // Pure black background for OLED screens
}

@Serializable
enum class AccentPalette {
    DYNAMIC, // Material You / Monet from device wallpaper
    MONO,    // Classic OhioFiles Black & White
    EMERALD, // Fresh Emerald green
    BLUE,    // Cloud Azure
    ROSE,    // Radiant Rose
    AMBER,   // Warm Amber glow
    PURPLE,  // Electric Purple
    SUNSET   // Deep Crimson / Orange
}

@Serializable
enum class CornerStyle {
    SQUARY, // 4.dp
    MEDIUM, // 12.dp
    ROUND   // 24.dp (Expressive M3)
}

@Serializable
enum class DensityStyle {
    COMPACT,
    COMFORTABLE,
    SPACIOUS
}

@Serializable
enum class FontOption {
    SYSTEM,
    MONOSPACE,
    ROUNDED,
    SERIF
}

@Serializable
data class AppearanceConfig(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentPalette: AccentPalette = AccentPalette.EMERALD,
    val cornerStyle: CornerStyle = CornerStyle.ROUND,
    val densityStyle: DensityStyle = DensityStyle.COMFORTABLE,
    val fontOption: FontOption = FontOption.SYSTEM,
    val serverUrl: String = "https://api.ohiofiles.cloud",
    val autoShowQrOnUpload: Boolean = true,
    val viewModeGrid: Boolean = true,
    val detailedChunkProgress: Boolean = false
)
