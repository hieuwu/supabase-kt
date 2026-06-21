package io.github.jan.supabase.auth.identities

import io.github.jan.supabase.auth.AuthImpl
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.ExternalAuthConfigDefaults
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.startExternalAuth
import io.github.jan.supabase.safeBody
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class IdentitiesApiImpl(
    private val auth: AuthImpl
) : IdentitiesApi {

    override suspend fun linkIdentity(
        provider: OAuthProvider,
        redirectUrl: String?,
        config: ExternalAuthConfigDefaults.() -> Unit
    ): String? {
        val automaticallyOpen = ExternalAuthConfigDefaults().apply(config).automaticallyOpenUrl
        val fetchUrl: suspend (String?) -> String = { redirectTo: String? ->
            val url = auth.getOAuthUrl(provider, redirectTo, "user/identities/authorize", config)
            val response = auth.userApi.rawRequest(url) {
                method = HttpMethod.Get
                parameter("skip_http_redirect", true)
            }
            response.safeBody<JsonObject>()["url"]?.jsonPrimitive?.contentOrNull ?: error("No URL found in response")
        }
        if(!automaticallyOpen) {
            return fetchUrl(redirectUrl ?: "")
        }
        auth.startExternalAuth(
            redirectUrl = redirectUrl,
            getUrl = {
                fetchUrl(it)
            },
            onSessionSuccess = {
                auth.importSession(it, source = SessionSource.UserIdentitiesChanged(it))
            }
        )
        return null
    }

    override suspend fun linkIdentityWithIdToken(
        provider: IDTokenProvider,
        idToken: String,
        config: (IDToken.Config).() -> Unit
    ) {
        val body = IDToken.Config(idToken = idToken, provider = provider, linkIdentity = true).apply(config)
        val result = auth.userApi.postJson("token?grant_type=id_token", body)
        auth.importSession(result.safeBody(), source = SessionSource.UserIdentitiesChanged(result.safeBody()))
    }

    override suspend fun unlinkIdentity(identityId: String, updateLocalUser: Boolean) {
        auth.userApi.delete("user/identities/$identityId")
        if (updateLocalUser) {
            val session = auth.currentSessionOrNull() ?: return
            val newUser = session.user?.copy(identities = session.user.identities?.filter { it.identityId != identityId })
            val newSession = session.copy(user = newUser)
            auth.setSessionStatus(SessionStatus.Authenticated(newSession, SessionSource.UserIdentitiesChanged(session)))
        }
    }
}
