package io.github.jan.supabase.auth.sso

import io.github.jan.supabase.auth.providers.builtin.SSO

/**
 * Provides access to Single Sign-On related authentication operations.
 */
interface SsoApi {

    /**
     * Retrieves the sso url for the given [config]
     * @param redirectUrl The redirect url to use
     * @param config The configuration to use
     */
    suspend fun retrieveSSOUrl(redirectUrl: String? = null, config: SSO.Config.() -> Unit): SSO.Result
}
