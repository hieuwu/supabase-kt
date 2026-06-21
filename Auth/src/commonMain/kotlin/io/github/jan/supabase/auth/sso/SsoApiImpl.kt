package io.github.jan.supabase.auth.sso

import io.github.jan.supabase.auth.AuthImpl
import io.github.jan.supabase.auth.providers.builtin.SSO
import io.github.jan.supabase.putJsonObject
import io.github.jan.supabase.safeBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.auth.putCaptchaToken
import io.github.jan.supabase.auth.putCodeChallenge

internal class SsoApiImpl(
    private val auth: AuthImpl
) : SsoApi {

    override suspend fun retrieveSSOUrl(
        redirectUrl: String?,
        config: SSO.Config.() -> Unit
    ): SSO.Result {
        val createdConfig = SSO.Config().apply(config)

        require((createdConfig.domain != null && createdConfig.domain!!.isNotBlank()) || (createdConfig.providerId != null && createdConfig.providerId!!.isNotBlank())) {
            "Either domain or providerId must be set"
        }

        require(createdConfig.domain == null || createdConfig.providerId == null) {
            "Either domain or providerId must be set, not both"
        }

        val codeChallenge: String? = auth.preparePKCEIfEnabled()
        return auth.publicApi.postJson("sso", buildJsonObject {
            redirectUrl?.let { put("redirect_to", it) }
            createdConfig.captchaToken?.let { putCaptchaToken(it) }
            codeChallenge?.let { putCodeChallenge(it) }
            createdConfig.domain?.let {
                put("domain", it)
            }
            createdConfig.providerId?.let {
                put("provider_id", it)
            }
        }).safeBody()
    }
}
