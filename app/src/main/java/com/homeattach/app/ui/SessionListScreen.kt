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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.homeattach.app.R
import com.homeattach.app.data.HostConfig
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.RemoteSession
import com.homeattach.app.ssh.SshAuthException
import com.homeattach.app.ssh.SshConnectException
import com.homeattach.app.ssh.fetchRemoteSessions
import com.homeattach.app.ssh.killRemoteSession
import com.homeattach.app.ssh.openSshSession
import com.homeattach.app.ssh.watchRemoteSessions
import com.homeattach.app.ui.theme.MonoBody
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private sealed interface SessionsUiState {
    data object Loading : SessionsUiState
    data class Success(val sessions: List<RemoteSession>) : SessionsUiState
    data class Error(val message: String) : SessionsUiState
}

/** Poll cadence for the "live" session list while this screen is on screen and resumed. */
private const val POLL_INTERVAL_MS = 5000L
private const val WATCH_RETRY_DELAY_MS = 2000L

/**
 * What the user sees as the session's title: the last path component of its live cwd (matching
 * how yakuake titles a tab by where it is, not by an internal id). The session [RemoteSession.name]
 * is an auto-generated identity (tsess-auto) and stays internal. Falls back to the name when the
 * host couldn't resolve a cwd.
 */
internal fun RemoteSession.displayLabel(): String {
    val trimmed = cwd.trimEnd('/')
    if (trimmed.isBlank() || trimmed == "?") return name
    val base = trimmed.substringAfterLast('/')
    return base.ifBlank { trimmed }
}

/** Mutable holder so the composition can hang on to one JSch [Session] across polls without a
 * `mutableStateOf` triggering a recomposition on every reconnect. */
private class SshSessionHolder {
    var session: Session? = null
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
    var state by remember { mutableStateOf<SessionsUiState>(SessionsUiState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pendingKill by remember { mutableStateOf<RemoteSession?>(null) }
    val sessionHolder = remember { SshSessionHolder() }
    val fetchMutex = remember { Mutex() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Returns the held SSH session if it's still alive, otherwise (re)connects - shared by both
    // the list poll and the kill action so they reuse one connection instead of a fresh
    // handshake per call. Caller must hold [fetchMutex].
    fun currentSession(config: HostConfig): Session {
        val existing = sessionHolder.session
        return if (existing != null && existing.isConnected) {
            existing
        } else {
            openSshSession(config).also { sessionHolder.session = it }
        }
    }

    suspend fun loadSessions(config: HostConfig): List<RemoteSession> = fetchMutex.withLock {
        withContext(Dispatchers.IO) {
            val session = currentSession(config)
            try {
                fetchRemoteSessions(session)
            } catch (e: Exception) {
                // The held session may have gone stale (host reboot, idle timeout, etc.) -
                // reconnect once and retry before giving up.
                runCatching { session.disconnect() }
                val fresh = openSshSession(config)
                sessionHolder.session = fresh
                fetchRemoteSessions(fresh)
            }
        }
    }

    // showSpinner = true for user-initiated (manual / initial) loads, which drive the
    // pull-to-refresh indicator. A failure never blanks an already-loaded list: manual
    // refreshes surface the cause in a snackbar instead, and silent background polls just keep
    // showing the last good list. The full-screen error state only appears when there is no
    // data to show at all (first load / retry after an error).
    suspend fun poll(showSpinner: Boolean) {
        if (showSpinner) isRefreshing = true
        val config = settingsStore.load()
        val result: SessionsUiState = try {
            SessionsUiState.Success(loadSessions(config))
        } catch (e: SshAuthException) {
            SessionsUiState.Error(e.message ?: context.getString(R.string.sessions_error_auth_fallback))
        } catch (e: SshConnectException) {
            SessionsUiState.Error(e.message ?: context.getString(R.string.sessions_error_connect_fallback))
        } catch (e: Exception) {
            SessionsUiState.Error(
                e.message ?: context.getString(R.string.sessions_error_unexpected, e::class.simpleName ?: "?"),
            )
        }
        if (showSpinner) isRefreshing = false
        val previous = state
        if (result is SessionsUiState.Error && previous is SessionsUiState.Success) {
            if (showSpinner) {
                // Fire-and-forget so a lingering snackbar can't stall the poll loop.
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.sessions_refresh_failed, result.message),
                    )
                }
            }
        } else {
            state = result
        }
    }

    fun refresh() {
        scope.launch { poll(showSpinner = true) }
    }

    // Runs after the user has confirmed the kill dialog: removes the card optimistically, then
    // ends the session on the host. Killed is killed - there is no undo - so a failure restores
    // the card and explains why in a snackbar; on success the card just stays gone (the next
    // poll tick also confirms it against the host's live list).
    fun killConfirmed(session: RemoteSession) {
        val beforeKill = state
        if (beforeKill is SessionsUiState.Success) {
            state = beforeKill.copy(sessions = beforeKill.sessions.filterNot { it.name == session.name })
        }
        scope.launch {
            try {
                val config = settingsStore.load()
                withContext(Dispatchers.IO) {
                    fetchMutex.withLock {
                        val sshSession = currentSession(config)
                        try {
                            killRemoteSession(sshSession, session.name)
                        } catch (e: Exception) {
                            // Held session may be stale - reconnect once and retry.
                            runCatching { sshSession.disconnect() }
                            val fresh = openSshSession(config)
                            sessionHolder.session = fresh
                            killRemoteSession(fresh, session.name)
                        }
                    }
                }
            } catch (e: Exception) {
                val afterFailure = state
                if (afterFailure is SessionsUiState.Success &&
                    afterFailure.sessions.none { it.name == session.name }
                ) {
                    state = afterFailure.copy(sessions = afterFailure.sessions + session)
                }
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

    LaunchedEffect(Unit) { poll(showSpinner = true) }

    // Live updates: a dedicated SSH connection runs `tsess-watch` on the host, which pushes the
    // full list the instant a session is born or dies (inotify) plus a ~5s heartbeat - so the UI
    // reacts in real time instead of on a poll schedule. Only runs while this screen is
    // resumed/visible (repeatOnLifecycle cancels it otherwise). If the watch script is missing
    // on the host, fall back to classic interval polling for the rest of this screen's life.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var watchSupported = true
            while (isActive) {
                if (watchSupported) {
                    var gotUpdate = false
                    try {
                        val config = settingsStore.load()
                        withContext(Dispatchers.IO) {
                            val watchSession = openSshSession(config)
                            try {
                                watchRemoteSessions(watchSession) { sessions ->
                                    gotUpdate = true
                                    state = SessionsUiState.Success(sessions)
                                }
                            } finally {
                                runCatching { watchSession.disconnect() }
                            }
                        }
                        // Clean EOF (host closed the feed): brief pause, then re-establish.
                    } catch (e: Exception) {
                        if (!gotUpdate) {
                            // Never delivered anything: tsess-watch may not exist on this
                            // host - switch to polling instead of hammering reconnects.
                            watchSupported = false
                        }
                        if (state !is SessionsUiState.Success) {
                            poll(showSpinner = false)
                        }
                    }
                    delay(WATCH_RETRY_DELAY_MS)
                } else {
                    delay(POLL_INTERVAL_MS)
                    poll(showSpinner = false)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sessionHolder.session?.let { runCatching { it.disconnect() } }
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
        ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is SessionsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SessionsUiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { refresh() },
                    onOpenSettings = onOpenSettings,
                )
                is SessionsUiState.Success -> {
                    if (s.sessions.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items = s.sessions, key = { it.name }) { session ->
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
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = { KillBackground(dismissState) },
                                ) {
                                    SessionCard(
                                        session = session,
                                        onClick = { onOpenSession(session.name, session.displayLabel()) },
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

/** Red "end session" backdrop revealed while a [SessionCard] is being swiped away. */
@Composable
private fun KillBackground(dismissState: SwipeToDismissBoxState) {
    val targetColor = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val color by animateColorAsState(targetColor, label = "swipeToKillBackground")
    val alignment = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
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

/** Compact, information-first card: name + focus chip, running command, cwd + pty size in
 * monospace, and a trailing chevron as the attach affordance (whole card is tappable). */
@Composable
private fun SessionCard(session: RemoteSession, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
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
                        session.displayLabel(),
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
                    color = MaterialTheme.colorScheme.outline,
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
