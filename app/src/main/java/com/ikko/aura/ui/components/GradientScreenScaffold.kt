package com.ikko.aura.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Inset-safe content wrapper used by every screen. The animated mesh-gradient itself is a
 * single instance drawn once behind the whole app in [com.ikko.aura.MainLayout] (so it reads
 * seamlessly across the status bar / nav chrome and doesn't restart on navigation) — screens
 * just sit on top of it transparently. The content lambda gets a [ColumnScope] receiver so
 * screens can use `Modifier.weight()` to let one section (e.g. a scrollable list) fill the
 * remaining space below a fixed-height header.
 *
 * Note this deliberately does *not* reserve room for the floating player pill. Content is meant
 * to scroll underneath it so its frosted glass has something to blur; lists instead add
 * [com.ikko.aura.ui.theme.LocalPlayerPillHeight] to their own `contentPadding`, which keeps the
 * last row reachable without cutting the backdrop short.
 */
@Composable
fun GradientScreenScaffold(
    modifier: Modifier = Modifier,
    applyContentInsets: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .let { if (applyContentInsets) it.windowInsetsPadding(WindowInsets.navigationBars) else it },
    ) {
        content()
    }
}
