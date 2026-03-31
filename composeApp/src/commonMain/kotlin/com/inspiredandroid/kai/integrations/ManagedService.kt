package com.inspiredandroid.kai.integrations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ManagedServiceId {
    @SerialName("codexui")
    CodexUi,

    @SerialName("openclaw")
    OpenClaw,
}

@Serializable
data class ManagedServiceConfig(
    val serviceId: ManagedServiceId,
    val serverUrl: String = "",
    val port: Int = 0,
    val token: String = "",
    val browserUrl: String = "",
    val gatewayUrl: String = "",
    val gatewayPort: Int = 0,
    val startCommand: String = "",
    val stopCommand: String = "",
)

fun defaultManagedServiceConfig(serviceId: ManagedServiceId): ManagedServiceConfig = when (serviceId) {
    ManagedServiceId.CodexUi -> ManagedServiceConfig(
        serviceId = serviceId,
        serverUrl = "http://127.0.0.1",
        port = 18923,
        browserUrl = "http://127.0.0.1:18923",
    )

    ManagedServiceId.OpenClaw -> ManagedServiceConfig(
        serviceId = serviceId,
        serverUrl = "http://127.0.0.1",
        port = 18789,
        browserUrl = "http://127.0.0.1:18789",
        gatewayUrl = "ws://127.0.0.1",
        gatewayPort = 18789,
    )
}

enum class ManagedServiceState {
    Unknown,
    Starting,
    Running,
    Stopping,
    Stopped,
    Error,
}

data class ServiceStatusField(
    val label: String,
    val value: String,
)

data class ManagedServiceStatus(
    val serviceId: ManagedServiceId,
    val state: ManagedServiceState = ManagedServiceState.Unknown,
    val statusMessage: String = "",
    val version: String = "",
    val uptime: String = "",
    val activeConnections: Int? = null,
    val lastCheckedAtMillis: Long? = null,
    val recentLogs: List<String> = emptyList(),
    val metadata: List<ServiceStatusField> = emptyList(),
)

data class LocalServiceCommandResult(
    val success: Boolean,
    val exitCode: Int? = null,
    val output: String = "",
)
