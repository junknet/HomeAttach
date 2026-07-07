package com.homeattach.app.ui

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.homeattach.app.BuildConfig
import com.homeattach.app.R
import com.homeattach.app.data.HostConfig
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.fetchRemoteSessions
import com.homeattach.app.ssh.normalizePrivateKeyPem
import com.homeattach.app.update.AppUpdateCheckResult
import com.homeattach.app.update.AvailableAppUpdate
import com.homeattach.app.update.GithubReleaseUpdater
import com.homeattach.app.update.InstallLaunchResult
import com.homeattach.app.ui.theme.MonoBody
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run / settings screen: host, port, username and the SSH private key PEM. Everything is
 * persisted through [SettingsStore] (EncryptedSharedPreferences) - never in plain prefs.
 *
 * Field-level validation runs live for malformed input (bracketed IPv6, bad port) and only
 * flags empty required fields after the user tries to save or test, so a fresh screen is not
 * a wall of red. "Test connection" reuses the existing read-only `tsess-list` fetch from
 * SshClient on a background dispatcher and reports the result inline.
 *
 * In debug builds only, if a file named `dev_key_prefill.pem` exists in the app's external files
 * dir, its contents are used to prefill the key field so a developer testing against an emulator
 * does not have to hand-type a PEM blob. Nothing here embeds any real key material in source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onSaved: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val initial = remember { settingsStore.load() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val updater = remember(context) { GithubReleaseUpdater(context.applicationContext) }

    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "22") }
    var username by remember { mutableStateOf(initial.username) }
    var keyPem by remember { mutableStateOf(initial.privateKeyPem) }

    // Key material is masked by default when one is already stored; a freshly pasted key
    // stays visible so the user can eyeball what they pasted.
    var keyVisible by remember { mutableStateOf(initial.privateKeyPem.isBlank()) }
    // Set on the first failed save/test attempt; from then on empty required fields go red.
    var showEmptyErrors by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestConnectionState>(TestConnectionState.Idle) }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG && keyPem.isBlank()) {
            val prefillFile = File(context.getExternalFilesDir(null), "dev_key_prefill.pem")
            if (prefillFile.exists()) {
                keyPem = prefillFile.readText()
            }
        }
    }

    val trimmedHost = host.trim()
    val hostHasBrackets = '[' in trimmedHost || ']' in trimmedHost
    // Two or more colons can only be an IPv6 literal (host:port is not valid input here).
    val hostLooksIpv6 = trimmedHost.count { it == ':' } >= 2
    val hostError = when {
        trimmedHost.isEmpty() -> if (showEmptyErrors) stringResource(R.string.settings_host_error_required) else null
        hostHasBrackets -> stringResource(R.string.settings_host_error_brackets)
        else -> null
    }

    val portValue = port.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val portError = if (portValid) null else stringResource(R.string.settings_port_error_range)

    val usernameError = if (username.isBlank() && showEmptyErrors) {
        stringResource(R.string.settings_username_error_required)
    } else {
        null
    }

    val normalizedPrivateKeyPem = remember(keyPem) {
        runCatching { normalizePrivateKeyPem(keyPem) }
    }
    val keyError = when {
        keyPem.isBlank() && showEmptyErrors -> stringResource(R.string.settings_key_error_required)
        keyPem.isNotBlank() && normalizedPrivateKeyPem.isFailure -> {
            normalizedPrivateKeyPem.exceptionOrNull()?.message
                ?: stringResource(R.string.settings_key_error_invalid)
        }
        else -> null
    }
    val keyTypeLabel = remember(keyPem, normalizedPrivateKeyPem) {
        normalizedPrivateKeyPem.getOrNull()?.let(::detectPrivateKeyType)
    }

    val formValid = trimmedHost.isNotEmpty() && !hostHasBrackets && portValid &&
        username.isNotBlank() && keyPem.isNotBlank() && normalizedPrivateKeyPem.isSuccess

    fun currentConfig() = HostConfig(
        host = trimmedHost,
        port = portValue ?: 22,
        username = username.trim(),
        privateKeyPem = normalizedPrivateKeyPem.getOrThrow(),
    )

    // Any edit invalidates a previous test verdict.
    fun onFieldEdited() {
        testState = TestConnectionState.Idle
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // First run has nowhere to go back to; the screen is then the app's root.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.settings_section_server))

            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    onFieldEdited()
                },
                label = { Text(stringResource(R.string.settings_host_label)) },
                singleLine = true,
                isError = hostError != null,
                supportingText = when {
                    hostError != null -> ({ Text(hostError) })
                    hostLooksIpv6 -> ({ Text(stringResource(R.string.settings_host_hint_ipv6)) })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it.filter(Char::isDigit)
                    onFieldEdited()
                },
                label = { Text(stringResource(R.string.settings_port_label)) },
                singleLine = true,
                isError = portError != null,
                supportingText = portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    onFieldEdited()
                },
                label = { Text(stringResource(R.string.settings_username_label)) },
                singleLine = true,
                isError = usernameError != null,
                supportingText = usernameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.settings_section_auth))

            OutlinedTextField(
                value = keyPem,
                onValueChange = {
                    keyPem = it
                    keyVisible = true
                    onFieldEdited()
                },
                label = { Text(stringResource(R.string.settings_key_label)) },
                isError = keyError != null,
                supportingText = {
                    Text(
                        keyError
                            ?: keyTypeLabel?.let { stringResource(R.string.settings_key_detected, it) }
                            ?: stringResource(R.string.settings_key_hint_format),
                    )
                },
                textStyle = if (keyVisible) MonoBody else LocalTextStyle.current,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = {
                    clipboard.getText()?.text?.let {
                        keyPem = runCatching { normalizePrivateKeyPem(it) }.getOrElse { _ -> it }
                        keyVisible = true
                        onFieldEdited()
                    }
                }) {
                    Text(stringResource(R.string.settings_key_paste))
                }
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(
                        stringResource(
                            if (keyVisible) R.string.settings_key_hide else R.string.settings_key_show,
                        ),
                    )
                }
                TextButton(
                    onClick = {
                        keyPem = ""
                        keyVisible = true
                        onFieldEdited()
                    },
                    enabled = keyPem.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.settings_key_clear))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        if (!formValid) {
                            showEmptyErrors = true
                        } else {
                            val config = currentConfig()
                            testState = TestConnectionState.Running
                            scope.launch {
                                testState = withContext(Dispatchers.IO) {
                                    try {
                                        // Read-only probe: same tsess-list fetch the session
                                        // list screen uses; connects, lists, disconnects.
                                        TestConnectionState.Success(fetchRemoteSessions(config).size)
                                    } catch (e: Exception) {
                                        TestConnectionState.Failure(
                                            e.message ?: e.javaClass.simpleName,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    enabled = testState != TestConnectionState.Running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_test_connection))
                }
                Button(
                    onClick = {
                        if (formValid) {
                            settingsStore.save(currentConfig())
                            onSaved()
                        } else {
                            showEmptyErrors = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            when (val state = testState) {
                TestConnectionState.Idle -> Unit
                TestConnectionState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.settings_testing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is TestConnectionState.Success -> Text(
                    stringResource(R.string.settings_test_success, state.sessionCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                is TestConnectionState.Failure -> Text(
                    stringResource(R.string.settings_test_failure, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (showEmptyErrors && !formValid) {
                Text(
                    stringResource(R.string.settings_fix_fields),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionHeader(stringResource(R.string.settings_section_update))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        updateState = UpdateUiState.Checking
                        scope.launch {
                            updateState = withContext(Dispatchers.IO) {
                                try {
                                    when (val result = updater.checkForUpdate()) {
                                        AppUpdateCheckResult.NotConfigured -> UpdateUiState.NotConfigured
                                        is AppUpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(
                                            currentVersion = result.currentVersion,
                                            latestTag = result.latestTag,
                                        )
                                        is AppUpdateCheckResult.Available -> UpdateUiState.Available(result.update)
                                    }
                                } catch (e: Exception) {
                                    UpdateUiState.Failure(e.message ?: e.javaClass.simpleName)
                                }
                            }
                        }
                    },
                    enabled = updateState !is UpdateUiState.Checking &&
                        updateState !is UpdateUiState.Downloading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_update_check))
                }

                Button(
                    onClick = {
                        val available = (updateState as? UpdateUiState.Available)?.update
                            ?: return@Button
                        updateState = UpdateUiState.Downloading(available)
                        scope.launch {
                            val nextState = try {
                                val downloaded = withContext(Dispatchers.IO) {
                                    updater.downloadApk(available)
                                }
                                when (updater.launchInstaller(downloaded)) {
                                    InstallLaunchResult.InstallerOpened -> UpdateUiState.InstallerOpened
                                    InstallLaunchResult.PermissionSettingsOpened -> {
                                        UpdateUiState.PermissionSettingsOpened
                                    }
                                }
                            } catch (e: Exception) {
                                UpdateUiState.Failure(e.message ?: e.javaClass.simpleName)
                            }
                            updateState = nextState
                        }
                    },
                    enabled = updateState is UpdateUiState.Available,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_update_install))
                }
            }

            UpdateStatusText(updateState)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Outcome of the inline "Test connection" probe. */
private sealed interface TestConnectionState {
    data object Idle : TestConnectionState
    data object Running : TestConnectionState
    data class Success(val sessionCount: Int) : TestConnectionState
    data class Failure(val message: String) : TestConnectionState
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NotConfigured : UpdateUiState
    data object InstallerOpened : UpdateUiState
    data object PermissionSettingsOpened : UpdateUiState
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateUiState
    data class Available(val update: AvailableAppUpdate) : UpdateUiState
    data class Downloading(val update: AvailableAppUpdate) : UpdateUiState
    data class Failure(val message: String) : UpdateUiState
}

@Composable
private fun UpdateStatusText(state: UpdateUiState) {
    when (state) {
        UpdateUiState.Idle -> Text(
            stringResource(R.string.settings_update_current_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UpdateUiState.Checking -> InlineProgressText(stringResource(R.string.settings_update_checking))
        is UpdateUiState.Downloading -> InlineProgressText(
            stringResource(R.string.settings_update_downloading, state.update.assetName),
        )
        UpdateUiState.NotConfigured -> Text(
            stringResource(R.string.settings_update_not_configured),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is UpdateUiState.UpToDate -> Text(
            stringResource(R.string.settings_update_latest, state.currentVersion, state.latestTag),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is UpdateUiState.Available -> Text(
            stringResource(
                R.string.settings_update_available,
                state.update.tagName,
                formatBytes(state.update.assetSizeBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        UpdateUiState.InstallerOpened -> Text(
            stringResource(R.string.settings_update_installer_opened),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        UpdateUiState.PermissionSettingsOpened -> Text(
            stringResource(R.string.settings_update_permission_opened),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is UpdateUiState.Failure -> Text(
            stringResource(R.string.settings_update_failure, state.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InlineProgressText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * Cheap, offline private-key format sniffing for the summary line under the key field.
 * For OpenSSH-format keys the inner key type name (ssh-ed25519 / ssh-rsa / ecdsa-*) is a
 * plain ASCII string inside the base64 payload, so one decode is enough - no real key
 * parsing, no crypto. Returns null when the text doesn't look like a known private key.
 */
private fun detectPrivateKeyType(pem: String): String? {
    val text = pem.trim()
    if (text.isEmpty()) return null
    return when {
        "BEGIN OPENSSH PRIVATE KEY" in text -> {
            val body = text.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
            val decoded = try {
                Base64.decode(body, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
            } catch (_: IllegalArgumentException) {
                return "OpenSSH"
            }
            when {
                "ssh-ed25519" in decoded -> "OpenSSH ed25519"
                "ecdsa-sha2" in decoded -> "OpenSSH ECDSA"
                "ssh-rsa" in decoded -> "OpenSSH RSA"
                else -> "OpenSSH"
            }
        }

        "BEGIN RSA PRIVATE KEY" in text -> "PEM RSA"
        "BEGIN EC PRIVATE KEY" in text -> "PEM EC"
        "BEGIN PRIVATE KEY" in text -> "PKCS#8"
        else -> null
    }
}
