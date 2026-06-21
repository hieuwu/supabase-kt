package io.github.jan.supabase.auth.jwt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.github.jan.supabase.auth.AuthImpl
import io.github.jan.supabase.auth.exception.InvalidJwtException
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.safeBody
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

internal class JwtValidator(
    private val auth: AuthImpl
) {
    private val jwksTtl = 10.minutes

    suspend fun getClaims(jwt: String?, options: ClaimsRequestBuilder.() -> Unit): ClaimsResponse {
        val token = jwt ?: auth.currentAccessTokenOrNull() ?: error("No access token found")
        val (claims, rawHeader, rawPayload) = JWTUtils.decodeJwt(token)
        val optionsBuilder = ClaimsRequestBuilder().apply(options)

        if (!optionsBuilder.allowExpired) {
            val exp = claims.claims.exp
            val now = Clock.System.now()
            if (exp == null || exp < now) throw TokenExpiredException()
        }

        val signingKey = if (claims.header.alg == JwtHeader.Algorithm.HS256 || claims.header.kid == null) null else {
            fetchJwk(claims.header.kid, optionsBuilder.jwks)
        }

        if (signingKey == null) {
            auth.retrieveUser(token)
            return claims
        } else {
            val signedData = "$rawHeader.$rawPayload".encodeToByteArray()
            val verified = when (val alg = claims.header.alg) {
                JwtHeader.Algorithm.RS256 -> {
                    val keyDecoder = CryptographyProvider.Default
                        .get(RSA.PKCS1).publicKeyDecoder(SHA256)
                    val derKey = rsaJwkToDer(signingKey)
                    val key = keyDecoder.decodeFromByteString(RSA.PublicKey.Format.DER, ByteString(derKey))
                    key.signatureVerifier().tryVerifySignature(signedData, claims.signature)
                }
                JwtHeader.Algorithm.ES256 -> {
                    val keyDecoder = CryptographyProvider.Default
                        .get(ECDSA).publicKeyDecoder(EC.Curve.P256)
                    val derKey = ecJwkToDer(signingKey)
                    val key = keyDecoder.decodeFromByteString(EC.PublicKey.Format.DER, ByteString(derKey))
                    val derSignature = ecdsaRawToDer(claims.signature)
                    key.signatureVerifier(SHA256, ECDSA.SignatureFormat.DER).tryVerifySignature(signedData, derSignature)
                }
                else -> error("Invalid alg claim $alg")
            }
            if (!verified) throw InvalidJwtException()
            return claims
        }
    }

    private suspend fun fetchJwk(kid: String, jwks: List<JWK>): JWK? {
        jwks.find { it.kid == kid }?.let { return it } // try to fetch in the supplied jwks
        val now = Clock.System.now()
        auth.config.jwkCache.get()?.let { entry -> // try to fetch from local cache
            val jwk = entry.jwks.find { jwk -> jwk.kid == kid }
            if (jwk != null && entry.cachedAt + jwksTtl > now) return jwk
        }
        val response = auth.unauthenticatedApi.get(".well-known/jwks.json").safeBody<JsonObject>() // fetch from the api
        val keysArray = response["keys"]?.jsonArray
        if (!response.containsKey("keys") || keysArray?.isEmpty() == true) return null
        val keys = keysArray?.map { JWK(it.jsonObject) }
        keys?.let {
            auth.config.jwkCache.set(
                JwkCacheEntry(
                    it,
                    now
                )
            )
        }
        val key = keys?.find { it.kid == kid }
        return key
    }
}
