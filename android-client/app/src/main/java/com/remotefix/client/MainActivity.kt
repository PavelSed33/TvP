package com.remotefix.client

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : androidx.activity.ComponentActivity() {
    private val viewModel: RemoteFixViewModel by viewModels()
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onScreenPermissionResult(result.resultCode, result.data)
    }
    private val notificationsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.createSession() else viewModel.onNotificationsDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = requireNotNull(getSystemService(MediaProjectionManager::class.java))
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RemoteFixScreen(
                        viewModel = viewModel,
                        onCreateSession = ::createSessionWithVisibleNotification,
                        onRequestScreenPermission = {
                            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.ensureSessionNotificationVisible()
    }

    private fun createSessionWithVisibleNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.createSession()
        }
    }
}

@Composable
private fun RemoteFixScreen(
    viewModel: RemoteFixViewModel,
    onCreateSession: () -> Unit,
    onRequestScreenPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.server_settings), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.server_url)) },
                        singleLine = true,
                        enabled = state.code == null && !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { viewModel.saveServerUrl(serverUrl) },
                        enabled = state.code == null && !state.busy,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.save)) }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.session), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(state.status.labelRes), color = MaterialTheme.colorScheme.primary)
                    state.code?.let { code ->
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.connection_code), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                        )
                        state.expiresAt?.takeIf(String::isNotBlank)?.let {
                            Text(stringResource(R.string.code_expiry, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.connectionRequest) {
                        Text(stringResource(R.string.connection_request_explanation), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { viewModel.approveConnection(true) }) { Text(stringResource(R.string.approve)) }
                            OutlinedButton(onClick = { viewModel.approveConnection(false) }) { Text(stringResource(R.string.deny)) }
                        }
                    }
                    if (state.sessionActive) {
                        Text(stringResource(R.string.session_active_explanation), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.screen_capture_separate_consent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            if (state.screenPermissionGranted) viewModel.stopScreenShare() else onRequestScreenPermission()
                        }) {
                            Text(stringResource(if (state.screenPermissionGranted) R.string.stop_screen_capture else R.string.allow_screen_capture))
                        }
                        Text(stringResource(state.screenShareState.labelRes), style = MaterialTheme.typography.bodySmall)
                        state.screenShareError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.screenPermissionGranted && state.screenShareState != ScreenShareState.STREAMING) {
                            Text(stringResource(R.string.no_capture_yet), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.code == null || state.status == SessionStatus.ENDED || state.status == SessionStatus.ERROR) {
                        Button(onClick = onCreateSession, enabled = !state.busy) {
                            Text(stringResource(if (state.busy) R.string.creating_session else R.string.create_session))
                        }
                    }
                    if (state.code != null) {
                        OutlinedButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.emergency_disconnect)) }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.device_diagnostics), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = viewModel::collectDiagnostics) { Text(stringResource(R.string.run_diagnostics)) }
                    state.diagnostics?.let { diagnostics ->
                        DiagnosticLine(stringResource(R.string.device_model), "${diagnostics.manufacturer} ${diagnostics.model}".trim())
                        DiagnosticLine(stringResource(R.string.android_version), diagnostics.androidVersion)
                        DiagnosticLine(stringResource(R.string.app_version), diagnostics.appVersion)
                        DiagnosticLine(stringResource(R.string.battery), diagnostics.batteryPercent?.let { "$it%" } ?: "—")
                        DiagnosticLine(stringResource(R.string.free_ram), stringResource(R.string.megabytes, diagnostics.freeRamMb))
                        DiagnosticLine(stringResource(R.string.free_storage), stringResource(R.string.megabytes, diagnostics.freeStorageMb))
                        DiagnosticLine(stringResource(R.string.network), diagnostics.networkType)
                        diagnostics.checks.forEach { check ->
                            DiagnosticLine(check.name, check.status)
                        }
                    } ?: Text(stringResource(R.string.diagnostics_empty), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.diagnostics_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.event_log), style = MaterialTheme.typography.titleSmall)
                    if (state.events.isEmpty()) {
                        Text(stringResource(R.string.no_events), style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.events.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Text(stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
