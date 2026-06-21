package io.github.jan.supabase.auth.passwords

/**
 * Provides access to password related authentication operations.
 */
interface PasswordsApi {

    /**
     * Sends a password reset email to the user with the specified [email]
     * @param email The email to send the password reset email to
     * @param redirectUrl The redirect url to use
     * @param captchaToken The captcha token to use
     */
    suspend fun resetPasswordForEmail(email: String, redirectUrl: String? = null, captchaToken: String? = null)
}
