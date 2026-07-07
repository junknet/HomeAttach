package com.homeattach.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import com.homeattach.app.R
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.RemoteTerminalSize
import com.homeattach.app.ssh.TerminalAttachRequest
import com.homeattach.app.ssh.TerminalConnection
import com.homeattach.app.ssh.RemoteSession
import com.homeattach.app.ssh.watchRemoteSessions
import com.homeattach.app.ssh.fetchRemoteSessions
import com.homeattach.app.ssh.openSshSession
import com.homeattach.app.terminal.RemoteTerminalSession
import jackpal.androidterm.emulatorview.EmulatorView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight

private sealed interface ConnStatus {
    data object Connecting : ConnStatus
    data object Connected : ConnStatus

    /** The initial attach failed before the terminal was ever shown. */
    data class ConnectFailed(val message: String) : ConnStatus

    /** The SSH transport dropped mid-session; the remote session may still be alive. */
    data class ConnectionLost(val message: String) : ConnStatus
}

private const val INITIAL_ANDROID_TERMINAL_COLUMNS = 80
private const val INITIAL_ANDROID_TERMINAL_ROWS = 24

private const val KEY_REPEAT_INITIAL_DELAY_MS = 400L
private const val KEY_REPEAT_INTERVAL_MS = 60L
private const val TERMINAL_SESSION_DRAWER_WIDTH_FRACTION = 0.62f
private const val TERMINAL_TEXT_SIZE_DP = 14
private val TERMINAL_EXTRA_KEYS_ROW_HEIGHT = 48.dp
private val ANSI_ARROW_UP = byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())
private val ANSI_ARROW_DOWN = byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())
private val ANSI_ARROW_RIGHT = byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())
private val ANSI_ARROW_LEFT = byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())

private val STATUS_DOT_CONNECTED = Color(0xFF4CAF50)
private val STATUS_DOT_CONNECTING = Color(0xFFFFB300)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    sessionName: String,
    settingsStore: SettingsStore,
    onBack: () -> Unit,
    onNavigateToSession: (name: String, label: String) -> Unit,
    sessionLabel: String = sessionName,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ConnStatus>(ConnStatus.Connecting) }
    var sessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var connectAttempt by remember(sessionName) { mutableIntStateOf(0) }
    var terminalView by remember { mutableStateOf<EmulatorView?>(null) }
    val connectionReference = remember { AtomicReference<TerminalConnection?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ownerIdentifier = remember { buildAndroidFocusOwnerIdentifier() }
    val backNavigationRequested = remember(sessionName) { AtomicBoolean(false) }
    val context = LocalContext.current
    val remoteTerminalSession = remember(sessionName, connectAttempt) {
        var resizeJob: Job? = null
        RemoteTerminalSession(
            onInput = { bytes -> connectionReference.get()?.send(bytes) },
            onResize = { columns, rows ->
                resizeJob?.cancel()
                resizeJob = scope.launch {
                    delay(300L)
                    connectionReference.get()?.resizePty(columns, rows)
                }
            },
        )
    }

    DisposableEffect(remoteTerminalSession) {
        onDispose {
            remoteTerminalSession.finish()
        }
    }

    DisposableEffect(status) {
        val activity = context.findHostActivity()
        val window = activity?.window
        if (window != null && status is ConnStatus.Connected) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun closeTerminalConnection() {
        connectionReference.getAndSet(null)?.close()
    }

    fun closeConnectionAndNavigateBackOnce() {
        if (!backNavigationRequested.compareAndSet(false, true)) return
        closeTerminalConnection()
        onBack()
    }

    fun reconnect() {
        status = ConnStatus.Connecting
        connectAttempt += 1
    }

    // Keep the screen awake while a terminal is showing.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        rootView.keepScreenOn = true
        onDispose { rootView.keepScreenOn = false }
    }

    DisposableEffect(sessionName, ownerIdentifier, connectAttempt) {
        val disposed = AtomicBoolean(false)
        val appContext = context.applicationContext
        val config = settingsStore.load()
        val attachRequest = TerminalAttachRequest(
            sessionName = sessionName,
            ownerIdentifier = ownerIdentifier,
            terminalSize = RemoteTerminalSize(
                columns = INITIAL_ANDROID_TERMINAL_COLUMNS,
                rows = INITIAL_ANDROID_TERMINAL_ROWS,
            ),
        )
        val reader = thread(name = "ssh-terminal-reader", isDaemon = true) {
            val conn = try {
                TerminalConnection.attach(config, attachRequest)
            } catch (e: Exception) {
                if (!disposed.get()) {
                    mainHandler.post { status = ConnStatus.ConnectFailed(describeError(appContext, e)) }
                }
                return@thread
            }
            if (disposed.get()) {
                conn.close()
                return@thread
            }
            connectionReference.set(conn)
            mainHandler.post {
                status = ConnStatus.Connected
            }
            try {
                val buf = ByteArray(8192)
                while (!conn.closed) {
                    val n = try {
                        conn.input.read(buf)
                    } catch (e: Exception) {
                        if (conn.closed) break else throw e
                    }
                    if (n < 0) break
                    remoteTerminalSession.appendRemoteOutput(buf, 0, n)
                }
                // Clean EOF while we never closed locally: the session ended on the PC
                // (e.g. its yakuake tab was closed). Leave with a brief notice.
                val sessionEndedRemotely = !conn.closed
                connectionReference.compareAndSet(conn, null)
                conn.close()
                if (sessionEndedRemotely && !disposed.get()) {
                    mainHandler.post {
                        if (backNavigationRequested.compareAndSet(false, true)) {
                            Toast.makeText(
                                appContext,
                                R.string.terminal_session_ended_on_pc,
                                Toast.LENGTH_SHORT,
                            ).show()
                            onBack()
                        }
                    }
                }
            } catch (e: Exception) {
                // Transport error mid-session: reconnecting might work, so keep the user
                // on this screen and offer it instead of silently kicking them out.
                connectionReference.compareAndSet(conn, null)
                conn.close()
                if (!disposed.get()) {
                    mainHandler.post { status = ConnStatus.ConnectionLost(describeError(appContext, e)) }
                }
            }
        }

        onDispose {
            disposed.set(true)
            closeTerminalConnection()
            reader.interrupt()
        }
    }

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
                                watchRemoteSessions(watchSession) { list ->
                                    gotUpdate = true
                                    sessions = list
                                }
                            } finally {
                                runCatching { watchSession.disconnect() }
                            }
                        }
                    } catch (e: Exception) {
                        if (!gotUpdate) {
                            watchSupported = false
                        }
                        if (!watchSupported) {
                            try {
                                val config = settingsStore.load()
                                val list = withContext(Dispatchers.IO) {
                                    val connSession = openSshSession(config)
                                    try {
                                        fetchRemoteSessions(connSession)
                                    } finally {
                                        runCatching { connSession.disconnect() }
                                    }
                                }
                                sessions = list
                            } catch (_: Exception) {}
                        }
                    }
                    delay(5000L)
                } else {
                    delay(5000L)
                    try {
                        val config = settingsStore.load()
                        val list = withContext(Dispatchers.IO) {
                            val connSession = openSshSession(config)
                            try {
                                fetchRemoteSessions(connSession)
                            } finally {
                                runCatching { connSession.disconnect() }
                            }
                        }
                        sessions = list
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val imeVisible = WindowInsets.isImeVisible
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    BackHandler(enabled = (status is ConnStatus.Connected) || drawerState.isOpen) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            closeConnectionAndNavigateBackOnce()
        }
    }

    LaunchedEffect(lifecycleOwner, status, terminalView) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (status is ConnStatus.Connected && terminalView != null) {
                terminalView?.requestFocus()
                terminalView?.showSoftKeyboard()
            }
        }
    }

    LaunchedEffect(imeVisible, terminalView) {
        terminalView?.let { view ->
            view.requestLayout()
            view.updateSize(true)
        }
    }

    LaunchedEffect(drawerState.currentValue, drawerState.targetValue, terminalView) {
        if (drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed
        ) {
            terminalView?.hideSoftKeyboard()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(TERMINAL_SESSION_DRAWER_WIDTH_FRACTION)
                    .widthIn(max = 280.dp),
                drawerShape = RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sessions_drawer_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        items(items = sessions, key = { it.name }) { session ->
                            val isCurrent = session.name == sessionName
                            TerminalSessionDrawerItem(
                                session = session,
                                selected = isCurrent,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    if (!isCurrent) {
                                        onNavigateToSession(session.name, session.displayLabel())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (status !is ConnStatus.Connected) {
                    TopAppBar(
                        title = { Text(sessionLabel) },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    closeConnectionAndNavigateBackOnce()
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.terminal_back),
                                )
                            }
                        },
                        actions = {
                            ConnectionStateIndicator(status = status, reconnecting = connectAttempt > 0)
                            val toggleKeyboardLabel = stringResource(R.string.terminal_toggle_keyboard)
                            IconButton(
                                onClick = {
                                    terminalView?.let { view ->
                                        if (imeVisible) {
                                            view.hideSoftKeyboard()
                                        } else {
                                            view.requestFocus()
                                            view.showSoftKeyboard()
                                        }
                                    }
                                },
                                enabled = status is ConnStatus.Connected,
                            ) {
                                Text(
                                    text = "⌨",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { contentDescription = toggleKeyboardLabel },
                                )
                            }
                        },
                    )
                }
            },
            containerColor = Color(0xFF0A0B10)
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .background(Color(0xFF0A0B10)),
            ) {
                when (val s = status) {
                    is ConnStatus.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF00E676))
                            Text(
                                stringResource(R.string.terminal_connecting_to, sessionName),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                    is ConnStatus.ConnectFailed -> ConnectionProblem(
                        title = stringResource(R.string.terminal_connection_failed_title),
                        message = s.message,
                        hint = null,
                        retryLabel = stringResource(R.string.terminal_retry),
                        onRetry = { reconnect() },
                        onBack = { closeConnectionAndNavigateBackOnce() },
                    )
                    is ConnStatus.ConnectionLost -> ConnectionProblem(
                        title = stringResource(R.string.terminal_connection_lost_title),
                        message = s.message,
                        hint = stringResource(R.string.terminal_connection_lost_hint),
                        retryLabel = stringResource(R.string.terminal_reconnect),
                        onRetry = { reconnect() },
                        onBack = { closeConnectionAndNavigateBackOnce() },
                    )
                    is ConnStatus.Connected -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                                    factory = { ctx ->
                                        EmulatorView(ctx, null).also { view ->
                                            view.setDensity(ctx.resources.displayMetrics)
                                            view.setUseCookedIME(false)
                                            view.attachSession(remoteTerminalSession)
                                            view.setTermType("xterm-256color")
                                            view.setTextSize(TERMINAL_TEXT_SIZE_DP)
                                            view.setOnFocusChangeListener { _, hasFocus ->
                                                if (hasFocus &&
                                                    remoteTerminalSession.currentColumns > 0 &&
                                                    remoteTerminalSession.currentRows > 0
                                                ) {
                                                    connectionReference.get()?.focusPty(
                                                        remoteTerminalSession.currentColumns,
                                                        remoteTerminalSession.currentRows,
                                                    )
                                                }
                                            }
                                            terminalView = view
                                        }
                                    },
                                    update = {},
                                )
                            }
                            ExtraKeysRow(
                                remoteTerminalSession = remoteTerminalSession,
                                onMenuClick = { scope.launch { drawerState.open() } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSessionDrawerItem(
    session: RemoteSession,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    } else {
        Color.Transparent
    }
    val dotColor = if (session.status == "focused") {
        STATUS_DOT_CONNECTED
    } else {
        MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f),
        ) {
            Text(
                text = session.displayLabel(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.command.isNotBlank()) {
                Text(
                    text = session.command,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun describeError(context: Context, e: Exception): String =
    e.message ?: e::class.simpleName ?: context.getString(R.string.terminal_unknown_error)

private fun buildAndroidFocusOwnerIdentifier(): String {
    val manufacturer = Build.MANUFACTURER.ifBlank { "android" }
    val model = Build.MODEL.ifBlank { "device" }
    return "android:$manufacturer-$model"
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private fun View.showSoftKeyboard() {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    inputMethodManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

private fun View.hideSoftKeyboard() {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
}

@Composable
private fun ConnectionStateIndicator(status: ConnStatus, reconnecting: Boolean) {
    val (color, label) = when (status) {
        is ConnStatus.Connected ->
            STATUS_DOT_CONNECTED to stringResource(R.string.terminal_status_connected)
        is ConnStatus.Connecting ->
            STATUS_DOT_CONNECTING to stringResource(
                if (reconnecting) R.string.terminal_status_reconnecting else R.string.terminal_status_connecting,
            )
        is ConnStatus.ConnectFailed, is ConnStatus.ConnectionLost ->
            MaterialTheme.colorScheme.error to stringResource(R.string.terminal_status_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ConnectionProblem(
    title: String,
    message: String,
    hint: String?,
    retryLabel: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B10))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF1E202A),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                if (hint != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.85f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.terminal_back))
                    }
                    Button(
                        onClick = onRetry,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E676),
                            contentColor = Color(0xFF0A0B10)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = retryLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(
    remoteTerminalSession: RemoteTerminalSession,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TERMINAL_EXTRA_KEYS_ROW_HEIGHT)
            .background(Color.Black)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap(
            label = "☰",
            modifier = Modifier.weight(0.72f),
        ) {
            onMenuClick()
        }
        KeyCap(
            label = stringResource(R.string.terminal_key_esc),
            modifier = Modifier.weight(0.9f),
        ) {
            remoteTerminalSession.write(byteArrayOf(0x1b), 0, 1)
        }
        KeyCap(
            label = "Ctrl+C",
            modifier = Modifier.weight(1.2f),
        ) {
            remoteTerminalSession.write(byteArrayOf(0x03), 0, 1)
        }
        KeyCap(
            label = "Ctrl+D",
            modifier = Modifier.weight(1.2f),
        ) {
            remoteTerminalSession.write(byteArrayOf(0x04), 0, 1)
        }
        KeyCap(
            label = "←",
            modifier = Modifier.weight(0.72f),
            repeating = true
        ) {
            remoteTerminalSession.write(ANSI_ARROW_LEFT, 0, ANSI_ARROW_LEFT.size)
        }
        KeyCap(
            label = "↑",
            modifier = Modifier.weight(0.72f),
            repeating = true
        ) {
            remoteTerminalSession.write(ANSI_ARROW_UP, 0, ANSI_ARROW_UP.size)
        }
        KeyCap(
            label = "↓",
            modifier = Modifier.weight(0.72f),
            repeating = true
        ) {
            remoteTerminalSession.write(ANSI_ARROW_DOWN, 0, ANSI_ARROW_DOWN.size)
        }
        KeyCap(
            label = "→",
            modifier = Modifier.weight(0.72f),
            repeating = true
        ) {
            remoteTerminalSession.write(ANSI_ARROW_RIGHT, 0, ANSI_ARROW_RIGHT.size)
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier,
    repeating: Boolean = false,
    onPress: () -> Unit,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }

    if (repeating) {
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            currentOnPress()
            delay(KEY_REPEAT_INITIAL_DELAY_MS)
            while (true) {
                currentOnPress()
                delay(KEY_REPEAT_INTERVAL_MS)
            }
        }
    }

    val buttonColor = if (pressed) Color(0xFF252A33) else Color(0xFF10131A)
    val borderColor = if (pressed) Color(0xFF6EA8FE) else Color.White.copy(alpha = 0.14f)
    val foreground = if (pressed) Color.White else Color(0xFFF2F5FA)

    Surface(
        color = buttonColor,
        contentColor = foreground,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(0.7.dp, borderColor),
        modifier = modifier
            .height(40.dp)
            .pointerInput(repeating) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    // Non-repeating keys fire on a completed tap; repeating keys already
                    // fired on touch-down via the LaunchedEffect above.
                    if (!repeating && up != null) currentOnPress()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp
                ),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
