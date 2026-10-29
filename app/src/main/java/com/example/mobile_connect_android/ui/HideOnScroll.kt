package com.example.mobile_connect_android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * How far a screen's header has slid away: scrolling down hides it, scrolling up brings it back.
 * [canScroll] says whether the content is long enough to scroll; when it isn't, the header stays put.
 */
class HideOnScrollState(private val canScroll: () -> Boolean) {
    /** 0 when fully shown, down to minus the header's height when hidden. */
    var offset by mutableFloatStateOf(0f)
        private set
    internal var height = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0 && !canScroll()) return Offset.Zero
            val before = offset
            offset = (offset + available.y).coerceIn(-height, 0f)
            // The header takes the scroll while it moves, so the list doesn't move twice as fast.
            return Offset(0f, offset - before)
        }
    }

    fun show() {
        offset = 0f
    }
}

@Composable
fun rememberHideOnScroll(canScroll: () -> Boolean): HideOnScrollState {
    val latest by rememberUpdatedState(canScroll)
    return remember { HideOnScrollState { latest() } }
}

/** A screen's top bar (and anything under it) that hides on scroll. The status bar area always stays. */
@Composable
fun HideOnScrollHeader(state: HideOnScrollState, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    state.height = placeable.height.toFloat()
                    val offset = state.offset.coerceAtLeast(-state.height).roundToInt()
                    layout(placeable.width, placeable.height + offset) { placeable.place(0, offset) }
                },
            content = content
        )
    }
}
