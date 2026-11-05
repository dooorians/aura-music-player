package com.ikko.aura.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import com.ikko.aura.navigation.isSettingsPageRoute
import com.ikko.aura.navigation.settingsTripKey
import com.ikko.aura.ui.components.HeroPhase
import com.ikko.aura.ui.components.HeroText
import com.ikko.aura.ui.components.LocalHero
import com.ikko.aura.ui.components.heroPageOut
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.heroTarget

/*
 * How the settings move: the way an album page does. A row that opens a page starts a trip from itself (its words travel to
 * the page's title); what is on the page it leaves steps back and clears from the touched row, and comes back from it on the
 * way back; the page it opens rises in order; and the way back is the trip's own.
 *
 * A new page of the settings, in three lines:
 *   1. a route that starts with `settings/`, or added to SETTINGS_PAGE_ROUTES (navigation/AuraDestination.kt);
 *   2. its body wrapped in [settingsPageBody], its title marked with [settingsPageTitle] (SettingsHeader already does both
 *      for the pages that use it, and PageScaffold hands the rows their order of arrival);
 *   3. opened from a `SettingsRow(tripRoute = <its route>)`.
 * A page that skips 2 still opens and closes (the trip ends by itself); it simply appears without rising.
 */

/** The hero key of the settings page this composition is in, or null outside one: what a trip to it is addressed to. */
@Composable
internal fun rememberSettingsPageKey(): String? {
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry ?: return null
    return remember(entry) {
        val route = entry.destination.route
        if (!isSettingsPageRoute(route)) null
        else settingsTripKey(if (route!!.startsWith("settings/")) "settings/" + (entry.arguments?.getString("page") ?: "") else route)
    }
}

/** The body of a settings page: on the way back it leaves as a whole, wherever it was scrolled to. */
@Composable
internal fun Modifier.settingsPageBody(): Modifier = this.heroPageOut()

/**
 * The title of a settings page: the words of the row that opened it land on it. A trip that carries no words (the picture
 * in Home's header opens the settings) has nothing to land, and the title rises with the page instead; and a page opened
 * without a trip is simply there.
 */
@Composable
internal fun Modifier.settingsPageTitle(title: String, style: TextStyle, color: Color = Color.White.copy(alpha = 0.42f)): Modifier {
    val hero = LocalHero.current
    val key = rememberSettingsPageKey()
    val flies = remember { key != null && hero.phase == HeroPhase.Opening && hero.srcTitle != null && hero.matchesTarget(key) }
    return if (flies && key != null) this.heroTarget(key, text = HeroText(title, Rect.Zero, style, color)) else this.heroRise(0)
}

/** Hands the groups of a page their order of arrival: the first rises first. */
internal class SettingsRise { var next = 0 }

internal val LocalSettingsRise = compositionLocalOf<SettingsRise?> { null }

/** What opens a settings page: the page's route, as it is navigated to. */
internal fun tripKeyOf(route: String): String = settingsTripKey(route)
