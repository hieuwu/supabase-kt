import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.auth.resolveAccessToken
import io.github.jan.supabase.testing.createMockedSupabaseClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessTokenTest {

    @Test
    fun testAccessTokenWithJwtToken() {
        runTest {
            val client = createMockedSupabaseClient(
                configuration = {
                    install(Auth) {
                        minimalConfig()
                    }
                }
            )
            client.auth.importAuthToken("myAuth") //this should be ignored as per plugin tokens override the used access token
            assertEquals("myJwtToken", client.resolveAccessToken("myJwtToken"))
        }
    }

    @Test
    fun testAccessTokenWithKeyAsFallback() {
        runTest {
            val client = createMockedSupabaseClient(supabaseKey = "myKey")
            assertEquals("myKey", client.resolveAccessToken())
        }
    }

    @Test
    fun testAccessTokenWithoutKey() {
        runTest {
            val client = createMockedSupabaseClient()
            assertNull(client.resolveAccessToken(keyAsFallback = false))
        }
    }

    @Test
    fun testAccessTokenWithCustomAccessToken() {
        runTest {
            val client = createMockedSupabaseClient(
                configuration = {
                    accessToken = {
                        "myCustomToken"
                    }
                }
            )
            assertEquals("myCustomToken", client.resolveAccessToken())
        }
    }

    @Test
    fun testAccessTokenWithAuth() {
        runTest {
            val client = createMockedSupabaseClient(
                configuration = {
                    install(Auth) {
                        minimalConfig()
                    }
                }
            )
            client.auth.importAuthToken("myAuth")
            assertEquals("myAuth", client.resolveAccessToken())
        }
    }

}