package com.homeattach.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.homeattach.app.R
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.RemoteSession
import com.homeattach.app.ssh.RemoteSessionFeed
import com.homeattach.app.ssh.SessionsSnapshot
import com.homeattach.app.terminal.AttachStatus
import com.homeattach.app.terminal.AttachedTerminal
import com.homeattach.app.terminal.RemoteTerminalSession
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight

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
    // The drawer's session list is the same process-wide feed the list screen collects - one
    // tsess-watch on the host, not one per screen.
    val snapshot by RemoteSessionFeed.sessions(settingsStore).collectAsStateWithLifecycle()
    val sessions = (snapshot as? SessionsSnapshot.Live)?.sessions.orEmpty()
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val leaveRequested = remember(sessionName) { AtomicBoolean(false) }
    val context = LocalContext.current

    // The connection and the emulator live in a process-scoped attachment, not in this
    // composition: leaving the app, or rotating, must cost neither a reconnect nor the scrollback.
    // Re-entering a session that is still attached reuses it as-is. Reconnects are the
    // attachment's own business - this screen only reports what it is doing.
    val attachment = remember(sessionName) {
        AttachedTerminal.open(context, sessionName, sessionLabel, settingsStore.load())
    }
    val remoteTerminalSession = attachment.terminal
    val status by attachment.status.collectAsState()
    val hasOutput by attachment.hasOutput.collectAsState()

    // Live IME composing text (voice dictation stream). A pty can't express rewrites, so the
    // preedit renders locally in an overlay strip; only the finalized text goes down the wire.
    var imePreedit by remember { mutableStateOf("") }

    // The emulator outlives this screen, so every callback pointing back into the view must be
    // dropped on the way out - otherwise the attachment pins a dead TerminalView, and the
    // Activity behind it, for as long as it stays attached.
    DisposableEffect(attachment) {
        onDispose { attachment.terminal.onScreenUpdated = {} }
    }

    // Immersive fullscreen for the entire life of this screen, entered BEFORE the first layout.
    // Hiding on entry (instead of on Connected) means the first measured grid is already the
    // final one, so nothing resizes when the connection lands seconds later. The layout below
    // must never consume system-bar insets: the bars transiently overlay the terminal (IME or
    // swipe can bring them back for a few seconds), and consuming their inset would resize the
    // grid on every show/hide flap — each flap costs a local reflow plus a remote SIGWINCH
    // full repaint.
    DisposableEffect(Unit) {
        val window = context.findHostActivity()?.window
        if (window != null) {
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

    // Detach for good: the only exit from this screen. Back, the session ending on the PC, and a
    // dead-end auth failure all land here, so the attachment and its foreground service can never
    // outlive the screen that owns them.
    fun leave() {
        if (!leaveRequested.compareAndSet(false, true)) return
        AttachedTerminal.close(context)
        onBack()
    }

    // Keep the screen awake while a terminal is showing.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        rootView.keepScreenOn = true
        onDispose { rootView.keepScreenOn = false }
    }

    // The session died on the PC (its tab was closed, or someone killed it). Nothing to reconnect
    // to - say so and get out.
    LaunchedEffect(status) {
        if (status is AttachStatus.Ended) {
            Toast.makeText(
                context.applicationContext,
                R.string.terminal_session_ended_on_pc,
                Toast.LENGTH_SHORT,
            ).show()
            leave()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Coming back to the app is the one moment the user is actually looking, and the radio is
    // almost always up by then - skip whatever backoff the loop is sitting in.
    LaunchedEffect(lifecycleOwner, attachment) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            attachment.retryNow()
        }
    }
    val composeDensity = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    val imeHeight = WindowInsets.ime.getBottom(composeDensity)
    var maxImeHeight by remember { mutableIntStateOf(settingsStore.loadMaxImeHeight(0)) }

    LaunchedEffect(imeHeight, imeVisible) {
        if (imeVisible && imeHeight > 0) {
            if (imeHeight > maxImeHeight) {
                maxImeHeight = imeHeight
                settingsStore.saveMaxImeHeight(imeHeight)
            } else if (imeHeight < maxImeHeight) {
                delay(150L)
                maxImeHeight = imeHeight
                settingsStore.saveMaxImeHeight(imeHeight)
            }
        }
    }

    val bottomPaddingPx = if (imeVisible) maxImeHeight else 0
    val bottomPaddingDp = with(composeDensity) { bottomPaddingPx.toDp() }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Always enabled: leaving must always route through leave(), or Back would pop the screen
    // while the attachment and its foreground service stayed behind with no UI.
    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            leave()
        }
    }

    // Grab focus on entry so the first tap raises the keyboard, but do NOT auto-pop the IME:
    // showing it right after entry resizes the terminal (the entry flicker). Tap to type.
    LaunchedEffect(lifecycleOwner, status, terminalView) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (status is AttachStatus.Connected) {
                terminalView?.requestFocus()
            }
        }
    }

    // IME show/hide is expressed as a discrete bottom padding jump (stored keyboard height,
    // flipped by imeVisible) instead of the animated imePadding() modifier: the terminal
    // resizes exactly once per keyboard toggle, not on every animation frame.

    LaunchedEffect(drawerState.currentValue, drawerState.targetValue, terminalView) {
        if (drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed
        ) {
            terminalView?.hideSoftKeyboard()
        }
    }

    // Terminal font size (pixels) is persisted so pinch-zoom sticks across sessions.
    val density = context.resources.displayMetrics.density
    val defaultFontPx = (TERMINAL_TEXT_SIZE_DP * density).toInt()
    val minFontPx = (9 * density).toInt()
    val maxFontPx = (32 * density).toInt()
    var terminalFontPx by remember { mutableStateOf(settingsStore.loadTerminalFontPx(defaultFontPx)) }
    // Bundled CJK monospace (Sarasa Fixed SC): guarantees fixed-width Chinese alignment.
    val terminalTypeface = remember {
        runCatching {
            android.graphics.Typeface.createFromAsset(context.assets, "fonts/sarasa_fixed_sc_regular.ttf")
        }.getOrDefault(android.graphics.Typeface.MONOSPACE)
    }
    val terminalViewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                // Pinch-zoom: adjust font size, clamp, apply, persist. Return 1f to reset the
                // recognizer's accumulator since we track the size ourselves.
                val newPx = (terminalFontPx * scale).roundToInt().coerceIn(minFontPx, maxFontPx)
                if (newPx != terminalFontPx) {
                    terminalFontPx = newPx
                    terminalView?.setTextSize(newPx)
                    settingsStore.saveTerminalFontPx(newPx)
                }
                return 1.0f
            }
            override fun onSingleTapUp(e: MotionEvent?) {
                // The load-bearing fix: a tap on the terminal raises the soft keyboard.
                terminalView?.requestFocus()
                terminalView?.showSoftKeyboard()
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false // Compose BackHandler owns back
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            // Return false so the view's default path writes input to the session → SSH.
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            // Debug builds surface the engine's IME traffic (commitText/composing calls) in
            // logcat — the only way to see what a voice keyboard actually sends. Release: silent.
            override fun logError(tag: String?, message: String?) {
                if (com.homeattach.app.BuildConfig.DEBUG) android.util.Log.e(tag ?: "TerminalView", message ?: "")
            }
            override fun logWarn(tag: String?, message: String?) {
                if (com.homeattach.app.BuildConfig.DEBUG) android.util.Log.w(tag ?: "TerminalView", message ?: "")
            }
            override fun logInfo(tag: String?, message: String?) {
                if (com.homeattach.app.BuildConfig.DEBUG) android.util.Log.i(tag ?: "TerminalView", message ?: "")
            }
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Closed: gestures off so the terminal keeps its horizontal swipes (the left-edge zone below
        // opens the drawer). Open: gestures on so a scrim tap or drag closes it — the terminal is
        // behind the scrim and gets no touches anyway.
        gesturesEnabled = drawerState.isOpen,
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
            containerColor = Color(0xFF0A0B10),
            // Zero insets: transient system bars overlay the terminal instead of resizing it.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF0A0B10)),
            ) {
                // 1. The terminal is always laid out, never gated on connection state: it has to
                //    measure itself before the attachment can ask the host for a pty that size,
                //    and on a reconnect it already holds the content the user wants to keep
                //    looking at.
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // The terminal simply fills whatever the Column leaves it: full height
                    // normally, full minus the keys row's discrete IME padding when the
                    // keyboard is up. One layout pass per IME toggle — no per-frame
                    // animation resize, no manual height math to double-count.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0A0B10)),
                            factory = { ctx ->
                                TerminalView(ctx, null).also { view ->
                                    view.setTerminalViewClient(terminalViewClient)
                                    view.setIsTerminalViewKeyLoggingEnabled(com.homeattach.app.BuildConfig.DEBUG)
                                    view.setImePreeditListener { preedit -> imePreedit = preedit }
                                    view.attachSession(remoteTerminalSession.session)
                                    // Termux setTextSize is pixels; set before first layout so
                                    // font metrics are non-zero and cols/rows compute correctly.
                                    view.setTextSize(terminalFontPx)
                                    view.setTypeface(terminalTypeface)
                                    view.setBackgroundColor(0xFF0A0B10.toInt())
                                    // Termux's TerminalView (unlike jackpal) does not make
                                    // itself focusable; without this, requestFocus() is a
                                    // no-op, the IME never binds to it, and input is lost.
                                    view.isFocusable = true
                                    view.isFocusableInTouchMode = true
                                    view.setOnFocusChangeListener { _, hasFocus ->
                                        if (hasFocus) attachment.claimFocus()
                                    }
                                    remoteTerminalSession.onScreenUpdated = { view.onScreenUpdated() }
                                    terminalView = view
                                }
                            },
                            update = { view ->
                                view.attachSession(remoteTerminalSession.session)
                                remoteTerminalSession.onScreenUpdated = { view.onScreenUpdated() }
                            },
                        )
                        // Left-edge swipe zone: only this thin strip opens the drawer, so the
                        // terminal body keeps all its horizontal touches (selection, scroll).
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(20.dp)
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        if (dragAmount > 8f) scope.launch { drawerState.open() }
                                    }
                                },
                        )
                        // Voice dictation preview: the composing text streams here live
                        // (revisions included); the terminal gets one clean write on finalize.
                        if (imePreedit.isNotEmpty()) {
                            Text(
                                text = imePreedit.takeLast(120),
                                color = Color(0xFFE8F0FE),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(Color(0xE61A2233))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = bottomPaddingDp)
                    ) {
                        ExtraKeysRow(remoteTerminalSession = remoteTerminalSession)
                    }
                }

                // 2. Blocking spinner only until the terminal has drawn something. Keyed off
                //    hasOutput, not the connection state, so a later drop repaints a banner over
                //    a live screen instead of hiding the content behind a spinner again.
                val current = status
                if (!hasOutput && current !is AttachStatus.Failed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0B10)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF00E676))
                            Text(
                                stringResource(R.string.terminal_connecting_to, sessionLabel),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            // A host that is simply unreachable would otherwise be an endless
                            // anonymous spinner; say what is failing while it keeps trying.
                            if (current is AttachStatus.Reconnecting) {
                                Text(
                                    stringResource(
                                        R.string.terminal_reconnecting_banner,
                                        current.attempt,
                                        current.message,
                                    ),
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                                )
                            }
                        }
                    }
                }

                // 3. Reconnecting over a terminal that already has content: a strip, not a
                //    takeover. The loop is retrying on its own, so there is nothing to tap.
                if (hasOutput && current is AttachStatus.Reconnecting) {
                    Surface(
                        color = Color(0xE6B26B00),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.terminal_reconnecting_banner,
                                current.attempt,
                                current.message,
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                // 4. Dead end: the host rejected our key. Retrying cannot fix that, so there is
                //    no Retry button - the fix lives in Settings.
                if (current is AttachStatus.Failed) {
                    ConnectionProblem(
                        title = stringResource(R.string.terminal_auth_failed_title),
                        message = current.message,
                        hint = null,
                        onBack = { leave() },
                    )
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
private fun ConnectionProblem(
    title: String,
    message: String,
    hint: String?,
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

                Button(
                    onClick = onBack,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color(0xFF0A0B10)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.terminal_back),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(
    remoteTerminalSession: RemoteTerminalSession,
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
            label = stringResource(R.string.terminal_key_paste),
            modifier = Modifier.weight(1.1f),
        ) {
            remoteTerminalSession.pasteTextFromClipboard()
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
    // Set once a press has been held long enough to mean key-repeat, so the tap-on-release path
    // stands down and a hold doesn't send one extra key when the finger lifts.
    var repeatTookOver by remember { mutableStateOf(false) }

    if (repeating) {
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            // Repeating keys used to fire the instant a finger touched down, with nothing able to
            // take it back. On the arrows that made a graze while reaching past the row send Up
            // to the shell, which silently pastes the last command into the prompt. Nothing is
            // sent until the press is either released on the cap (a tap) or held (a repeat).
            delay(KEY_REPEAT_INITIAL_DELAY_MS)
            repeatTookOver = true
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
                // detectTapGestures, not a raw down/up: it already cancels a press whose finger
                // slides off the cap. That is the whole point here - a stray brush across the row
                // must send nothing at all, and only a tap that starts and ends on the key counts.
                detectTapGestures(
                    onPress = {
                        pressed = true
                        repeatTookOver = false
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (!repeatTookOver) currentOnPress() },
                )
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
