package io.github.jan.supacompose.storage

import io.github.jan.supacompose.SupabaseClient
import io.github.jan.supacompose.auth.auth
import io.github.jan.supacompose.exceptions.RestException
import io.github.jan.supacompose.plugins.SupacomposePlugin
import io.github.jan.supacompose.plugins.SupacomposePluginProvider
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface Storage : SupacomposePlugin {

    /**
     * Creates a new bucket in the storage
     * @param name the name of the bucket
     * @param id the id of the bucket
     * @param public whether the bucket should be public or not
     */
    suspend fun createBucket(name: String, id: String, public: Boolean)

    /**
     * Returns all buckets in the storage
     */
    suspend fun getAllBuckets(): List<Bucket>

    /**
     * Retrieves a bucket by its [id]
     */
    suspend fun getBucket(id: String): Bucket?

    /**
     * Changes a bucket's public status to [public]
     */
    suspend fun changePublicStatus(bucketId: String, public: Boolean)

    suspend fun emptyBucket(bucketId: String)

    suspend fun deleteBucket(id: String)

    operator fun get(bucketId: String): BucketApi

    fun from(id: String): BucketApi = get(id)

    class Config

    companion object : SupacomposePluginProvider<Config, Storage> {

        override val key: String = "storage"

        override fun createConfig(init: Config.() -> Unit) = Config().apply(init)
        override fun create(supabaseClient: SupabaseClient, config: Config): Storage {
            return StorageImpl(supabaseClient)
        }

    }

}

internal class StorageImpl(val supabaseClient: SupabaseClient) : Storage {

    override suspend fun getAllBuckets(): List<Bucket> = makeRequest(HttpMethod.Get, "bucket").body()

    override suspend fun getBucket(id: String): Bucket? = makeRequest(HttpMethod.Get, "bucket/$id").body()

    override suspend fun deleteBucket(id: String) {
        makeRequest(HttpMethod.Delete, "bucket/$id")
    }

    override suspend fun createBucket(name: String, id: String, public: Boolean) {
        makeRequest(HttpMethod.Post, "bucket") {
            setBody(buildJsonObject {
                put("name", name)
                put("id", id)
                put("public", public)
            })
        }
    }

    override suspend fun changePublicStatus(bucketId: String, public: Boolean) {
        makeRequest(HttpMethod.Put, "bucket/$bucketId") {
            setBody(buildJsonObject {
                put("public", public)
            })
        }
    }

    override suspend fun emptyBucket(bucketId: String) {
        makeRequest(HttpMethod.Post, "bucket/$bucketId/empty")
    }

    override fun get(bucketId: String): BucketApi = BucketApiImpl(bucketId, this)

    fun path(path: String) = "${supabaseClient.supabaseHttpUrl}/storage/v1/$path"

    suspend inline fun makeRequest(method: HttpMethod, path: String, json: Boolean = true, body: HttpRequestBuilder.() -> Unit = {}) = supabaseClient.httpClient.request(path(path)) {
        this.method = method
        if(json) contentType(ContentType.Application.Json)
        addAuthorization()
        body()
    }.also { 
        if(it.status.value == 400) {
            val error = it.body<JsonObject>()
            throw RestException(error["statusCode"]!!.jsonPrimitive.int, error["error"]!!.jsonPrimitive.content, error["message"]!!.jsonPrimitive.content)
        }
    }

    private fun HttpRequestBuilder.addAuthorization() {
        headers {
            append(HttpHeaders.Authorization, "Bearer ${(supabaseClient.auth.currentSession.value)?.accessToken ?: throw IllegalStateException("Can't use buckets without a user session")}")
        }
    }

}

val SupabaseClient.storage: Storage
    get() = pluginManager.getPlugin(Storage.key)