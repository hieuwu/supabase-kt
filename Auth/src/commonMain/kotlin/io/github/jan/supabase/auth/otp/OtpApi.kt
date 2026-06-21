package io.github.jan.supabase.auth.otp

import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.OtpType

/**
 * Provides access to OTP related authentication operations.
 */
interface OtpApi {

    /**
     * Resends an existing signup confirmation email, email change email
     * @param type The email otp type
     * @param email The email to resend the otp to
     * @param captchaToken The captcha token to use
     * @param redirectUrl The redirect Url
     */
    suspend fun resendEmail(type: OtpType.Email, email: String, captchaToken: String? = null, redirectUrl: String? = null)

    /**
     * Resends an existing SMS OTP or phone change OTP.
     * @param type The phone otp type
     * @param phone The phone to resend the otp to
     * @param captchaToken The captcha token to use
     */
    suspend fun resendPhone(type: OtpType.Phone, phone: String, captchaToken: String? = null)

    /**
     * Verifies a email otp
     * @param type The type of the verification
     * @param email The email to verify
     * @param token The token used to verify
     */
    suspend fun verifyEmailOtp(type: OtpType.Email, email: String, token: String, captchaToken: String? = null): OtpVerifyResult

    /**
     * Verifies an email otp token hash received via email
     * @param type The type of the verification
     * @param tokenHash The token hash used to verify
     */
    suspend fun verifyEmailOtp(type: OtpType.Email, tokenHash: String, captchaToken: String? = null): OtpVerifyResult

    /**
     * Verifies a phone/sms otp
     * @param type The type of the verification
     * @param token The otp to verify
     * @param phone The phone number the token was sent to
     */
    suspend fun verifyPhoneOtp(type: OtpType.Phone, phone: String, token: String, captchaToken: String? = null)
}
