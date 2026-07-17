package com.homeattach.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.homeattach.app.R
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.RemoteSession
import com.homeattach.app.ssh.RemoteSessionFeed
import com.homeattach.app.ssh.SessionsSnapshot
import com.homeattach.app.ssh.createRemoteSession
import com.homeattach.app.ssh.killRemoteSession
import com.homeattach.app.ui.theme.MonoBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/** How long pull-to-refresh keeps spinning: the feed is already live, so this is dwell, not work. */
private const val REFRESH_INDICATOR_MS = 450L

/** How long a new session gets to appear in the feed before we open it on its internal id. */
private const val NEW_SESSION_LABEL_WAIT_MS = 2_000L

/** Separates a label from the id tacked on when two sessions share a directory outright. */
private const val LABEL_ID_MARKER = "\u00b7"
private const val LABEL_ID_CHARS = 4

/**
 * What the user sees as the session's title: the last path component of its live cwd (matching
 * how yakuake titles a tab by where it is, not by an internal id). The session [RemoteSession.name]
 * is an auto-generated identity (tsess-auto) and stays internal. Falls back to the name when the
 * host couldn't resolve a cwd.
 */
internal fun RemoteSession.displayLabel(): String {
    val segments = cwdSegments()
    return segments.lastOrNull() ?: name
}

private fun RemoteSession.cwdSegments(): List<String> {
    val trimmed = cwd.trimEnd('/')
    if (trimmed.isBlank() || trimmed == "?") return emptyList()
    return trimmed.split('/').filter { it.isNotBlank() }
}

/**
 * Labels for a whole list at once, keyed by session name.
 *
 * A title is only worth anything if it is unique, and titling by the cwd's last component is not:
 * two tabs open in one project produce two cards both reading "gpu-ops" with nothing to tell them
 * apart. So collisions widen leftward along the path ("linege/gpu-ops"), and when even that cannot
 * separate them — two tabs in the *same* directory, which is the common case — the label falls
 * back to the session id, which is the only thing about them that actually differs.
 */
internal fun List<RemoteSession>.displayLabels(): Map<String, String> {
    val segments = associate { it.name to it.cwdSegments() }
    val labels = mutableMapOf<String, String>()
    var pending = map { it.name }
    var depth = 1

    while (pending.isNotEmpty()) {
        val candidates = pending.associateWith { name ->
            val segs = segments.getValue(name)
            if (segs.isEmpty()) name else segs.takeLast(depth).joinToString("/")
        }
        val perLabel = candidates.values.groupingBy { it }.eachCount()
        val (unique, colliding) = pending.partition { perLabel.getValue(candidates.getValue(it)) == 1 }
        unique.forEach { labels[it] = candidates.getValue(it) }
        if (colliding.isEmpty()) break

        if (colliding.none { segments.getValue(it).size > depth }) {
            // Identical cwds: no amount of path buys a distinction. Tag the shortest form with
            // the id rather than showing the same words twice.
            colliding.forEach { name ->
                val base = segments.getValue(name).lastOrNull() ?: name
                labels[name] = base + LABEL_ID_MARKER + name.takeLast(LABEL_ID_CHARS)
            }
            break
        }
        pending = colliding
        depth += 1
    }
    return labels
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    settingsStore: SettingsStore,
    onOpenSession: (name: String, label: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingKill by remember { mutableStateOf<RemoteSession?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The screen's whole data path: one process-wide feed, shared with the terminal's drawer. The
    // host pushes the full list the instant a session is born or dies, so there is nothing to poll
    // and no connection of our own to babysit.
    val snapshot by RemoteSessionFeed.sessions(settingsStore).collectAsStateWithLifecycle()

    // A killed session leaves its card the moment the host is asked rather than on the next feed
    // tick, so the swipe visibly did something. The feed remains the authority: a name that turns
    // out to have survived reappears, and one that really died stops being listed at all.
    val killed = remember { mutableStateListOf<String>() }
    LaunchedEffect(snapshot) {
        val live = (snapshot as? SessionsSnapshot.Live)?.sessions?.mapTo(mutableSetOf()) { it.name }
            ?: return@LaunchedEffect
        killed.retainAll { it in live }
    }

    fun refresh() {
        scope.launch {
            isRefreshing = true
            RemoteSessionFeed.retryNow()
            // The feed is already live; this only skips its backoff. Hold the indicator long
            // enough to read as an action rather than a flicker.
            delay(REFRESH_INDICATOR_MS)
            isRefreshing = false
        }
    }

    // Asks the PC to open a tab, then walks straight into the session it became. Slow enough
    // (yakuake spawns a tab, its profile brings the session up) that the button has to show it is
    // working, or it reads as unresponsive and gets tapped twice.
    var isCreating by remember { mutableStateOf(false) }
    fun createSession() {
        if (isCreating) return
        isCreating = true
        scope.launch {
            try {
                val config = settingsStore.load()
                val name = withContext(Dispatchers.IO) { createRemoteSession(config) }
                // The feed sees the new socket within a tick of it existing. Waiting for it buys
                // a real title (its cwd) instead of opening on the internal id; if it somehow
                // does not arrive, the id still works.
                val label = withTimeoutOrNull(NEW_SESSION_LABEL_WAIT_MS) {
                    RemoteSessionFeed.sessions(settingsStore)
                        .mapNotNull { snap ->
                            (snap as? SessionsSnapshot.Live)?.sessions?.firstOrNull { it.name == name }
                        }
                        .first()
                        .displayLabel()
                } ?: name
                onOpenSession(name, label)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.sessions_new_failed,
                        e.message ?: (e::class.simpleName ?: "?"),
                    ),
                )
            } finally {
                isCreating = false
            }
        }
    }

    // Runs after the user confirmed the kill dialog. Killed is killed - there is no undo - so a
    // failure puts the card back and explains why.
    fun killConfirmed(session: RemoteSession) {
        killed.add(session.name)
        scope.launch {
            try {
                val config = settingsStore.load()
                withContext(Dispatchers.IO) { killRemoteSession(config, session.name) }
            } catch (e: Exception) {
                killed.remove(session.name)
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.sessions_kill_failed,
                        session.displayLabel(),
                        e.message ?: (e::class.simpleName ?: "?"),
                    ),
                )
            }
        }
    }

    pendingKill?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingKill = null },
            title = { Text(stringResource(R.string.sessions_kill_dialog_title, target.displayLabel())) },
            text = { Text(stringResource(R.string.sessions_kill_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingKill = null
                        killConfirmed(target)
                    },
                ) {
                    Text(
                        stringResource(R.string.sessions_kill_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKill = null }) {
                    Text(stringResource(R.string.sessions_kill_cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF006B5B).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = size.width * 0.9f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF56DBBC).copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.6f),
                        radius = size.width * 0.7f
                    )
                )
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sessions_title)) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.sessions_open_settings),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { createSession() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.sessions_new),
                        )
                    }
                }
            },
        ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = snapshot) {
                is SessionsSnapshot.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SessionsSnapshot.Failed -> ErrorState(
                    message = s.message,
                    onRetry = { refresh() },
                    onOpenSettings = onOpenSettings,
                )
                is SessionsSnapshot.Live -> {
                    val visible = s.sessions.filterNot { it.name in killed }
                    val labels = visible.displayLabels()
                    if (visible.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items = visible, key = { it.name }) { session ->
                                // Swiping only *requests* the kill: the box always snaps back
                                // (confirmValueChange returns false) and a confirm dialog does
                                // the actual work, so a stray swipe can't end a session.
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value != SwipeToDismissBoxValue.Settled) {
                                            pendingKill = session
                                        }
                                        false
                                    },
                                )
                                val label = labels[session.name] ?: session.displayLabel()
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = { KillBackground(dismissState) },
                                ) {
                                    SessionCard(
                                        session = session,
                                        label = label,
                                        onClick = { onOpenSession(session.name, label) },
                                    )
                                }
                            }
                            item {
                                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Full-screen error with the cause, a Retry action, and a shortcut to Settings (the usual fix
 * for auth/host problems). Scrollable so pull-to-refresh keeps working on top of it. */
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
            Text(
                stringResource(R.string.sessions_error_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.sessions_open_settings))
                }
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.sessions_retry))
                }
            }
        }
    }
}

/** Empty list is a normal state: sessions are born on the PC (`tsess <name>`), the phone only
 * mirrors them. Explains that, with the command in monospace. Scrollable so pull-to-refresh
 * keeps working. */
@Composable
private fun EmptyState() {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Text(
                stringResource(R.string.sessions_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.sessions_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    stringResource(R.string.sessions_empty_command),
                    style = MonoBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Text(
                stringResource(R.string.sessions_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Red "end session" backdrop, revealed only while a [SessionCard] is actually being swiped.
 *
 * It renders nothing at rest on purpose. The card in front is translucent, so a backdrop drawn
 * while settled shows through it as a permanent trash icon floating in the middle of every card —
 * which reads as a delete button, is not one, and does nothing when tapped.
 */
@Composable
private fun KillBackground(dismissState: SwipeToDismissBoxState) {
    val swiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled ||
        dismissState.dismissDirection != SwipeToDismissBoxValue.Settled
    if (!swiping) return

    val color by animateColorAsState(
        MaterialTheme.colorScheme.errorContainer,
        label = "swipeToKillBackground",
    )
    val alignment = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.sessions_kill_action),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** Compact, information-first card: label + focus chip, running command, cwd in monospace, and a
 * trailing chevron as the attach affordance (whole card is tappable). */
@Composable
private fun SessionCard(session: RemoteSession, label: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            // Opaque enough to be a surface. At 15% the card was a tint over the page gradient:
            // text sat on a moving background, and the swipe-to-kill backdrop behind it showed
            // straight through.
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        ),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
                )
            )
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusChip(session, modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    session.command,
                    style = MonoBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    session.cwd,
                    style = MonoBody,
                    // Was `outline`, the lowest-contrast role in the scheme - the path was the
                    // one thing that actually identifies a session and it was the hardest to read.
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.sessions_attach),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Small tinted chip telling at a glance who is looking at the session right now. */
@Composable
private fun StatusChip(session: RemoteSession, modifier: Modifier = Modifier) {
    val (label, container, content) = when (session.owner) {
        "android" -> Triple(
            stringResource(R.string.sessions_status_phone),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        "pc" -> Triple(
            stringResource(R.string.sessions_status_pc),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            stringResource(R.string.sessions_status_idle),
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(shape = RoundedCornerShape(50), color = container, modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
