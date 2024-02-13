package io.github.jan.supabase.gotrue

import io.github.jan.supabase.plugins.CustomSerializationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The configuration for [Auth]
 */
actual class AuthConfig : CustomSerializationConfig, AuthConfigDefaults() {

    /**
     * The port the web server is running on, when logging in with OAuth. Defaults to 0 (random port).
     */
    var httpPort: Int = 0

    /**
     * The timeout for the web server, when logging in with OAuth. Defaults to 1 minutes.
     */
    var timeout: Duration = 1.minutes

    var htmlTitle: String = "Supabase Auth"

    /**
     * The html content of the redirect page, when logging in with OAuth. Defaults to a page with a title, text and icon.
     */
    var redirectHtml: String = HTML.redirectPage("https://supabase.com/brand-assets/supabase-logo-icon.png", "Supabase Auth", "Logged in. You may continue in your app")

    //maybe some map thing for deeplinks?

}