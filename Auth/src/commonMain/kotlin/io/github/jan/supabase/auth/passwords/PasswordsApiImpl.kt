package io.github.jan.supabase.auth.passwords

import io.github.jan.supabase.auth.AuthImpl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.auth.putCaptchaToken
import io.github.jan.supabase.auth.putCodeChallenge

internal class PasswordsApiImpl(
    private val auth: AuthImpl
) : PasswordsApi {

    override suspend fun resetPasswordForEmail(
        email: String,
        redirectUrl: String?,
        captchaToken: String?
    ) {
        require(email.isNotBlank()) {
            "Email must not be blank"
        }
        val codeChallenge = auth.preparePKCEIfEnabled()
        val body = buildJsonObject {
            put("email", email)
            captchaToken?.let { putCaptchaToken(it) }
            codeChallenge?.let { putCodeChallenge(it) }
        }.toString()
        auth.publicApi.postJson("recover", body) {
            redirectUrl?.let { url.parameters.append("redirect_to", it) }
        }
    }
}
