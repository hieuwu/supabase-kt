package io.github.jan.supabase.auth.otp

import io.github.jan.supabase.auth.AuthImpl
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.bodyOrNull
import io.github.jan.supabase.putJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.statement.bodyAsText
import io.github.jan.supabase.logging.d
import io.github.jan.supabase.auth.putCaptchaToken

internal class OtpApiImpl(
    private val auth: AuthImpl
) : OtpApi {

    private suspend fun resend(type: String,  redirectUrl: String? = null, body: JsonObjectBuilder.() -> Unit) {
        auth.userApi.postJson("resend", buildJsonObject {
            put("type", type)
            putJsonObject(buildJsonObject(body))
        }) {
            redirectUrl?.let { url.parameters["redirect_to"] = it }
        }
    }

    override suspend fun resendEmail(type: OtpType.Email, email: String, captchaToken: String?, redirectUrl: String?) =
        resend(type = type.type, redirectUrl = redirectUrl) {
            put("email", email)
            captchaToken?.let { putCaptchaToken(it) }
        }

    override suspend fun resendPhone(
        type: OtpType.Phone,
        phone: String,
        captchaToken: String?
    ) = resend(type.type) {
        put("phone", phone)
        captchaToken?.let { putCaptchaToken(it) }
    }

    private suspend fun verify(
        type: String,
        token: String?,
        captchaToken: String?,
        additionalData: JsonObjectBuilder.() -> Unit
    ): OtpVerifyResult {
        val body = buildJsonObject {
            put("type", type)
            token?.let { put("token", it) }
            captchaToken?.let { putCaptchaToken(it) }
            additionalData()
        }
        val response = auth.publicApi.postJson("verify", body)
        val session = auth.supabaseClient.bodyOrNull<io.github.jan.supabase.auth.user.UserSession>(response)
        if(session == null) {
            auth.logger.d { "Received `verifyOtp` response without session: ${response.bodyAsText()}. This may occur if changing the email with 'Secure email change' enabled" }
            return OtpVerifyResult.VerifiedNoSession
        }
        auth.importSession(session, source = SessionSource.SignIn(OTP))
        return OtpVerifyResult.Authenticated(session)
    }

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        email: String,
        token: String,
        captchaToken: String?
    ) = verify(type.type, token, captchaToken) {
        put("email", email)
    }

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        tokenHash: String,
        captchaToken: String?
    ) = verify(type.type, null, captchaToken) {
        put("token_hash", tokenHash)
    }

    override suspend fun verifyPhoneOtp(
        type: OtpType.Phone,
        phone: String,
        token: String,
        captchaToken: String?
    )  {
        verify(type.type, token, captchaToken) {
            put("phone", phone)
        }
    }
}
