package sh.ahmd.wallflower.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import sh.ahmd.wallflower.prefs.Look
import sh.ahmd.wallflower.terminal.R
import sh.ahmd.wallflower.terminal.Theme

/**
 * wallflower's colors in one theme, named after Everforest's: backgrounds
 * from the page ([bg0]) up, words from [fg] down to [dim], accents, and the
 * [terminal]'s colors to match. [light] for a light theme.
 */
@Immutable
class Colors(
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val bg4: Color,
    val fg: Color,
    val grey: Color,
    val dim: Color,
    val green: Color,
    val orange: Color,
    val yellow: Color,
    val red: Color,
    val aqua: Color,
    val blue: Color,
    val purple: Color,
    val terminal: Theme,
    val light: Boolean = false,
    /** Behind a diff's added lines. */
    val bgGreen: Color = lerp(bg1, green, 0.2f),
    /** Behind its removed ones. */
    val bgRed: Color = lerp(bg1, red, 0.2f),
) {
    /** Borders and rules between things, whatever the theme. */
    val border: Color get() = bg4
}

val LocalPalette = staticCompositionLocalOf { Themes.EverforestDarkHard }

/** The colors of the theme the user picked. */
val Palette: Colors @Composable @ReadOnlyComposable get() = LocalPalette.current

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

/** The [colors] and [look] for everything inside, Material components (menus, dialogs) too. */
@Composable
fun WallflowerTheme(look: Look = Look.Grotesque, colors: Colors = Themes.EverforestDarkHard, content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.words() = copy(fontFamily = if (look == Look.Grotesque) Grotesque else Mono, letterSpacing = 0.sp)
    MaterialTheme(
        colorScheme = remember(colors) { colors.material() },
        typography = Typography(
            titleLarge = base.titleLarge.words(),
            titleMedium = base.titleMedium.words(),
            bodyLarge = base.bodyLarge.words(),
            bodyMedium = base.bodyMedium.words(),
            labelLarge = base.labelLarge.words().let { if (look == Look.Grotesque) it.copy(fontSize = 16.sp) else it },
        ),
    ) {
        // Text outside Material components keeps the plain style it was designed with.
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, LocalLook provides look, LocalPalette provides colors, content = content)
    }
}

/** Material components (menus, dialogs) in the same colors. */
private fun Colors.material(): ColorScheme = (if (light) lightColorScheme() else darkColorScheme()).copy(
    primary = green,
    onPrimary = bg0,
    surfaceTint = green,
    secondary = orange,
    background = bg0,
    onBackground = fg,
    surface = bg1,
    onSurface = fg,
    onSurfaceVariant = grey,
    surfaceContainer = bg2,
    surfaceContainerHigh = bg2,
    surfaceContainerHighest = bg3,
    outline = bg3,
    error = red,
)
