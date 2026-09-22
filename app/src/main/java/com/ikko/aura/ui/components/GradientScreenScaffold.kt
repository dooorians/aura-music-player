package com.ikko.aura.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Content wrapper used by every screen. The animated mesh-gradient itself is a
 * single instance drawn once behind the whole app in [com.ikko.aura.MainLayout] (so it reads
 * seamlessly across the status bar / nav chrome and doesn't restart on navigation) — screens
 * just sit on top of it transparently. The content lambda gets a [ColumnScope] receiver so
 * screens can use `Modifier.weight()` to let one section (e.g. a scrollable list) fill the
 * remaining space below a fixed-height header.
 *
 * Note this deliberately does *not* reserve room for the floating player pill or the system
 * navigation bar. Content is meant to scroll underneath both — the pill so its frosted glass has
 * something to blur, the bar so it is transparent rather than a black strip the page stops above.
 * Lists instead add [com.ikko.aura.ui.theme.LocalPlayerPillHeight], which already includes the
 * navigation bar, to their own `contentPadding`, which keeps the last row reachable.
 *
 * A screen whose content is a fixed layout rather than a scrolling list passes
 * [applyContentInsets] so nothing of it ends up beneath the bar.
 *
 * The status bar is padded here rather than around the whole navigation host, so that a screen
 * which wants to paint behind it (the album page's atmosphere) can opt out with
 * [applyStatusBarInset] and do so — the host clips whatever a screen draws outside its own bounds.
 */
@Composable
fun GradientScreenScaffold(
    modifier: Modifier = Modifier,
    applyContentInsets: Boolean = false,
    applyStatusBarInset: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .let { if (applyStatusBarInset) it.windowInsetsPadding(WindowInsets.statusBars) else it }
            .let { if (applyContentInsets) it.windowInsetsPadding(WindowInsets.navigationBars) else it },
    ) {
        content()
    }
}
