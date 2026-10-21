package com.ntpx.alchemist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ntpx.alchemist.R

object Ntpx {
    val Void = Color(0xFF06070F)
    val Navy = Color(0xFF0B1024)
    val Panel = Color(0xFF11172E)
    val Glass = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x33A78BFA)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Magenta = Color(0xFFE879F9)
    val Text = Color(0xFFE6E9F5)
    val Muted = Color(0xFF8C93B0)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
}

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private val Scheme = darkColorScheme(
    primary = Ntpx.Cyan,
    onPrimary = Ntpx.Void,
    secondary = Ntpx.Violet,
    onSecondary = Color.White,
    tertiary = Ntpx.Magenta,
    background = Ntpx.Void,
    onBackground = Ntpx.Text,
    surface = Ntpx.Navy,
    onSurface = Ntpx.Text,
    surfaceVariant = Ntpx.Panel,
    onSurfaceVariant = Ntpx.Muted,
    surfaceContainer = Ntpx.Navy,
    surfaceContainerHigh = Ntpx.Panel,
    surfaceContainerHighest = Ntpx.Panel,
    outline = Ntpx.GlassBorder,
    outlineVariant = Ntpx.GlassBorder,
    error = Ntpx.Danger,
    secondaryContainer = Color(0x408B5CF6),
    onSecondaryContainer = Ntpx.Text,
)

private val Type = Typography(
    displaySmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 1.5.sp),
    headlineSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.5.sp),
    titleSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodySmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
)

@Composable
fun AlchemistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
