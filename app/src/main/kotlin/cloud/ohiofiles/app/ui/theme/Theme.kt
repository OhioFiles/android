package cloud.ohiofiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.model.AccentPalette
import cloud.ohiofiles.app.data.model.AppearanceConfig
import cloud.ohiofiles.app.data.model.CornerStyle
import cloud.ohiofiles.app.data.model.ThemeMode

val LocalCornerStyle = staticCompositionLocalOf { CornerStyle.ROUND }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OhioFilesTheme(
    config: AppearanceConfig = AppearanceConfig(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (config.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val isAmoled = config.themeMode == ThemeMode.AMOLED
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme = when {
        config.accentPalette == AccentPalette.DYNAMIC && supportsDynamic -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        config.accentPalette == AccentPalette.MONO -> {
            if (isDark) {
                darkColorScheme(
                    primary = MonoDarkPrimary,
                    onPrimary = MonoDarkOnPrimary,
                    secondary = MonoDarkPrimary, secondaryContainer = MonoDarkContainer, onSecondaryContainer = MonoDarkOnContainer,
                    tertiary = MonoDarkPrimary, tertiaryContainer = MonoDarkContainer, onTertiaryContainer = MonoDarkOnContainer,
                    primaryContainer = MonoDarkContainer,
                    onPrimaryContainer = MonoDarkOnContainer,
                    background = if (isAmoled) Color.Black else MonoDarkBackground,
                    surface = if (isAmoled) Color(0xFF0C0C0C) else MonoDarkSurface,
                    surfaceVariant = if (isAmoled) Color(0xFF141414) else MonoDarkSurfaceVariant
                )
            } else {
                lightColorScheme(
                    primary = MonoLightPrimary,
                    onPrimary = MonoLightOnPrimary,
                    secondary = MonoLightPrimary, secondaryContainer = MonoLightContainer, onSecondaryContainer = MonoLightOnContainer,
                    tertiary = MonoLightPrimary, tertiaryContainer = MonoLightContainer, onTertiaryContainer = MonoLightOnContainer,
                    primaryContainer = MonoLightContainer,
                    onPrimaryContainer = MonoLightOnContainer,
                    background = MonoLightBackground,
                    surface = MonoLightSurface,
                    surfaceVariant = MonoLightSurfaceVariant
                )
            }
        }
        else -> {
            val (prim, lightPrim, darkCont, lightCont) = when (config.accentPalette) {
                AccentPalette.EMERALD -> listOf(EmeraldPrimary, EmeraldLightPrimary, EmeraldDarkContainer, EmeraldLightContainer)
                AccentPalette.BLUE -> listOf(BluePrimary, BlueLightPrimary, BlueDarkContainer, BlueLightContainer)
                AccentPalette.ROSE -> listOf(RosePrimary, RoseLightPrimary, RoseDarkContainer, RoseLightContainer)
                AccentPalette.AMBER -> listOf(AmberPrimary, AmberLightPrimary, AmberDarkContainer, AmberLightContainer)
                AccentPalette.PURPLE -> listOf(PurplePrimary, PurpleLightPrimary, PurpleDarkContainer, PurpleLightContainer)
                AccentPalette.SUNSET -> listOf(SunsetPrimary, SunsetLightPrimary, SunsetDarkContainer, SunsetLightContainer)
                else -> listOf(EmeraldPrimary, EmeraldLightPrimary, EmeraldDarkContainer, EmeraldLightContainer)
            }

            if (isDark) {
                darkColorScheme(
                    primary = prim,
                    onPrimary = Color.Black,
                    secondary = prim, secondaryContainer = darkCont, onSecondaryContainer = Color.White,
                    tertiary = prim, tertiaryContainer = darkCont, onTertiaryContainer = Color.White,
                    primaryContainer = darkCont,
                    onPrimaryContainer = Color.White,
                    background = if (isAmoled) Color.Black else Color(0xFF111418),
                    surface = if (isAmoled) Color(0xFF0C0E10) else Color(0xFF181C20),
                    surfaceVariant = if (isAmoled) Color(0xFF161A1E) else Color(0xFF22272E)
                )
            } else {
                lightColorScheme(
                    primary = lightPrim,
                    onPrimary = Color.White,
                    secondary = lightPrim, secondaryContainer = lightCont, onSecondaryContainer = Color.Black,
                    tertiary = lightPrim, tertiaryContainer = lightCont, onTertiaryContainer = Color.Black,
                    primaryContainer = lightCont,
                    onPrimaryContainer = Color.Black,
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFF1F5F9)
                )
            }
        }
    }

    val tonalScheme = if (config.accentPalette == AccentPalette.DYNAMIC) baseScheme else baseScheme.copy(
        surfaceContainerLowest = baseScheme.surface,
        surfaceContainerLow = lerp(baseScheme.surface, baseScheme.primary, 0.025f),
        surfaceContainer = lerp(baseScheme.surface, baseScheme.primary, 0.05f),
        surfaceContainerHigh = lerp(baseScheme.surface, baseScheme.primary, 0.08f),
        surfaceContainerHighest = lerp(baseScheme.surface, baseScheme.primary, 0.12f),
        outlineVariant = lerp(baseScheme.surface, baseScheme.onSurface, 0.16f)
    )
    // Apply AMOLED black override if enabled
    val colorScheme = if (isAmoled) {
        tonalScheme.copy(
            background = Color.Black,
            surface = Color(0xFF080808),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1A1A1A)
        )
    } else {
        tonalScheme
    }

    // Custom Shapes according to user's corner style
    val shapes = when (config.cornerStyle) {
        CornerStyle.SQUARY -> Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(12.dp)
        )
        CornerStyle.MEDIUM -> Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp)
        )
        CornerStyle.ROUND -> Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)
        )
    }

    CompositionLocalProvider(LocalCornerStyle provides config.cornerStyle) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
            shapes = shapes,
            content = content
        )
    }
}
