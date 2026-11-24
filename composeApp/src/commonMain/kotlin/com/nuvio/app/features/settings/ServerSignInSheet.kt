package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.api.funnelSourceFormOpened
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.QuickConnectDriver
import com.nuvio.app.features.servers.QuickConnectFailure
import com.nuvio.app.features.servers.QuickConnectPolicy
import com.nuvio.app.features.servers.QuickConnectState
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerCertificateException
import com.nuvio.app.features.servers.ServerPlainHttpException
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerTrust
import com.nuvio.app.features.servers.supports
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.plex.PlexProvider
import com.nuvio.app.features.servers.silo.SiloProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.servers_cert_changed_message
import nuvio.composeapp.generated.resources.servers_cert_changed_title
import nuvio.composeapp.generated.resources.servers_cert_fingerprint
import nuvio.composeapp.generated.resources.servers_cert_message
import nuvio.composeapp.generated.resources.servers_cert_title
import nuvio.composeapp.generated.resources.servers_plain_http_continue
import nuvio.composeapp.generated.resources.servers_plain_http_message
import nuvio.composeapp.generated.resources.servers_plain_http_title
import nuvio.composeapp.generated.resources.servers_cert_trust
import nuvio.composeapp.generated.resources.servers_cert_trust_new
import nuvio.composeapp.generated.resources.servers_quick_connect
import nuvio.composeapp.generated.resources.servers_quick_connect_back
import nuvio.composeapp.generated.resources.servers_quick_connect_disabled
import nuvio.composeapp.generated.resources.servers_quick_connect_expired
import nuvio.composeapp.generated.resources.servers_quick_connect_failed
import nuvio.composeapp.generated.resources.servers_quick_connect_instructions
import nuvio.composeapp.generated.resources.servers_quick_connect_retry
import nuvio.composeapp.generated.resources.servers_quick_connect_unreachable
import nuvio.composeapp.generated.resources.servers_quick_connect_waiting
import nuvio.composeapp.generated.resources.servers_address
import nuvio.composeapp.generated.resources.servers_address_hint
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_connecting
import nuvio.composeapp.generated.resources.servers_error_address
import nuvio.composeapp.generated.resources.servers_error_auth
import nuvio.composeapp.generated.resources.servers_error_failed
import nuvio.composeapp.generated.resources.servers_error_forbidden
import nuvio.composeapp.generated.resources.servers_error_unreachable
import nuvio.composeapp.generated.resources.servers_error_unsupported
import nuvio.composeapp.generated.resources.servers_failure_remote_playback
import nuvio.composeapp.generated.resources.servers_password
import nuvio.composeapp.generated.resources.servers_sign_in_subtitle
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_username
import org.jetbrains.compose.resources.stringResource

internal data class ServerSignInRequest(
    val provider: ServerProvider,
    val address: String = "",
    val username: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSignInSheet(
    request: ServerSignInRequest,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { TelemetryAccess.current().funnelSourceFormOpened() }
    when (val provider = request.provider) {
        is PlexProvider -> {
            PlexSignInSheet(provider = provider, onConnected = onConnected, onDismiss = onDismiss)
            return
        }
        is SiloProvider -> {
            SiloSignInSheet(provider = provider, address = request.address, onConnected = onConnected, onDismiss = onDismiss)
            return
        }
        else -> Unit
    }
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var address by rememberSaveable { mutableStateOf(request.address) }
    var username by rememberSaveable { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ServerFailure?>(null) }
    var certificate by remember { mutableStateOf<ServerCertificateException?>(null) }
    var quickConnect by remember { mutableStateOf<QuickConnectDriver?>(null) }
    // An address typed without a scheme that only answers over plain http: asked before any secret is sent.
    var plainHttp by remember { mutableStateOf<String?>(null) }
    val canConnect = !connecting && address.isNotBlank() && username.isNotBlank()
    val canQuickConnect = !connecting && address.isNotBlank() && request.provider.supports(ServerCapability.QUICK_CONNECT)

    fun connect() {
        if (!canConnect) return
        connecting = true
        error = null
        scope.launch {
            try {
                val connection = ServerRepository.connect(request.provider, address, username.trim(), password)
                password = ""
                dismissNuvioBottomSheet(sheetState) { onConnected(connection) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (untrusted: ServerCertificateException) {
                certificate = untrusted
            } catch (plain: ServerPlainHttpException) {
                plainHttp = plain.httpAddress
            } catch (failure: ServerException) {
                error = failure.failure
            } catch (_: Exception) {
                error = ServerFailure.FAILED
            } finally {
                connecting = false
            }
        }
    }

    fun startQuickConnect() {
        if (!canQuickConnect) return
        error = null
        val driver = QuickConnectDriver(request.provider, address)
        quickConnect = driver
        scope.launch { driver.start() }
    }

    fun trustCertificate(prompt: ServerCertificateException) {
        ServerTrust.shared.pin(prompt.authority, prompt.fingerprint)
        certificate = null
        // Resume what was being attempted: the Quick Connect request, or the password sign-in.
        val driver = quickConnect
        if (driver != null) scope.launch { driver.start() } else connect()
    }

    fun continueOverPlainHttp(httpAddress: String) {
        // Explicit from here on: the typed address becomes the http one, so nothing is tried or decided silently again.
        address = httpAddress
        plainHttp = null
        if (quickConnect != null) {
            val retry = QuickConnectDriver(request.provider, httpAddress)
            quickConnect = retry
            scope.launch { retry.start() }
        } else {
            connect()
        }
    }

    val driver = quickConnect
    val qcState = driver?.state?.collectAsStateWithLifecycle()?.value
    val lifecycleOwner = LocalLifecycleOwner.current
    val waiting = qcState is QuickConnectState.Waiting
    // Lifecycle-bound poll: runs only while this sheet is visible (RESUMED), ends at a terminal state or the 10 minute deadline.
    LaunchedEffect(driver, waiting) {
        if (driver != null && waiting) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { driver.run() }
    }
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(waiting) {
        while (waiting && isActive) {
            nowMs = Clock.System.now().toEpochMilliseconds()
            delay(1_000L)
        }
    }
    // A Quick Connect request that stopped on an untrusted certificate asks the same trust decision as a password sign-in.
    LaunchedEffect(qcState) {
        driver?.certificateToPrompt()?.let { certificate = it }
        driver?.plainHttpToPrompt()?.let { plainHttp = it }
    }
    LaunchedEffect(qcState) {
        if (driver != null && qcState == QuickConnectState.Authorised) {
            try {
                val connection = driver.complete()
                dismissNuvioBottomSheet(sheetState) { onConnected(connection) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (untrusted: ServerCertificateException) {
                certificate = untrusted
            } catch (_: Exception) {
            }
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!connecting) { quickConnect?.cancel(); onDismiss() } },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = tokens.spacing.sheetPadding,
                    end = tokens.spacing.sheetPadding,
                    bottom = tokens.spacing.sheetPadding,
                ),
        ) {
            SheetHeader(
                title = stringResource(Res.string.servers_sign_in_title, request.provider.displayName),
                subtitle = stringResource(Res.string.servers_sign_in_subtitle, request.provider.displayName),
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
            val prompt = certificate
            val plainPrompt = plainHttp
            if (plainPrompt != null) {
                PlainHttpPrompt(
                    address = plainPrompt,
                    onContinue = { continueOverPlainHttp(plainPrompt) },
                    onCancel = {
                        plainHttp = null
                        quickConnect?.cancel()
                        quickConnect = null
                    },
                )
            } else if (prompt != null) {
                CertificatePrompt(
                    prompt = prompt,
                    onTrust = { trustCertificate(prompt) },
                    onCancel = {
                        certificate = null
                        // Declining ends the Quick Connect attempt too: back to the form, nothing saved or pinned.
                        quickConnect?.cancel()
                        quickConnect = null
                    },
                )
            } else if (driver != null && qcState != null) {
                QuickConnectPanel(
                    providerName = request.provider.displayName,
                    state = qcState,
                    remainingMs = (qcState as? QuickConnectState.Waiting)
                        ?.let { QuickConnectPolicy.remainingMs(it.startedAtMs, nowMs) } ?: 0L,
                    onRetry = { scope.launch { driver.start() } },
                    onUsePassword = {
                        driver.cancel()
                        quickConnect = null
                    },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.servers_address)) },
                        placeholder = { Text(stringResource(Res.string.servers_address_hint)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        shape = tokens.shapes.button,
                        colors = sheetFieldColors(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.servers_username)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        shape = tokens.shapes.button,
                        colors = sheetFieldColors(),
                    )
                    SettingsSecretTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        label = stringResource(Res.string.servers_password),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        shape = tokens.shapes.button,
                        colors = sheetFieldColors(),
                        imeAction = ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { connect() }),
                    )
                }
                error?.let { failure ->
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
                    Text(
                        text = failure.signInMessage(request.provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
                Button(
                    onClick = ::connect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NuvioTokens.Space.s56),
                    enabled = canConnect,
                    shape = tokens.shapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                    ),
                ) {
                    if (connecting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NuvioLoadingIndicator(
                                modifier = Modifier.size(tokens.icons.sm),
                                color = tokens.colors.onAccent,
                            )
                            Text(stringResource(Res.string.servers_connecting))
                        }
                    } else {
                        Text(stringResource(Res.string.servers_connect))
                    }
                }
                if (canQuickConnect) {
                    TextButton(
                        onClick = ::startQuickConnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.servers_quick_connect), color = tokens.colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, subtitle: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = tokens.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textSecondary,
    )
}

@Composable
internal fun sheetFieldColors(): TextFieldColors {
    val colors = MaterialTheme.nuvio.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.borderFocus,
        unfocusedBorderColor = colors.borderDefault,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
    )
}

@Composable
private fun ServerFailure.signInMessage(provider: ServerProvider): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(Res.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(Res.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(Res.string.servers_error_unreachable)
    ServerFailure.UNSUPPORTED -> stringResource(Res.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
    ServerFailure.FORBIDDEN -> stringResource(Res.string.servers_error_forbidden)
    ServerFailure.REMOTE_PLAYBACK_BLOCKED -> stringResource(Res.string.servers_failure_remote_playback)
    ServerFailure.INCOMPLETE,
    ServerFailure.CERTIFICATE_UNTRUSTED,
    ServerFailure.CERTIFICATE_CHANGED,
    ServerFailure.FAILED -> stringResource(Res.string.servers_error_failed)
}

@Composable
private fun PlainHttpPrompt(
    address: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        Text(
            text = stringResource(Res.string.servers_plain_http_title),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.danger,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.servers_plain_http_message, address.removePrefix("http://")),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTokens.Space.s56),
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.danger, contentColor = tokens.colors.onAccent),
        ) {
            Text(stringResource(Res.string.servers_plain_http_continue))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_cancel), color = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun CertificatePrompt(
    prompt: ServerCertificateException,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        Text(
            text = stringResource(if (prompt.changed) Res.string.servers_cert_changed_title else Res.string.servers_cert_title),
            style = MaterialTheme.typography.titleMedium,
            color = if (prompt.changed) tokens.colors.danger else tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                if (prompt.changed) Res.string.servers_cert_changed_message else Res.string.servers_cert_message,
                prompt.authority,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.servers_cert_fingerprint, prompt.fingerprint.chunked(2).joinToString(":")),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Button(
            onClick = onTrust,
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTokens.Space.s56),
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (prompt.changed) tokens.colors.danger else tokens.colors.accent,
                contentColor = tokens.colors.onAccent,
            ),
        ) {
            Text(stringResource(if (prompt.changed) Res.string.servers_cert_trust_new else Res.string.servers_cert_trust))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_cancel), color = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun QuickConnectPanel(
    providerName: String,
    state: QuickConnectState,
    remainingMs: Long,
    onRetry: () -> Unit,
    onUsePassword: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is QuickConnectState.Waiting -> {
                Text(
                    text = stringResource(Res.string.servers_quick_connect_instructions, providerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = QuickConnectPolicy.displayCode(state.code),
                    style = MaterialTheme.typography.displayMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.servers_quick_connect_waiting, QuickConnectPolicy.countdownLabel(remainingMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            QuickConnectState.Idle,
            QuickConnectState.Starting,
            QuickConnectState.Authorised -> NuvioLoadingIndicator(
                modifier = Modifier.size(tokens.icons.md),
                color = tokens.colors.accent,
            )
            QuickConnectState.Expired -> QuickConnectRetry(stringResource(Res.string.servers_quick_connect_expired), onRetry)
            is QuickConnectState.Failed -> QuickConnectRetry(
                message = stringResource(
                    when (state.reason) {
                        QuickConnectFailure.DISABLED -> Res.string.servers_quick_connect_disabled
                        QuickConnectFailure.UNREACHABLE -> Res.string.servers_quick_connect_unreachable
                        QuickConnectFailure.CERTIFICATE,
                        QuickConnectFailure.PLAIN_HTTP,
                        QuickConnectFailure.SERVER_ERROR -> Res.string.servers_quick_connect_failed
                    },
                ),
                onRetry = onRetry,
                canRetry = state.reason != QuickConnectFailure.DISABLED,
            )
        }
        TextButton(onClick = onUsePassword) {
            Text(stringResource(Res.string.servers_quick_connect_back), color = tokens.colors.textSecondary)
        }
    }
}

@Composable
private fun QuickConnectRetry(message: String, onRetry: () -> Unit, canRetry: Boolean = true) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.danger,
        textAlign = TextAlign.Center,
    )
    if (canRetry) {
        Button(
            onClick = onRetry,
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
        ) {
            Text(stringResource(Res.string.servers_quick_connect_retry))
        }
    }
}
