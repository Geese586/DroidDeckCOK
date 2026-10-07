package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

// Sizes that decide how the launcher reflows on 4:3 and square screens (480x360 or 480x480 dp) as
// well as on the 16:9 ones it was first laid out for.

/** A page narrower than this reflows: settings rows stack, grids drop columns, posters go. */
val NarrowPaneWidth = 520.dp

/** Whether the page being drawn is narrow - the pane beside the rail on a 4:3 or square screen. */
val LocalNarrowPane = compositionLocalOf { false }

/** Too narrow a screen for a labelled rail: the rail shows icons only. */
@Composable
internal fun isNarrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 600

