package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SteamCdn
import com.droiddeck.launcher.session.SteamCdnProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow

// The session's loading screen, its paused screen and the HUD text style.

@Composable
fun HudText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0x8C000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * One line of the loading screen's checklist; [detail] is what it says while it is the one running,
 * and [dragging] what it says once that has taken long enough to need saying.
 */
private class LoadStage(
    @androidx.annotation.StringRes val label: Int,
    @androidx.annotation.StringRes val detail: Int,
    @androidx.annotation.StringRes val dragging: Int? = null,
)

private fun loadStages(steam: Boolean): List<LoadStage> = if (steam) listOf(
    LoadStage(R.string.load_runtime, R.string.load_runtime_detail),
    LoadStage(R.string.load_session, R.string.load_session_detail),
    LoadStage(R.string.load_steam, R.string.load_steam_detail),
    LoadStage(R.string.load_opening_steam, R.string.load_opening_steam_detail, R.string.load_opening_steam_slow),
) else listOf(
    LoadStage(R.string.load_runtime, R.string.load_runtime_detail),
    LoadStage(R.string.load_desktop, R.string.load_desktop_detail),
    LoadStage(R.string.load_opening, R.string.load_opening_detail),
)

/** How long the last stage runs before it stops assuming the next second is the one. */
private const val DRAGGING_AFTER_SECONDS = 45

/**
 * The checklist stage a loading line's [topic] belongs to, -1 when it says nothing about the stage.
 * The lines are the session script's "== STEP" milestones and the installer's own progress; they
 * arrive in order, and the screen never steps back.
 */
private fun stageOf(topic: LoadingState.Topic, steam: Boolean): Int = when (topic) {
    LoadingState.Topic.RUNTIME -> 0
    LoadingState.Topic.SESSION -> 1
    LoadingState.Topic.DESKTOP -> if (steam) -1 else 1
    LoadingState.Topic.STEAM_STARTING -> if (steam) 3 else -1
    LoadingState.Topic.STEAM -> if (steam) 2 else -1
    LoadingState.Topic.OTHER -> -1
}

@Composable
fun LoadingOverlay(
    step: String, percent: Int, elapsed: String, hint: String, ended: Boolean,
    /** What [step] is about, and whether it is a download or install line worth showing as it is (see [LoadingState]). */
    topic: LoadingState.Topic, readable: Boolean,
    /** How long the stage in [topic] has been running, so a long one can say more about itself. */
    stageSeconds: Int = 0,
    /** The host a running download is coming from, empty when nothing is downloading (see [LoadingState]). */
    source: String = "",
    /** The Steam client's line ranking as measured for this session, empty when it was not measured. */
    steamLine: String = "",
    /** How fast the running Steam download is arriving, empty when nothing is downloading. */
    speed: String = "",
    /**
     * The edges the line test ranked for this session, fastest first (SteamCdn's values). The list
     * the screen offers is these plus "automatic" - the client-update chain has exactly two edges,
     * so three rows is all of it and not a sample of something longer.
     */
    steamChoices: List<String> = emptyList(),
    /** The edge asked for right now: a value from [steamChoices], or empty for automatic. */
    steamPick: String = "",
    /** A tap on that list; null keeps the list off the screen (a session with no edge to choose). */
    onSteamPick: ((String) -> Unit)? = null,
    /** One headline per checklist stage, in the same order as the stages [steam] selects. */
    titles: List<String>, steam: Boolean = true, onCancel: (() -> Unit)? = null,
    /** An ended session's exit status and log path, shown small under the advice. */
    endedDetail: String? = null,
    onRetry: (() -> Unit)? = null,
    onShareLogs: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /** The blue the page opens on when a button flooded to it, gathered into the throbber's ball. */
    flood: Color? = null,
    onFlooded: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var ball by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    val stages = remember(steam) { loadStages(steam) }
    var reached by remember { mutableStateOf(0) }
    LaunchedEffect(step, topic, steam) {
        val at = stageOf(topic, steam)
        if (at > reached) reached = at
    }
    val active = reached.coerceAtMost(stages.lastIndex)
    val title = titles.getOrElse(active) { titles.lastOrNull().orEmpty() }
    // A stage that has been running for a while without anything to show for it says what it is
    // waiting on, rather than repeating the promise it cannot keep: the screen cannot be still and
    // say nothing, which is what makes a slow first launch look like a hang.
    val dragging = !ended && active == stages.lastIndex && stageSeconds >= DRAGGING_AFTER_SECONDS
    val detail = when {
        percent >= 0 || readable -> step.replaceFirstChar { it.uppercase() }
        dragging -> stringResource(stages[active].dragging ?: stages[active].detail)
        else -> stringResource(stages[active].detail)
    }
    // The run that offers the edges: only the advanced Play asks for it, and only a Steam session
    // has edges to offer. Everything that belongs to "the choice and the numbers to judge it by"
    // hangs off this one flag, so the plain Play's screen stays the screen it always was.
    val advanced = !ended && steam && onSteamPick != null && steamChoices.isNotEmpty()
    val context = LocalContext.current
    // The Steam tab's wall behind it, dimmed and slowed: starting Steam reads as the same place settling in.
    val games by produceState(emptyList<Library.SteamGame>()) {
        value = withContext(Dispatchers.IO) { runCatching { Library.steamGames(context).sortedByDescending { it.lastPlayed } }.getOrDefault(emptyList()) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        CapsuleWall(games, driftMs = 65_000, modifier = Modifier.alpha(0.3f))
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to colors.background.copy(alpha = 0.94f),
                        0.6f to colors.background.copy(alpha = 0.72f),
                        1f to colors.background.copy(alpha = 0.55f),
                        center = Offset(size.width / 2f, size.height * 0.46f), radius = maxOf(size.width, size.height) * 0.55f,
                    ),
                )
            },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to colors.background.copy(alpha = 0.95f))))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            // Held on the plain D while the flood gathers, and started fresh when it lands.
            key(flood == null) {
                LogoThrobber(
                    Modifier.width(if (ended) 88.dp else 110.dp).onGloballyPositioned { ball = throbberBall(it.boundsInRoot()).also { b -> ThrobberSpot.ball = b } },
                    running = !ended && flood == null,
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (ended) stringResource(R.string.load_ended) else title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (ended) step else detail, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (ended) 12 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp),
            )
            // Why this line, above which line: the ranking the install's edge was chosen from, then
            // the edge the bytes are actually coming from. Both read while the download runs, which
            // is when the question is asked - the Toast that measured them is long gone by then.
            if (!ended && steamLine.isNotEmpty()) Text(
                stringResource(R.string.load_steam_line, steamLine),
                fontSize = 12.sp, color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!ended && source.isNotEmpty()) Text(
                stringResource(R.string.load_source, source),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            // How fast, not just how far: a crawl and a dead host look identical on a bar, and this
            // number is what the user reads before deciding to tap another line just below. On the
            // advanced run it is on that row already, next to the chips it is about.
            if (!ended && !advanced && topic == LoadingState.Topic.STEAM && speed.isNotEmpty()) Text(
                stringResource(R.string.load_speed, speed),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            // The two edges the client install picks from, offered by hand while it runs: the script
            // switches mid-file when one is tapped (see SteamLineSwitch), so the choice is not
            // advice for next time but a change to this download.
            //
            // Shown only when this run asked for it - the "advanced play" button; Play's screen is
            // the one it always was. It outlives the stage it belongs to: a client that is already
            // current finishes the install stage in about two seconds, which used to put this list
            // on screen for two seconds - long enough to see, too short to touch. And it takes taps
            // before the install starts, not only during it: the file it writes is read at the
            // first fetch, so a choice made while the session is still coming up decides the edge
            // the download begins on.
            //
            // The clock and the rate ride this row (see SteamLinePicker); the two lines they used to
            // own further down are dropped for that run, or the same number would be on screen twice
            // - once where it is read, once where it is out of the way.
            if (advanced) {
                SteamLinePicker(
                    steamChoices, steamPick, elapsed = elapsed, speed = speed,
                    onPick = onSteamPick!!,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (!ended) LoadSegments(stages.size, active, percent, Modifier.padding(top = 18.dp))
            // The clock, so a quiet stretch still reads as work going on rather than a hang.
            if (!ended && !advanced && elapsed.isNotEmpty()) Text(
                elapsed, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (ended && endedDetail != null) Text(
                endedDetail, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 520.dp),
            )
            if (ended) EndedActions(onRetry, onShareLogs, onClose)
        }
        if (!ended && hint.isNotEmpty()) Text(
            hint, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 120.dp, end = 120.dp, bottom = 28.dp),
        )
        if (!ended && onCancel != null) CancelHint(onCancel, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
        // The fork's own version, top left as on the Steam page: a build handed around by hand
        // should be identifiable from the screen it is on, this one included.
        Text(
            "COK" + com.droiddeck.launcher.BuildConfig.FORK_VERSION,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 16.dp),
        )
        if (flood != null) FloodGather(flood, ball, onFlooded)
    }
}

/** What to do about an ended session: nothing leaves on a timer, and the pad starts on Try again. */
@Composable
private fun EndedActions(onRetry: (() -> Unit)?, onShareLogs: (() -> Unit)?, onClose: (() -> Unit)?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
        var firstUsed = false
        fun claim(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(first) }
        if (onRetry != null) PrimaryButton(stringResource(R.string.store_try_again), modifier = claim(), onClick = onRetry)
        if (onShareLogs != null) SecondaryButton(stringResource(R.string.drawer_share_logs), modifier = claim(),
            progress = com.droiddeck.launcher.session.SessionLogShare.progress, onClick = onShareLogs)
        if (onClose != null) SecondaryButton(stringResource(R.string.mode_back), modifier = claim(), onClick = onClose)
    }
}

/** One segment per stage: done ones filled, the current one filling (or running, with no percent). */
@Composable
private fun LoadSegments(count: Int, active: Int, percent: Int, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    val run = rememberInfiniteTransition(label = "segment")
        .animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "run")
    val still = Motion.scale == 0f
    val stepDescription = stringResource(R.string.load_step, active + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = stepDescription },
    ) {
        repeat(count) { i ->
            Box(
                Modifier.width(44.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < active) pal.signal else track)
                    .drawBehind {
                        if (i != active) return@drawBehind
                        if (percent >= 0) drawRect(pal.signal, size = Size(size.width * percent.coerceIn(0, 100) / 100f, size.height))
                        else if (still) drawRect(pal.signal.copy(alpha = 0.5f))
                        else drawRect(pal.signal, topLeft = Offset(size.width * run.value, 0f), size = Size(size.width * 0.4f, size.height))
                    },
            )
        }
    }
}

/** Cancel as the pad's B, in the corner; a tap works too. */
@Composable
private fun CancelHint(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsHoveredAsState().value || src.collectIsFocusedAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (hot) colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onCancel)
            .padding(horizontal = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).clip(CircleShape).background(colors.onBackground)) {
            Text("B", color = colors.background, style = TextStyle(
                fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ))
        }
        Text(stringResource(R.string.common_cancel), fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

/** A session paused in the background, ready to pick up where it left off. */
@Composable
fun SessionPausedOverlay(title: String = stringResource(R.string.load_steam_paused), onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { resumeFocus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pal.background.copy(alpha = 0.94f))
            .clickable(interactionSource = interactionSource, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 440.dp).padding(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 6.dp).size(64.dp).border(2.dp, pal.line2, CircleShape)) {
                Icon(Icons.Filled.Pause, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(30.dp))
            }
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, textAlign = TextAlign.Center)
            PrimaryButton(
                stringResource(R.string.resume_session),
                modifier = Modifier.padding(top = 14.dp).focusRequester(resumeFocus).controllerConfirm(onClick = onResume),
                onClick = onResume,
            )
        }
    }
}

/**
 * Which edge this download should use, as three chips: automatic first, then the two edges - and
 * beside them the two numbers that say what the download is doing with that edge.
 *
 * Three is the whole menu, not a page of one: the client-update chain has exactly two edges (see
 * the archive's Z section - the third candidate host does not even resolve), so "automatic" plus
 * two is every choice that exists. The tap changes the file in flight rather than a setting for
 * next time: the install script watches for it and restarts the current component on that edge.
 *
 * The clock and the rate belong here rather than on lines of their own. They are the answer to the
 * question the chips ask - "is this edge worth keeping" - and they used to be somewhere else: the
 * rate on a line of its own above, the clock under the bar at the bottom of the page, where the
 * hint that slides in at the bottom sat on top of it (DroidDeckCOK). Reading a rate and a choice
 * that are a whole panel apart is how "the tap changed nothing" was concluded from a screen that
 * was never showing the two things together.
 *
 * The chips are ordinary controls of this app, not labels that happen to take a tap: they take
 * focus from the pad (the ring and the lit border are the same language as every other control),
 * A confirms, and touch works as it always did. The first chip asks for the focus when the row
 * appears, so a pad user has something under the ring before pressing anything - a controller's
 * A goes to whatever holds focus, and without this the row was visible, lit and unreachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SteamLinePicker(
    choices: List<String>,
    chosen: String,
    /** "1:23 elapsed" - the clock, on this row instead of under the bar. */
    elapsed: String,
    /** "1.2 MB/s", or empty when nothing is being fetched. */
    speed: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // A page narrow enough to run out of width wraps rather than clips: three chips, a clock and a
    // rate are one line on this device and two on a phone held upright, and neither is a puzzle.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.load_steam_pick),
            fontSize = 12.sp, color = colors.onSurfaceVariant.copy(alpha = 0.7f),
        )
        LineChip(
            stringResource(R.string.load_steam_pick_auto), chosen.isEmpty(),
            modifier = Modifier.focusRequester(first),
        ) { onPick(SteamCdn.AUTO) }
        choices.forEach { value ->
            LineChip(SteamCdnProbe.label(SteamCdn.hosts(value).first()), chosen == value) { onPick(value) }
        }
        if (elapsed.isNotEmpty()) Text(
            elapsed, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
        )
        if (speed.isNotEmpty()) Text(
            speed, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LineChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val src = remember { MutableInteractionSource() }
    // Focused (pad) or hovered (mouse): the same "under the finger" the rest of the app draws,
    // so which chip A would press is never a guess.
    val hot = rememberHot(src)
    Text(
        text,
        fontSize = 12.sp,
        color = if (active) colors.primary else colors.onSurfaceVariant,
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    active -> colors.primary.copy(alpha = 0.16f)
                    hot -> colors.primary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .border(
                if (active || hot) 2.dp else 1.dp,
                when {
                    active -> colors.primary.copy(alpha = 0.55f)
                    hot -> colors.primary
                    else -> colors.onSurfaceVariant.copy(alpha = 0.22f)
                },
                shape,
            )
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
