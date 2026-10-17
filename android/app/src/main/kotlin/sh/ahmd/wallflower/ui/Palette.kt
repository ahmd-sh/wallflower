package sh.ahmd.wallflower.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import sh.ahmd.wallflower.Look
import sh.ahmd.wallflower.terminal.R

/** Everforest Dark Hard, matching the terminal theme. */
object Palette {
    val bg0 = Color(0xFF1E2326)
    val bg1 = Color(0xFF272E33)
    val bg2 = Color(0xFF2E383C)
    val bg3 = Color(0xFF374145)
    val bg4 = Color(0xFF414B50)
    /** Borders and rules between things, whatever the theme. */
    val border = bg4
    val fg = Color(0xFFD3C6AA)
    val grey = Color(0xFF859289)
    val dim = Color(0xFF5C6A72)
    val green = Color(0xFFA7C080)
    val orange = Color(0xFFE69875)
    val yellow = Color(0xFFDBBC7F)
    val red = Color(0xFFE67E80)
    val aqua = Color(0xFF83C092)
    val blue = Color(0xFF7FBBB3)
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val Grotesque = FontFamily(
    Font(sh.ahmd.wallflower.R.font.bricolage_regular, FontWeight.Normal),
    Font(sh.ahmd.wallflower.R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(sh.ahmd.wallflower.R.font.bricolage_bold, FontWeight.Bold),
)

val LocalLook = staticCompositionLocalOf { Look.Grotesque }

internal val grotesque: Boolean @Composable get() = LocalLook.current == Look.Grotesque

/** The font for wallflower's own words. The terminal, what's typed for it, keys and values stay in [Mono]. */
internal val words: FontFamily @Composable get() = if (grotesque) Grotesque else Mono

/** Grotesque reads smaller than mono at the same size. */
@Composable
internal fun wordSize(mono: TextUnit, sans: TextUnit): TextUnit = if (grotesque) sans else mono

/** Material components (menus, dialogs) in the same colors and fonts. */
@Composable
fun WallflowerTheme(look: Look = Look.Grotesque, content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.words() = copy(fontFamily = if (look == Look.Grotesque) Grotesque else Mono, letterSpacing = 0.sp)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.green,
            onPrimary = Palette.bg0,
            secondary = Palette.orange,
            background = Palette.bg0,
            onBackground = Palette.fg,
            surface = Palette.bg1,
            onSurface = Palette.fg,
            onSurfaceVariant = Palette.grey,
            surfaceContainer = Palette.bg2,
            surfaceContainerHigh = Palette.bg2,
            surfaceContainerHighest = Palette.bg3,
            outline = Palette.bg3,
            error = Palette.red,
        ),
        typography = Typography(
            titleLarge = base.titleLarge.words(),
            titleMedium = base.titleMedium.words(),
            bodyLarge = base.bodyLarge.words(),
            bodyMedium = base.bodyMedium.words(),
            labelLarge = base.labelLarge.words().let { if (look == Look.Grotesque) it.copy(fontSize = 16.sp) else it },
        ),
    ) {
        // Text outside Material components keeps the plain style it was designed with.
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, LocalLook provides look, content = content)
    }
}
