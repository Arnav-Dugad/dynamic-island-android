package com.arnav.island.settings.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnav.island.storage.AppThemeMode

/** Extra colours for the settings UI (section badges, status). */
@Immutable
data class IslandUiColors(
    val isDark: Boolean,
    val card: Color,
    val cardPressed: Color,
    val divider: Color,
    val subtle: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
)

val LocalIslandColors = staticCompositionLocalOf {
    IslandUiColors(true, Color(0xFF141417), Color(0xFF1C1C20), Color(0x14FFFFFF), Color(0x99FFFFFF), Color(0xFF3DDC84), Color(0xFFFFA23A), Color(0xFFFF5147))
}

/** Section badge colours, a calm spectrum reminiscent of the launcher icon rim. */
object BadgeColors {
    val Cyan = Color(0xFF2EC5D3)
    val Blue = Color(0xFF4C7DFF)
    val Indigo = Color(0xFF6D5BFF)
    val Violet = Color(0xFF9B5CFF)
    val Pink = Color(0xFFFF5C9A)
    val Red = Color(0xFFFF5147)
    val Orange = Color(0xFFFF9330)
    val Yellow = Color(0xFFF5B82E)
    val Green = Color(0xFF2FBF71)
    val Teal = Color(0xFF1FB5A0)
    val Graphite = Color(0xFF6B7280)
}

private val IslandDark = darkColorScheme(
    primary = Color(0xFF9DB2FF),
    onPrimary = Color(0xFF0A1440),
    primaryContainer = Color(0xFF26315E),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFF7CF7FF),
    onSecondary = Color(0xFF00363B),
    tertiary = Color(0xFFFF8CC6),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF09090B),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1C1C21),
    onSurfaceVariant = Color(0xFFB4B6C0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101013),
    surfaceContainer = Color(0xFF15151A),
    surfaceContainerHigh = Color(0xFF1C1C22),
    surfaceContainerHighest = Color(0xFF24242B),
    outline = Color(0xFF3A3B44),
    outlineVariant = Color(0xFF26272E),
)

private val IslandLight = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF00838F),
    tertiary = Color(0xFFC2185B),
    background = Color(0xFFF4F4F7),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFF4F4F7),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFE6E6EC),
    onSurfaceVariant = Color(0xFF55565F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F4),
    surfaceContainerHighest = Color(0xFFE8E8EE),
    outline = Color(0xFFC6C6D0),
    outlineVariant = Color(0xFFE2E2E8),
)

private val IslandTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.5.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val IslandShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

@Composable
fun IslandTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    amoled: Boolean = true,
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> IslandDark
        else -> IslandLight
    }
    if (dark) {
        // Keep the carefully tuned neutral surfaces even with dynamic accents.
        scheme = scheme.copy(
            background = if (amoled) Color.Black else IslandDark.background,
            surface = if (amoled) Color.Black else IslandDark.surface,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = if (amoled) Color(0xFF0B0B0D) else IslandDark.surfaceContainerLow,
            surfaceContainer = if (amoled) Color(0xFF121215) else IslandDark.surfaceContainer,
            surfaceContainerHigh = IslandDark.surfaceContainerHigh,
            surfaceContainerHighest = IslandDark.surfaceContainerHighest,
            onSurface = IslandDark.onSurface,
            onBackground = IslandDark.onBackground,
            outlineVariant = IslandDark.outlineVariant,
        )
    } else {
        scheme = scheme.copy(
            background = IslandLight.background,
            surface = IslandLight.surface,
            surfaceContainer = IslandLight.surfaceContainer,
            surfaceContainerLow = IslandLight.surfaceContainerLow,
            surfaceContainerHigh = IslandLight.surfaceContainerHigh,
        )
    }
    val extra = if (dark) {
        IslandUiColors(true, scheme.surfaceContainer, scheme.surfaceContainerHigh, Color(0x12FFFFFF), Color(0x99FFFFFF), Color(0xFF3DDC84), Color(0xFFFFA23A), Color(0xFFFF5147))
    } else {
        IslandUiColors(false, scheme.surfaceContainer, scheme.surfaceContainerHigh, Color(0x14000000), Color(0x99000000), Color(0xFF1E9E5A), Color(0xFFD9761A), Color(0xFFD7372E))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalIslandColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = IslandTypography, shapes = IslandShapes, content = content)
    }
}

val TitleHero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, lineHeight = 40.sp)
