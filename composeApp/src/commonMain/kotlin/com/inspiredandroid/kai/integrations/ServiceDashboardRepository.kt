package com.inspiredandroid.kai.integrations

import com.inspiredandroid.kai.executeLocalServiceCommand
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.data.AppSettings
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive

class ServiceDashboardRepository(
    private val appSettings: AppSettings,
) {
    fun getConfig(serviceId: ManagedServiceId): ManagedServiceConfig = appSettings.getManagedServiceConfig(serviceId)

    fun saveConfig(config: ManagedServiceConfig) {
        appSettings.setManagedServiceConfig(config)
    }

    fun browserUrl(serviceId: ManagedServiceId): String {
        val config = getConfig(serviceId)
        return when {
            config.browserUrl.isNotBlank() -> config.browserUrl.trim()
            serviceId == ManagedServiceId.CodexUi -> buildUrl(config.serverUrl, config.port)
            else -> buildUrl(config.serverUrl, config.port)
        }
    }

    suspend fun fetchStatus(serviceId: ManagedServiceId): ManagedServiceStatus = when (serviceId) {
        ManagedServiceId.CodexUi -> fetchCodexUiStatus(getConfig(serviceId))
        ManagedServiceId.OpenClaw -> fetchOpenClawStatus(getConfig(serviceId))
    }

    suspend fun startService(serviceId: ManagedServiceId): LocalServiceCommandResult {
        val command = getConfig(serviceId).startCommand.trim()
        if (command.isBlank()) {
            return LocalServiceCommandResult(success = false, output = "No start command configured.")
        }
        return executeLocalServiceCommand(command)
    }

    suspend fun stopService(serviceId: ManagedServiceId): LocalServiceCommandResult {
        val command = getConfig(serviceId).stopCommand.trim()
        if (command.isBlank()) {
            return LocalServiceCommandResult(success = false, output = "No stop command configured.")
        }
        return executeLocalServiceCommand(command)
    }

    private suspend fun fetchCodexUiStatus(config: ManagedServiceConfig): ManagedServiceStatus {
        val baseUrl = buildUrl(config.serverUrl, config.port)
        val client = httpClient {
            install(ContentNegotiation) {
                json(SharedDashboardJson)
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }

        return try {
            val rootResponse = client.get(baseUrl)
            val rootBody = rootResponse.bodyAsText()
            val uiReachable = rootResponse.status.value in 200..299 && rootBody.contains("<html", ignoreCase = true)

            if (config.token.isNotBlank()) {
                val loginResponse = client.post("${baseUrl.trimEnd('/')}/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("password", config.token) })
                }
                if (loginResponse.status == HttpStatusCode.Unauthorized) {
                    return ManagedServiceStatus(
                        serviceId = ManagedServiceId.CodexUi,
                        state = ManagedServiceState.Error,
                        statusMessage = "Authentication failed",
                        lastCheckedAtMillis = nowMillis(),
                    )
                }
            }

            val methods = readJsonDataArrayOrNull(
                client.get("${baseUrl.trimEnd('/')}/codex-api/meta/methods"),
            )
            val notifications = readJsonDataArrayOrNull(
                client.get("${baseUrl.trimEnd('/')}/codex-api/meta/notifications"),
            )
            val homeDirectory = readJsonDataObjectOrNull(
                client.get("${baseUrl.trimEnd('/')}/codex-api/home-directory"),
            )?.get("path")?.jsonPrimitive?.content.orEmpty()
            val apiReachable = methods != null && notifications != null

            ManagedServiceStatus(
                serviceId = ManagedServiceId.CodexUi,
                state = when {
                    apiReachable -> ManagedServiceState.Running
                    uiReachable -> ManagedServiceState.Starting
                    else -> ManagedServiceState.Stopped
                },
                statusMessage = when {
                    apiReachable -> "Bridge reachable"
                    uiReachable -> "Web UI reachable, API bridge unavailable"
                    else -> "Server unavailable"
                },
                lastCheckedAtMillis = nowMillis(),
                metadata = buildList {
                    add(ServiceStatusField("Web UI", if (uiReachable) "Reachable" else "Unavailable"))
                    add(ServiceStatusField("API bridge", if (apiReachable) "Reachable" else "Unavailable"))
                    if (methods != null) add(ServiceStatusField("RPC methods", methods.size.toString()))
                    if (notifications != null) add(ServiceStatusField("Notifications", notifications.size.toString()))
                    if (homeDirectory.isNotBlank()) add(ServiceStatusField("Workspace", homeDirectory))
                },
            )
        } catch (error: Throwable) {
            ManagedServiceStatus(
                serviceId = ManagedServiceId.CodexUi,
                state = ManagedServiceState.Stopped,
                statusMessage = error.message ?: "Connection failed",
                lastCheckedAtMillis = nowMillis(),
            )
        } finally {
            client.close()
        }
    }

    private suspend fun fetchOpenClawStatus(config: ManagedServiceConfig): ManagedServiceStatus {
        suspend fun httpFallback(reason: String): ManagedServiceStatus = fetchOpenClawHttpStatus(config, reason)

        val wsUrl = buildUrl(
            base = if (config.gatewayUrl.isNotBlank()) config.gatewayUrl else config.serverUrl,
            port = if (config.gatewayPort > 0) config.gatewayPort else config.port,
            defaultProtocol = URLProtocol.WS,
        )

        val client = httpClient {
            install(WebSockets)
            install(ContentNegotiation) {
                json(SharedDashboardJson)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }

        return try {
            val session = client.webSocketSession(urlString = wsUrl)
            if (!session.isActive) {
                return httpFallback("Gateway unavailable")
            }

            val connectId = newRequestId()
            session.send(
                Frame.Text(
                    buildRequestFrame(
                        id = connectId,
                        method = "connect",
                        params = buildJsonObject {
                            put("minProtocol", 3)
                            put("maxProtocol", 3)
                            putJsonObject("client") {
                                put("id", "openclaw-android")
                                put("version", "Kai")
                                put("platform", "Android")
                                put("mode", "ui")
                            }
                            put("role", "operator")
                            putJsonArray("scopes") {
                                add(JsonPrimitive("operator.read"))
                            }
                            putJsonArray("caps") {}
                            if (config.token.isNotBlank()) {
                                putJsonObject("auth") {
                                    put("token", config.token)
                                    put("password", config.token)
                                }
                            }
                            put("userAgent", "Kai Android")
                            put("locale", "en-US")
                        },
                    ),
                ),
            )

            val helloResponse = awaitResponse(session.incoming, connectId)
                ?: return ManagedServiceStatus(
                    serviceId = ManagedServiceId.OpenClaw,
                    state = ManagedServiceState.Error,
                    statusMessage = "Gateway connect timeout",
                    lastCheckedAtMillis = nowMillis(),
                )
            if (!helloResponse.ok) {
                return ManagedServiceStatus(
                    serviceId = ManagedServiceId.OpenClaw,
                    state = ManagedServiceState.Error,
                    statusMessage = helloResponse.errorMessage.ifBlank { "Gateway authentication failed" },
                    lastCheckedAtMillis = nowMillis(),
                )
            }

            val requestIds = mapOf(
                "health" to newRequestId(),
                "status" to newRequestId(),
                "logs.tail" to newRequestId(),
                "channels.status" to newRequestId(),
            )
            session.send(Frame.Text(buildRequestFrame(requestIds.getValue("health"), "health", buildJsonObject {})))
            session.send(Frame.Text(buildRequestFrame(requestIds.getValue("status"), "status", buildJsonObject {})))
            session.send(
                Frame.Text(
                    buildRequestFrame(
                        requestIds.getValue("logs.tail"),
                        "logs.tail",
                        buildJsonObject {
                            put("limit", 8)
                            put("maxBytes", 4000)
                        },
                    ),
                ),
            )
            session.send(
                Frame.Text(
                    buildRequestFrame(
                        requestIds.getValue("channels.status"),
                        "channels.status",
                        buildJsonObject { put("probe", false) },
                    ),
                ),
            )

            val responses = mutableMapOf<String, GatewayRpcResponse>()
            while (responses.size < requestIds.size) {
                val response = awaitAnyResponse(session.incoming) ?: break
                requestIds.entries.firstOrNull { it.value == response.id }?.let { (method, _) ->
                    responses[method] = response
                }
            }

            val helloPayload = helloResponse.payload
            val healthPayload = responses["health"]?.payload
            val logsPayload = responses["logs.tail"]?.payload
            val channelsPayload = responses["channels.status"]?.payload

            val version = helloPayload.jsonObject["server"]
                ?.jsonObject
                ?.get("version")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
            val uptimeMs = helloPayload.jsonObject["snapshot"]
                ?.jsonObject
                ?.get("uptimeMs")
                ?.jsonPrimitive
                ?.longOrNull
            val agents = healthPayload?.jsonObject?.get("agents")?.jsonArray?.size ?: 0
            val sessions = healthPayload?.jsonObject?.get("sessions")
                ?.jsonObject
                ?.get("count")
                ?.jsonPrimitive
                ?.intOrNull
            val channelAccounts = channelsPayload?.jsonObject?.get("channelAccounts")?.jsonObject
            val connectedChannels = channelAccounts
                ?.values
                ?.sumOf { accounts ->
                    accounts.jsonArray.count { account ->
                        account.jsonObject["connected"]?.jsonPrimitive?.contentOrNull == "true"
                    }
                }
            val logLines = logsPayload?.jsonObject?.get("lines")
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeLast(5)
                .orEmpty()

            session.close()

            ManagedServiceStatus(
                serviceId = ManagedServiceId.OpenClaw,
                state = ManagedServiceState.Running,
                statusMessage = "Gateway connected",
                version = version,
                uptime = uptimeMs?.let(::formatDurationMillis).orEmpty(),
                activeConnections = connectedChannels,
                lastCheckedAtMillis = nowMillis(),
                recentLogs = logLines,
                metadata = buildList {
                    if (agents > 0) add(ServiceStatusField("Agents", agents.toString()))
                    if (sessions != null) add(ServiceStatusField("Sessions", sessions.toString()))
                    if (connectedChannels != null) add(ServiceStatusField("Connected channels", connectedChannels.toString()))
                    val authMode = helloPayload.jsonObject["snapshot"]
                        ?.jsonObject
                        ?.get("authMode")
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                    if (authMode.isNotBlank()) add(ServiceStatusField("Auth", authMode))
                },
            )
        } catch (error: Throwable) {
            httpFallback(error.message ?: "Gateway unavailable")
        } finally {
            client.close()
        }
    }

    private suspend fun fetchOpenClawHttpStatus(
        config: ManagedServiceConfig,
        fallbackReason: String,
    ): ManagedServiceStatus {
        val baseUrl = buildUrl(config.serverUrl, config.port)
        val client = httpClient {
            install(ContentNegotiation) {
                json(SharedDashboardJson)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }

        return try {
            val liveResponse = client.get("${baseUrl.trimEnd('/')}/health") {
                applyOpenClawAuth(config)
            }
            val readyResponse = client.get("${baseUrl.trimEnd('/')}/readyz") {
                applyOpenClawAuth(config)
            }

            val livePayload = readJsonDataObjectOrNull(liveResponse)
            val readyPayload = readJsonDataObjectOrNull(readyResponse)
            val isLive = liveResponse.status.value in 200..299
            val isReady = readyResponse.status == HttpStatusCode.OK &&
                readyPayload?.get("ready")?.jsonPrimitive?.contentOrNull != "false"
            val uptime = readyPayload?.get("uptimeMs")?.jsonPrimitive?.longOrNull?.let(::formatDurationMillis).orEmpty()
            val failingChecks = readyPayload?.get("failing")
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()

            ManagedServiceStatus(
                serviceId = ManagedServiceId.OpenClaw,
                state = when {
                    isReady -> ManagedServiceState.Running
                    isLive -> ManagedServiceState.Starting
                    else -> ManagedServiceState.Stopped
                },
                statusMessage = when {
                    isReady -> "Gateway reachable over HTTP"
                    isLive -> "Gateway live, not ready"
                    else -> fallbackReason
                },
                uptime = uptime,
                lastCheckedAtMillis = nowMillis(),
                metadata = buildList {
                    livePayload?.get("status")?.jsonPrimitive?.contentOrNull?.let {
                        add(ServiceStatusField("Health", it))
                    }
                    add(ServiceStatusField("Transport", "HTTP fallback"))
                    if (failingChecks.isNotEmpty()) {
                        add(ServiceStatusField("Failing checks", failingChecks.joinToString()))
                    }
                },
            )
        } catch (_: Throwable) {
            ManagedServiceStatus(
                serviceId = ManagedServiceId.OpenClaw,
                state = ManagedServiceState.Stopped,
                statusMessage = fallbackReason,
                lastCheckedAtMillis = nowMillis(),
                metadata = listOf(ServiceStatusField("Transport", "HTTP fallback")),
            )
        } finally {
            client.close()
        }
    }
}

private val SharedDashboardJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun buildUrl(
    base: String,
    port: Int,
    path: String = "",
    defaultProtocol: URLProtocol = URLProtocol.HTTP,
): String {
    val normalizedBase = base.trim().ifBlank { "${defaultProtocol.name}://127.0.0.1" }
    val withScheme = if ("://" in normalizedBase) normalizedBase else "${defaultProtocol.name}://$normalizedBase"
    val builder = URLBuilder(withScheme)
    if (port > 0) builder.port = port
    val baseUrl = builder.buildString().trimEnd('/')
    return if (path.isNotBlank()) {
        "$baseUrl/${path.trimStart('/')}"
    } else {
        baseUrl
    }
}

private suspend fun readJsonDataArrayOrNull(response: HttpResponse): JsonArray? {
    val raw = response.bodyAsText()
    if (!looksLikeJsonResponse(response, raw)) return null
    val root = SharedDashboardJson.parseToJsonElement(raw)
    return when (root) {
        is JsonArray -> root
        is JsonObject -> root["data"]?.jsonArray ?: buildJsonArray {}
        else -> buildJsonArray {}
    }
}

private suspend fun readJsonDataObjectOrNull(response: HttpResponse): JsonObject? {
    val raw = response.bodyAsText()
    if (!looksLikeJsonResponse(response, raw)) return null
    val root = SharedDashboardJson.parseToJsonElement(raw)
    return when (root) {
        is JsonObject -> root["data"]?.jsonObject ?: root
        else -> null
    }
}

private fun looksLikeJsonResponse(response: HttpResponse, raw: String): Boolean {
    val contentType = response.contentType()
    if (contentType != null && contentType.match(ContentType.Application.Json)) return true
    val trimmed = raw.trimStart()
    if (trimmed.startsWith("<!doctype", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        return false
    }
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

private fun io.ktor.client.request.HttpRequestBuilder.applyOpenClawAuth(config: ManagedServiceConfig) {
    val token = config.token.trim()
    if (token.isNotBlank()) {
        header("Authorization", "Bearer $token")
    }
}

private data class GatewayRpcResponse(
    val id: String,
    val ok: Boolean,
    val payload: JsonElement = JsonObject(emptyMap()),
    val errorMessage: String = "",
)

private fun buildRequestFrame(id: String, method: String, params: JsonObject): String = buildJsonObject {
    put("type", "req")
    put("id", id)
    put("method", method)
    put("params", params)
}.toString()

private suspend fun awaitResponse(incoming: ReceiveChannel<Frame>, id: String): GatewayRpcResponse? {
    val deadlineMillis = 6_000
    val started = nowMillis()
    while (nowMillis() - started < deadlineMillis) {
        val response = awaitAnyResponse(incoming, deadlineMillis - (nowMillis() - started)) ?: return null
        if (response.id == id) return response
    }
    return null
}

private suspend fun awaitAnyResponse(
    incoming: ReceiveChannel<Frame>,
    timeoutMs: Long = 2_500,
): GatewayRpcResponse? {
    val started = nowMillis()
    while (nowMillis() - started < timeoutMs) {
        val remaining = timeoutMs - (nowMillis() - started)
        val frame = withTimeoutOrNull(remaining.coerceAtLeast(1)) { incoming.receive() } ?: return null
        val text = (frame as? Frame.Text)?.readText() ?: continue
        val root = SharedDashboardJson.parseToJsonElement(text).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "res") continue
        return GatewayRpcResponse(
            id = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ok = root["ok"]?.jsonPrimitive?.contentOrNull == "true",
            payload = root["payload"] ?: JsonObject(emptyMap()),
            errorMessage = root["error"]?.let { error ->
                (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            }.orEmpty(),
        )
    }
    return null
}

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
private fun newRequestId(): String = Uuid.random().toString()

private fun formatDurationMillis(value: Long): String {
    val totalSeconds = value / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
