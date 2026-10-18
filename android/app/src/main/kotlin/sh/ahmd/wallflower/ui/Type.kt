package sh.ahmd.wallflower.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * The sizes wallflower's own words come in, in [words]: each a mono size and
 * a grotesque one, which reads smaller. Text in the terminal's font sets its
 * own.
 */
internal object Type {
    /** Small print: notes, hints, a field's label, copy. */
    val caption: TextStyle @Composable get() = TextStyle(fontFamily = words, fontSize = wordSize(11.sp, 13.sp))

    /** Labels: tabs, rows, summaries, the body of a setting. */
    val label: TextStyle @Composable get() = TextStyle(fontFamily = words, fontSize = wordSize(12.sp, 14.sp))

    /** What's read: a dialog's body, a menu's choices. */
    val body: TextStyle @Composable get() = TextStyle(fontFamily = words, fontSize = wordSize(13.sp, 15.sp))

    /** A dialog's or a card's title. */
    val title: TextStyle @Composable get() = TextStyle(fontFamily = words, fontSize = wordSize(16.sp, 20.sp))
}
