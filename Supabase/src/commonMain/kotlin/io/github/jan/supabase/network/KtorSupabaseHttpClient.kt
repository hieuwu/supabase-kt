@file:Suppress("UndocumentedPublicFunction")
package io.github.jan.supabase.network

import io.github.jan.supabase.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.logging.d
import io.github.jan.supabase.supabaseJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * A function that can be used to override the default request configuration
 */
typealias HttpRequestOverride = HttpRequestBuilder.() -> Unit

/**
 * A [SupabaseHttpClient] that uses ktor to send requests
 */
@OptIn(SupabaseInternal::class)
class KtorSupabaseHttpClient @SupabaseInternal constructor(
    private val supabase: SupabaseClient
) : SupabaseHttpClient() {

    val logger = supabase.logger.appendTag(" [Network]")
    private val supabaseKey = supabase.supabaseKey
    private val osInformation = supabase.config.osInformation

    private val networkConfig = supabase.config.networkConfig
    private val requestTimeout = networkConfig.requestTimeout
    private val engine = networkConfig.httpEngine
    private val modifiers = networkConfig.httpConfigOverrides

    override suspend fun getDefaultHeaders(): Headers {
        return io.ktor.http.headers {
            defaultHeaders()
        }
    }

    init {
        logger.d { "Creating KtorSupabaseHttpClient with request timeout $requestTimeout, HttpClientEngine: $engine" }
    }

    @SupabaseInternal
    val httpClient = if (engine != null) {
        HttpClient(engine) { applyDefaultConfiguration(modifiers) }
    } else {
        HttpClient { applyDefaultConfiguration(modifiers) }
    }

    override suspend fun request(
        url: String,
        builder: HttpRequestBuilder.() -> Unit
    ): HttpResponse {
        val request = buildRequest(url, builder)
        val endPoint = request.url.encodedPath

        logger.d { "Starting ${request.method.value} request to endpoint $endPoint" }

        return executeSafely(request, endPoint) {
            httpClient.request(request)
        }
    }

    override suspend fun prepareRequest(
        url: String,
        builder: HttpRequestBuilder.() -> Unit
    ): HttpStatement {
        val request = buildRequest(url, builder)
        return executeSafely(request) {
            httpClient.prepareRequest(request)
        }
    }

    fun close() = httpClient.close()

    private fun buildRequest(
        url: String,
        builder: HttpRequestBuilder.() -> Unit
    ): HttpRequestBuilder = try {
        HttpRequestBuilder().apply {
            url(url)
            builder()
        }
    } catch (e: Exception) {
        logger.d(e) { "Failed to build request builder for url $url" }
        throw e
    }

    /**
     * Executes a request with consistent timeout/cancellation/error logging.
     */
    private suspend inline fun <T> executeSafely(
        request: HttpRequestBuilder,
        endPoint: String? = null,
        crossinline block: suspend () -> T
    ): T {
        val startTime = TimeSource.Monotonic.markNow()
        val endpointName = endPoint ?: request.url.encodedPath

        return try {
            block().also {
                logger.d { "${request.method.value} request to endpoint $endpointName successfully finished in ${startTime.elapsedNow()}" }
            }
        } catch (e: Exception) {
            val elapsed = startTime.elapsedNow()
            when (e) {
                is HttpRequestTimeoutException -> {
                    logger.d(e) {
                        "${request.method.value} request to endpoint $endpointName timed out after $requestTimeout (elapsed: $elapsed)"
                    }
                    throw e
                }
                is CancellationException -> {
                    logger.d(e) {
                        "${request.method.value} request to endpoint $endpointName was cancelled after $elapsed"
                    }
                    throw e
                }
                else -> {
                    logger.d(e) {
                        "${request.method.value} request to endpoint $endpointName failed with exception ${e.message} after $elapsed"
                    }
                    throw HttpRequestException(e.message ?: "", request, e)
                }
            }
        }
    }

    private fun HeadersBuilder.defaultHeaders() {
        if (supabaseKey.isNotBlank()) {
            append("apikey", supabaseKey)
        }
        append("X-Client-Info", "supabase-kt/${BuildConfig.PROJECT_VERSION}")
        osInformation?.let {
            append("X-Supabase-Client-Platform", it.name)
            it.version?.let { version ->
                append("X-Supabase-Client-Platform-Version", version)
            }
        }
    }

    private fun HttpClientConfig<*>.applyDefaultConfiguration(modifiers: List<HttpClientConfig<*>.() -> Unit>) {
        install(DefaultRequest) {
            headers { defaultHeaders() }
        }
        install(ContentNegotiation) {
            json(supabaseJson)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
        modifiers.forEach { it(this) }
    }
}