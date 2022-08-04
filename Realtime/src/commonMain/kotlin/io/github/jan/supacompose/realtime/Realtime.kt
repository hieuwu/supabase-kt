package io.github.jan.supacompose.realtime

import io.github.aakira.napier.Napier
import io.github.jan.supacompose.SupabaseClient
import io.github.jan.supacompose.SupabaseClientBuilder
import io.github.jan.supacompose.annotiations.SupaComposeInternal
import io.github.jan.supacompose.auth.auth
import io.github.jan.supacompose.plugins.SupacomposePlugin
import io.github.jan.supacompose.plugins.SupacomposePluginProvider
import io.github.jan.supacompose.supabaseJson
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface Realtime : SupacomposePlugin {

    /**
     * The current status of the realtime connection
     */
    val status: StateFlow<Status>

    /**
     * A map of all active the subscriptions
     */
    val subscriptions: Map<String, RealtimeChannel>

    /**
     * Connects to the realtime websocket. The url will be taken from the custom provided [Realtime.Config.customRealtimeURL] or [SupabaseClient]
     */
    suspend fun connect()

    /**
     * Disconnects from the realtime websocket
     */
    fun disconnect()

    /**
     * Calls [callback] whenever [status] changes
     */
    fun onStatusChange(callback: (Status) -> Unit)

    @SupaComposeInternal
    fun addChannel(channel: RealtimeChannel)

    @SupaComposeInternal
    fun removeChannel(topic: String)

    class Config(
        var websocketConfig: WebSockets.Config.() -> Unit = {},
        var secure: Boolean = true,
        var heartbeatInterval: Duration = 10.seconds,
        var customRealtimeURL: String? = null
    )

    companion object : SupacomposePluginProvider<Config, Realtime> {

        override val key = "realtime"

        override fun createConfig(init: Config.() -> Unit) = Config().apply(init)
        override fun setup(builder: SupabaseClientBuilder, config: Config) {
            builder.httpConfig {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(supabaseJson)
                    config.websocketConfig(this)
                }
            }
        }

        override fun create(supabaseClient: SupabaseClient, config: Config): Realtime = RealtimeImpl(supabaseClient, config)

    }

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

}

internal class RealtimeImpl(val supabaseClient: SupabaseClient, private val realtimeConfig: Realtime.Config) :
    Realtime {

    lateinit var ws: DefaultClientWebSocketSession
    private val _status = MutableStateFlow(Realtime.Status.DISCONNECTED)
    override val status: StateFlow<Realtime.Status> = _status.asStateFlow()
    private val _subscriptions = mutableMapOf<String, RealtimeChannel>()
    override val subscriptions: Map<String, RealtimeChannel>
        get() = _subscriptions.toMap()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val statusListeners = mutableListOf<(Realtime.Status) -> Unit>()
    var ref = 0
    var heartbeatRef = 0

    override fun onStatusChange(callback: (Realtime.Status) -> Unit) {
        statusListeners.add(callback)
    }

    override suspend fun connect() {
        supabaseClient.auth.onSessionChange { new, old ->
            if (status.value == Realtime.Status.CONNECTED) {
                if (new == null) {
                    disconnect()
                } else {
                    updateJwt(new.accessToken)
                }
            }
        }
        if (status.value == Realtime.Status.CONNECTED) throw IllegalStateException("Websocket already connected")
        val prefix = if (realtimeConfig.secure) "wss://" else "ws://"
        statusListeners.forEach { it(Realtime.Status.CONNECTING) }
        _status.value = Realtime.Status.CONNECTING
        val realtimeUrl = realtimeConfig.customRealtimeURL ?: (prefix + supabaseClient.supabaseUrl + ("/realtime/v1/websocket?apikey=${supabaseClient.supabaseKey}"))
        ws = supabaseClient.httpClient.webSocketSession(realtimeUrl)
        _status.value = Realtime.Status.CONNECTED
        statusListeners.forEach { it(Realtime.Status.CONNECTED) }
        Napier.i { "Connected to realtime websocket!" }
        scope.launch {
            for (frame in ws.incoming) {
                val message = frame as? Frame.Text ?: continue
                onMessage(message.readText())
            }
            _status.value = Realtime.Status.DISCONNECTED
            Napier.i { "Disconnected from realtime websocket!" }
        }
        scope.launch {
            while (isActive) {
                delay(realtimeConfig.heartbeatInterval)
                sendHeartbeat()
            }
        }
    }

    override fun disconnect() {
        ws.cancel()
        scope.cancel()
        statusListeners.forEach { it(Realtime.Status.DISCONNECTED) }
        _status.value = Realtime.Status.DISCONNECTED
    }

    private fun onMessage(stringMessage: String) {
        val message = supabaseJson.decodeFromString<RealtimeMessage>(stringMessage)
        println(stringMessage)
        val channel = subscriptions[message.topic] as? RealtimeChannelImpl
        if(message.ref?.toIntOrNull() == heartbeatRef) {
            Napier.i { "Heartbeat received" }
            heartbeatRef = 0
        } else {
            println(channel)
            channel?.onMessage(message)
        }
    }

    private fun updateJwt(jwt: String) {

    }

    private suspend fun sendHeartbeat() {
        if (heartbeatRef != 0) {
            heartbeatRef = 0
            ref = 0
            Napier.w { "Heartbeat timeout. Trying to reconnect" }
            disconnect()
            scope.launch {
                connect()
            }
            return
        }
        Napier.d { "Sending heartbeat" }
        heartbeatRef = ++ref
        ws.sendSerialized(RealtimeMessage("phoenix", "heartbeat", buildJsonObject { }, heartbeatRef.toString()))
    }

    @SupaComposeInternal
    override fun removeChannel(topic: String) {
        _subscriptions.remove(topic)
    }

    @SupaComposeInternal
    override fun addChannel(channel: RealtimeChannel) {
        _subscriptions[channel.topic] = channel
    }

    override suspend fun close() {
        ws.close(CloseReason(CloseReason.Codes.NORMAL, "Connection closed by library"))
    }

}

/**
 * Creates a new [RealtimeChannel]
 */
inline fun Realtime.createChannel(builder: RealtimeChannelBuilder.() -> Unit): RealtimeChannel {
    return RealtimeChannelBuilder(this as RealtimeImpl).apply(builder).build()
}

/**
 * Creates a new [RealtimeChannel] and joins it after creation
 */
suspend inline fun Realtime.createAndJoinChannel(builder: RealtimeChannelBuilder.() -> Unit): RealtimeChannel {
    return RealtimeChannelBuilder(this as RealtimeImpl).apply(builder).build().also { it.join() }
}

/**
 * Supabase Realtime is a way to listen to changes in the PostgreSQL database via websockets
 */
val SupabaseClient.realtime: Realtime
    get() = pluginManager.getPlugin(Realtime.key)