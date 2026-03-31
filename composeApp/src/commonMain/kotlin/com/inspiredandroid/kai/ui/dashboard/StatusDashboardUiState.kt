package com.inspiredandroid.kai.ui.dashboard

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.integrations.ManagedServiceConfig
import com.inspiredandroid.kai.integrations.ManagedServiceId
import com.inspiredandroid.kai.integrations.ManagedServiceStatus

@Immutable
data class StatusDashboardUiState(
    val isRefreshing: Boolean = false,
    val services: List<ServiceDashboardCardState> = emptyList(),
    val browserTarget: ServiceBrowserTarget? = null,
)

@Immutable
data class ServiceDashboardCardState(
    val serviceId: ManagedServiceId,
    val title: String,
    val config: ManagedServiceConfig,
    val status: ManagedServiceStatus,
    val isBusy: Boolean = false,
)

@Immutable
data class ServiceBrowserTarget(
    val title: String,
    val url: String,
)
