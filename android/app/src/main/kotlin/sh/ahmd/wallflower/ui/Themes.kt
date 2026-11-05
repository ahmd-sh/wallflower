package sh.ahmd.wallflower.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import sh.ahmd.wallflower.prefs.Scheme
import sh.ahmd.wallflower.terminal.Theme

/** The themes the user can pick, in their own colors. */
internal object Themes {
    val EverforestDarkHard = Colors(
        bg0 = Color(0xFF1E2326),
        bg1 = Color(0xFF272E33),
        bg2 = Color(0xFF2E383C),
        bg3 = Color(0xFF374145),
        bg4 = Color(0xFF414B50),
        fg = Color(0xFFD3C6AA),
        grey = Color(0xFF859289),
        dim = Color(0xFF5C6A72),
        green = Color(0xFFA7C080),
        orange = Color(0xFFE69875),
        yellow = Color(0xFFDBBC7F),
        red = Color(0xFFE67E80),
        aqua = Color(0xFF83C092),
        blue = Color(0xFF7FBBB3),
        purple = Color(0xFFD699B6),
        bgGreen = Color(0xFF3C4841),
        bgRed = Color(0xFF4C3743),
        terminal = Theme.EverforestDarkHard,
    )

    val GruvboxDark = Colors(
        bg0 = Color(0xFF282828),
        bg1 = Color(0xFF32302F),
        bg2 = Color(0xFF3C3836),
        bg3 = Color(0xFF504945),
        bg4 = Color(0xFF665C54),
        fg = Color(0xFFEBDBB2),
        grey = Color(0xFFA89984),
        dim = Color(0xFF7C6F64),
        green = Color(0xFFB8BB26),
        orange = Color(0xFFFE8019),
        yellow = Color(0xFFFABD2F),
        red = Color(0xFFFB4934),
        aqua = Color(0xFF8EC07C),
        blue = Color(0xFF83A598),
        purple = Color(0xFFD3869B),
        terminal = Theme.GruvboxDark,
    )

    val CatppuccinMocha = Colors(
        bg0 = Color(0xFF1E1E2E),
        bg1 = Color(0xFF313244),
        bg2 = Color(0xFF45475A),
        bg3 = Color(0xFF585B70),
        bg4 = Color(0xFF6C7086),
        fg = Color(0xFFCDD6F4),
        grey = Color(0xFFA6ADC8),
        dim = Color(0xFF7F849C),
        green = Color(0xFFA6E3A1),
        orange = Color(0xFFFAB387),
        yellow = Color(0xFFF9E2AF),
        red = Color(0xFFF38BA8),
        aqua = Color(0xFF94E2D5),
        blue = Color(0xFF89B4FA),
        purple = Color(0xFFCBA6F7),
        terminal = Theme.CatppuccinMocha,
    )

    val CatppuccinLatte = Colors(
        bg0 = Color(0xFFEFF1F5),
        bg1 = Color(0xFFE6E9EF),
        bg2 = Color(0xFFDCE0E8),
        bg3 = Color(0xFFCCD0DA),
        bg4 = Color(0xFFBCC0CC),
        fg = Color(0xFF4C4F69),
        grey = Color(0xFF6C6F85),
        dim = Color(0xFF8C8FA1),
        green = Color(0xFF40A02B),
        orange = Color(0xFFFE640B),
        yellow = Color(0xFFDF8E1D),
        red = Color(0xFFD20F39),
        aqua = Color(0xFF179299),
        blue = Color(0xFF1E66F5),
        purple = Color(0xFF8839EF),
        terminal = Theme.CatppuccinLatte,
        light = true,
    )

    val TokyoNight = Colors(
        bg0 = Color(0xFF1A1B26),
        bg1 = Color(0xFF1F2335),
        bg2 = Color(0xFF292E42),
        bg3 = Color(0xFF3B4261),
        bg4 = Color(0xFF414868),
        fg = Color(0xFFC0CAF5),
        grey = Color(0xFFA9B1D6),
        dim = Color(0xFF565F89),
        green = Color(0xFF9ECE6A),
        orange = Color(0xFFFF9E64),
        yellow = Color(0xFFE0AF68),
        red = Color(0xFFF7768E),
        aqua = Color(0xFF73DACA),
        blue = Color(0xFF7AA2F7),
        purple = Color(0xFFBB9AF7),
        bgGreen = Color(0xFF20303B),
        bgRed = Color(0xFF37222C),
        terminal = Theme.TokyoNight,
    )

    val Nord = Colors(
        bg0 = Color(0xFF2E3440),
        bg1 = Color(0xFF3B4252),
        bg2 = Color(0xFF434C5E),
        bg3 = Color(0xFF4C566A),
        bg4 = Color(0xFF616E88),
        fg = Color(0xFFD8DEE9),
        // Nord has no grey for words between its comments and its text.
        grey = lerp(Color(0xFF616E88), Color(0xFFD8DEE9), 0.4f),
        dim = Color(0xFF616E88),
        green = Color(0xFFA3BE8C),
        orange = Color(0xFFD08770),
        yellow = Color(0xFFEBCB8B),
        red = Color(0xFFBF616A),
        aqua = Color(0xFF8FBCBB),
        blue = Color(0xFF81A1C1),
        purple = Color(0xFFB48EAD),
        terminal = Theme.Nord,
    )
}

/** The colors [scheme] stands for. Material You's follow the wallpaper, and whether the phone is dark. */
@Composable
fun colorsOf(scheme: Scheme): Colors = when (scheme) {
    Scheme.EverforestDarkHard -> Themes.EverforestDarkHard
    Scheme.GruvboxDark -> Themes.GruvboxDark
    Scheme.CatppuccinMocha -> Themes.CatppuccinMocha
    Scheme.CatppuccinLatte -> Themes.CatppuccinLatte
    Scheme.TokyoNight -> Themes.TokyoNight
    Scheme.Nord -> Themes.Nord
    Scheme.MaterialYou -> {
        val context = LocalContext.current
        val dark = isSystemInDarkTheme()
        remember(context, dark) {
            if (dark) materialYou(dynamicDarkColorScheme(context), Themes.EverforestDarkHard) else materialYou(dynamicLightColorScheme(context), Themes.CatppuccinLatte)
        }
    }
}

/**
 * The phone's [scheme] for the backgrounds, words and accent, the
 * terminal's ground and its blacks and whites. The colors that mean
 * something (green added, red failed, the terminal's ANSI hues) are
 * [base]'s, turned toward the scheme's primary so they sit with it.
 */
internal fun materialYou(scheme: ColorScheme, base: Colors): Colors {
    val primary = scheme.primary
    val ansi = base.terminal.palette
    return Colors(
        bg0 = scheme.surface,
        bg1 = scheme.surfaceContainer,
        bg2 = scheme.surfaceContainerHigh,
        bg3 = scheme.surfaceContainerHighest,
        bg4 = scheme.outlineVariant,
        fg = scheme.onSurface,
        grey = scheme.onSurfaceVariant,
        dim = scheme.outline,
        green = harmonize(base.green, primary),
        orange = primary,
        yellow = harmonize(base.yellow, primary),
        red = scheme.error,
        aqua = harmonize(base.aqua, primary),
        blue = harmonize(base.blue, primary),
        purple = scheme.tertiary,
        terminal = Theme(
            background = scheme.surface.rgb,
            foreground = scheme.onSurface.rgb,
            cursor = primary.rgb,
            palette = IntArray(16) { i ->
                when (i) {
                    0 -> scheme.outlineVariant.rgb
                    7 -> scheme.onSurfaceVariant.rgb
                    8 -> scheme.outline.rgb
                    15 -> scheme.onSurface.rgb
                    else -> harmonize(Color(0xFF000000 or ansi[i].toLong()), primary).rgb
                }
            },
        ),
        light = base.light,
    )
}

private val Color.rgb get() = toArgb() and 0xFFFFFF

/** [color]'s hue turned halfway toward [toward]'s, at most 15°, as Material harmonizes colors (here in HSL). A grey has no hue to turn toward. */
internal fun harmonize(color: Color, toward: Color): Color {
    val (h, s, l) = hsl(color)
    val (target, grey) = hsl(toward).let { it[0] to (it[1] == 0f) }
    if (grey) return color
    val diff = (target - h + 540f) % 360f - 180f
    val turned = (h + sign(diff) * min(abs(diff) / 2, 15f) + 360f) % 360f
    return Color.hsl(turned, s, l, color.alpha)
}

private fun hsl(c: Color): FloatArray {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val l = (max + min) / 2
    val d = max - min
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val h = when (max) {
        c.red -> ((c.green - c.blue) / d).mod(6f)
        c.green -> (c.blue - c.red) / d + 2
        else -> (c.red - c.green) / d + 4
    } * 60
    return floatArrayOf(h, (d / (1 - abs(2 * l - 1))).coerceIn(0f, 1f), l)
}
