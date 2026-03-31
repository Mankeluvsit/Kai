@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.inspiredandroid.kai.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.EmbeddedBrowserView
import com.inspiredandroid.kai.integrations.ManagedServiceConfig
import com.inspiredandroid.kai.integrations.ManagedServiceState
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StatusDashboardScreen(
    viewModel: StatusDashboardViewModel = koinViewModel(),
    navigationTabBar: (@Composable () -> Unit)? = null,
) {
    val uiState by viewModel.state.collectAsState()

    DisposableEffect(Unit) {
        viewModel.onScreenVisible()
        onDispose { viewModel.onScreenHidden() }
    }

    StatusDashboardScreenContent(
        uiState = uiState,
        onUpdateConfig = viewModel::updateConfig,
        onRefresh = { viewModel.refresh() },
        onStart = viewModel::startService,
        onStop = viewModel::stopService,
        onOpenBrowser = viewModel::openBrowser,
        onCloseBrowser = viewModel::clearBrowserTarget,
        navigationTabBar = navigationTabBar,
    )
}

@Composable
fun StatusDashboardScreenContent(
    uiState: StatusDashboardUiState,
    onUpdateConfig: (com.inspiredandroid.kai.integrations.ManagedServiceId, (ManagedServiceConfig) -> ManagedServiceConfig) -> Unit,
    onRefresh: () -> Unit,
    onStart: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
    onStop: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
    onOpenBrowser: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
    onCloseBrowser: () -> Unit,
    navigationTabBar: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .statusBarsPadding(),
    ) {
        if (uiState.browserTarget != null) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(uiState.browserTarget.title, style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onCloseBrowser) {
                        Text("Close")
                    }
                }
                EmbeddedBrowserView(
                    url = uiState.browserTarget.url,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return
        }

        Column(Modifier.fillMaxSize()) {
            if (navigationTabBar != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    navigationTabBar()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Status Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "CodexUI and OpenClaw controls stay inside Kai.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onRefresh) {
                    if (uiState.isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Refresh")
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                uiState.services.forEach { card ->
                    ServiceDashboardCard(
                        card = card,
                        onUpdateConfig = onUpdateConfig,
                        onStart = onStart,
                        onStop = onStop,
                        onOpenBrowser = onOpenBrowser,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ServiceDashboardCard(
    card: ServiceDashboardCardState,
    onUpdateConfig: (com.inspiredandroid.kai.integrations.ManagedServiceId, (ManagedServiceConfig) -> ManagedServiceConfig) -> Unit,
    onStart: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
    onStop: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
    onOpenBrowser: (com.inspiredandroid.kai.integrations.ManagedServiceId) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stateLabel(card.status.state, card.status.statusMessage),
                        color = stateColor(card.status.state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (card.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                }
            }

            ServiceConfigFields(card = card, onUpdateConfig = onUpdateConfig)

            if (card.status.metadata.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.status.metadata.forEach { item ->
                        Text(
                            "${item.label}: ${item.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (card.status.recentLogs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recent logs", style = MaterialTheme.typography.labelLarge)
                    card.status.recentLogs.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStart(card.serviceId) }, enabled = !card.isBusy) {
                    Text("Start")
                }
                Button(onClick = { onStop(card.serviceId) }, enabled = !card.isBusy) {
                    Text("Stop")
                }
                Button(onClick = { onOpenBrowser(card.serviceId) }) {
                    Text("Open in Browser")
                }
            }
        }
    }
}

@Composable
private fun ServiceConfigFields(
    card: ServiceDashboardCardState,
    onUpdateConfig: (com.inspiredandroid.kai.integrations.ManagedServiceId, (ManagedServiceConfig) -> ManagedServiceConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = card.config.serverUrl,
            onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(serverUrl = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server address") },
            singleLine = true,
        )
        OutlinedTextField(
            value = if (card.config.port == 0) "" else card.config.port.toString(),
            onValueChange = { value ->
                onUpdateConfig(card.serviceId) { it.copy(port = value.toIntOrNull() ?: 0) }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server port") },
            singleLine = true,
        )
        OutlinedTextField(
            value = card.config.token,
            onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(token = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Token / password") },
            singleLine = true,
        )
        if (card.serviceId == com.inspiredandroid.kai.integrations.ManagedServiceId.OpenClaw) {
            OutlinedTextField(
                value = card.config.gatewayUrl,
                onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(gatewayUrl = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway address") },
                singleLine = true,
            )
            OutlinedTextField(
                value = if (card.config.gatewayPort == 0) "" else card.config.gatewayPort.toString(),
                onValueChange = { value ->
                    onUpdateConfig(card.serviceId) { it.copy(gatewayPort = value.toIntOrNull() ?: 0) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway port") },
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = card.config.browserUrl,
            onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(browserUrl = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Browser URL") },
            singleLine = true,
        )
        OutlinedTextField(
            value = card.config.startCommand,
            onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(startCommand = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Start command") },
            singleLine = true,
        )
        OutlinedTextField(
            value = card.config.stopCommand,
            onValueChange = { value -> onUpdateConfig(card.serviceId) { it.copy(stopCommand = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Stop command") },
            singleLine = true,
        )
    }
}

private fun stateLabel(state: ManagedServiceState, message: String): String = when {
    message.isNotBlank() -> message
    state == ManagedServiceState.Running -> "Running"
    state == ManagedServiceState.Starting -> "Starting"
    state == ManagedServiceState.Stopping -> "Stopping"
    state == ManagedServiceState.Stopped -> "Stopped"
    state == ManagedServiceState.Error -> "Error"
    else -> "Unknown"
}

@Composable
private fun stateColor(state: ManagedServiceState) = when (state) {
    ManagedServiceState.Running -> MaterialTheme.colorScheme.primary
    ManagedServiceState.Starting,
    ManagedServiceState.Stopping,
    -> MaterialTheme.colorScheme.tertiary
    ManagedServiceState.Error -> MaterialTheme.colorScheme.error
    ManagedServiceState.Stopped,
    ManagedServiceState.Unknown,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}
