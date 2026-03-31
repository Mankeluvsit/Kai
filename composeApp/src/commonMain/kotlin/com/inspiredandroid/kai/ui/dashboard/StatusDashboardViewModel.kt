package com.inspiredandroid.kai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inspiredandroid.kai.getBackgroundDispatcher
import com.inspiredandroid.kai.integrations.ManagedServiceConfig
import com.inspiredandroid.kai.integrations.ManagedServiceId
import com.inspiredandroid.kai.integrations.ManagedServiceState
import com.inspiredandroid.kai.integrations.ManagedServiceStatus
import com.inspiredandroid.kai.integrations.ServiceDashboardRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StatusDashboardViewModel(
    private val repository: ServiceDashboardRepository,
) : ViewModel() {
    private var pollJob: Job? = null

    private val _state = MutableStateFlow(buildState())
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _state.value,
    )

    fun onScreenVisible() {
        if (pollJob != null) return
        refresh()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(8_000)
                refresh(quiet = true)
            }
        }
    }

    fun onScreenHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch(getBackgroundDispatcher()) {
            if (!quiet) {
                _state.update { it.copy(isRefreshing = true) }
            }
            val cards = ManagedServiceId.entries.map { serviceId ->
                val current = _state.value.services.find { it.serviceId == serviceId }
                val status = repository.fetchStatus(serviceId)
                ServiceDashboardCardState(
                    serviceId = serviceId,
                    title = serviceTitle(serviceId),
                    config = repository.getConfig(serviceId),
                    status = status,
                    isBusy = current?.isBusy == true,
                )
            }
            _state.update { it.copy(isRefreshing = false, services = cards) }
        }
    }

    fun updateConfig(serviceId: ManagedServiceId, transform: (ManagedServiceConfig) -> ManagedServiceConfig) {
        val updated = transform(repository.getConfig(serviceId))
        repository.saveConfig(updated)
        _state.update { state ->
            state.copy(
                services = state.services.map { card ->
                    if (card.serviceId == serviceId) card.copy(config = updated) else card
                },
            )
        }
    }

    fun startService(serviceId: ManagedServiceId) {
        runCommand(serviceId, starting = true) {
            repository.startService(serviceId)
        }
    }

    fun stopService(serviceId: ManagedServiceId) {
        runCommand(serviceId, starting = false) {
            repository.stopService(serviceId)
        }
    }

    private fun runCommand(
        serviceId: ManagedServiceId,
        starting: Boolean,
        block: suspend () -> com.inspiredandroid.kai.integrations.LocalServiceCommandResult,
    ) {
        _state.update { state ->
            state.copy(
                services = state.services.map { card ->
                    if (card.serviceId == serviceId) {
                        card.copy(
                            isBusy = true,
                            status = card.status.copy(
                                state = if (starting) ManagedServiceState.Starting else ManagedServiceState.Stopping,
                                statusMessage = if (starting) "Running start command..." else "Running stop command...",
                            ),
                        )
                    } else {
                        card
                    }
                },
            )
        }
        viewModelScope.launch(getBackgroundDispatcher()) {
            val result = block()
            _state.update { state ->
                state.copy(
                    services = state.services.map { card ->
                        if (card.serviceId == serviceId) {
                            card.copy(
                                isBusy = false,
                                status = card.status.copy(
                                    state = if (result.success) ManagedServiceState.Unknown else ManagedServiceState.Error,
                                    statusMessage = result.output.ifBlank {
                                        if (result.success) "Command finished." else "Command failed."
                                    },
                                ),
                            )
                        } else {
                            card
                        }
                    },
                )
            }
            delay(1_000)
            refresh()
        }
    }

    fun openBrowser(serviceId: ManagedServiceId) {
        val url = repository.browserUrl(serviceId)
        if (url.isBlank()) return
        _state.update {
            it.copy(
                browserTarget = ServiceBrowserTarget(
                    title = serviceTitle(serviceId),
                    url = url,
                ),
            )
        }
    }

    fun clearBrowserTarget() {
        _state.update { it.copy(browserTarget = null) }
    }

    private fun buildState(): StatusDashboardUiState = StatusDashboardUiState(
        services = ManagedServiceId.entries.map { serviceId ->
            ServiceDashboardCardState(
                serviceId = serviceId,
                title = serviceTitle(serviceId),
                config = repository.getConfig(serviceId),
                status = ManagedServiceStatus(serviceId = serviceId),
            )
        },
    )

    private fun serviceTitle(serviceId: ManagedServiceId): String = when (serviceId) {
        ManagedServiceId.CodexUi -> "CodexUI"
        ManagedServiceId.OpenClaw -> "OpenClaw"
    }
}
