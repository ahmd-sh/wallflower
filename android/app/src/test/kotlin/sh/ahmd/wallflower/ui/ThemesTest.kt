package sh.ahmd.wallflower.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemesTest {
    private val themes = with(Themes) { listOf(EverforestDarkHard, GruvboxDark, CatppuccinMocha, CatppuccinLatte, TokyoNight, Nord) }

    @Test
    fun thePageAroundTheTerminalIsTheTerminalsBackground() {
        for (t in themes) assertEquals(t.terminal.background, t.bg0.toArgb() and 0xFFFFFF)
    }

    @Test
    fun harmonizingTurnsAHueHalfwayTowardTheOtherAtMost15Degrees() {
        val red = Color.hsl(0f, 0.8f, 0.5f)
        assertEquals(10f, hue(harmonize(red, Color.hsl(20f, 0.5f, 0.5f))), 0.5f)
        assertEquals(15f, hue(harmonize(red, Color.hsl(90f, 0.5f, 0.5f))), 0.5f)
        assertEquals(345f, hue(harmonize(red, Color.hsl(240f, 0.5f, 0.5f))), 0.5f)
        assertEquals(red, harmonize(red, Color.hsl(0f, 0f, 0.5f)))
    }

    private fun hue(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val d = max - minOf(c.red, c.green, c.blue)
        return when (max) {
            c.red -> ((c.green - c.blue) / d).mod(6f)
            c.green -> (c.blue - c.red) / d + 2
            else -> (c.red - c.green) / d + 4
        } * 60
    }
}
