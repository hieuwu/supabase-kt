package io.github.jan.supabase.auth.identities

import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.ExternalAuthConfigDefaults
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.providers.builtin.IDToken

/**
 * Provides access to identity related authentication operations.
 */
interface IdentitiesApi {

    /**
     * Links an OAuth Identity to an existing user.
     * @param provider The OAuth provider
     * @param redirectUrl The redirect url to use
     * @param config Extra configuration
     * @return The OAuth url to open in the browser if automaticallyOpenUrl is false, otherwise null.
     */
    suspend fun linkIdentity(
        provider: OAuthProvider,
        redirectUrl: String? = null,
        config: ExternalAuthConfigDefaults.() -> Unit = {}
    ): String?

    /**
     * Links an identity to the current user using an ID token.
     * @param provider One of the [IDTokenProvider] providers.
     * @param idToken The ID token to use
     * @param config Extra configuration
     */
    suspend fun linkIdentityWithIdToken(
        provider: IDTokenProvider,
        idToken: String,
        config: (IDToken.Config).() -> Unit = {}
    )

    /**
     * Unlinks an OAuth Identity from an existing user.
     * @param identityId The id of the OAuth identity
     * @param updateLocalUser Whether to delete the identity from the local user or not
     */
    suspend fun unlinkIdentity(
        identityId: String,
        updateLocalUser: Boolean = true
    )
}
