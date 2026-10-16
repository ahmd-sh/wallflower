package sh.ahmd.wallflower.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import sh.ahmd.wallflower.terminal.R

/** Everforest Dark Hard, matching the terminal theme. */
object Palette {
    val bg0 = Color(0xFF1E2326)
    val bg1 = Color(0xFF272E33)
    val bg2 = Color(0xFF2E383C)
    val bg3 = Color(0xFF374145)
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
