package sh.ahmd.wallflower.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** [value], or while it's null the last it wasn't: what's still shown as something leaves. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null && value != last) last = value
    return value ?: last
}
