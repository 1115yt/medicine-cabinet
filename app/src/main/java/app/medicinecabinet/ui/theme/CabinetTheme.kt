package app.medicinecabinet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.medicinecabinet.domain.InterfaceSize

private val LightColors = lightColorScheme(
    primary = Color(0xFF196D63), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE5), onPrimaryContainer = Color(0xFF124D43),
    secondary = Color(0xFF53634F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECDA), onSecondaryContainer = Color(0xFF35422F),
    tertiary = Color(0xFF835627), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7C4), onTertiaryContainer = Color(0xFF603F1C),
    background = Color(0xFFF5F6F1), onBackground = Color(0xFF1C2B25),
    surface = Color(0xFFFCFDF8), onSurface = Color(0xFF1C2B25),
    surfaceVariant = Color(0xFFEAF0E8), onSurfaceVariant = Color(0xFF526158),
    outline = Color(0xFF7F8A80), outlineVariant = Color(0xFFD6DED3),
    error = Color(0xFFA63235), onError = Color.White,
    errorContainer = Color(0xFFFFDAD8), onErrorContainer = Color(0xFF783032),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD1BD), onPrimary = Color(0xFF063C33),
    primaryContainer = Color(0xFF214D40), onPrimaryContainer = Color(0xFFBCEFD9),
    secondary = Color(0xFFBDCDB5), onSecondary = Color(0xFF243321),
    secondaryContainer = Color(0xFF374C33), onSecondaryContainer = Color(0xFFD8EAD1),
    tertiary = Color(0xFFE8BF89), onTertiary = Color(0xFF442C12),
    tertiaryContainer = Color(0xFF594326), onTertiaryContainer = Color(0xFFFFDEB4),
    background = Color(0xFF111A16), onBackground = Color(0xFFE5EAE2),
    surface = Color(0xFF18231E), onSurface = Color(0xFFE5EAE2),
    surfaceVariant = Color(0xFF26352C), onSurfaceVariant = Color(0xFFB9C8BB),
    outline = Color(0xFF8C9B8D), outlineVariant = Color(0xFF3D4C40),
    error = Color(0xFFFFB4AE), onError = Color(0xFF590F15),
    errorContainer = Color(0xFF652B31), onErrorContainer = Color(0xFFFFDAD8),
)

private val CabinetTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinetTheme(darkTheme: Boolean = isSystemInDarkTheme(), interfaceSize: InterfaceSize = InterfaceSize.STANDARD,
    content: @Composable () -> Unit) {
    val physicalDensity = LocalDensity.current
    val density = Density(physicalDensity.density * interfaceSize.scale, physicalDensity.fontScale)
    val physicalConfiguration = LocalViewConfiguration.current
    val touchSize = (48f / interfaceSize.scale).dp
    val configuration = remember(physicalConfiguration, touchSize) {
        object : ViewConfiguration by physicalConfiguration {
            override val minimumTouchTargetSize = DpSize(touchSize, touchSize)
        }
    }
    // 缩放视觉尺寸时补偿触控区域，保持至少 48 个实际 dp。
    CompositionLocalProvider(LocalDensity provides density, LocalViewConfiguration provides configuration,
        LocalMinimumInteractiveComponentSize provides touchSize) {
        MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CabinetTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content,
        )
    }
}
