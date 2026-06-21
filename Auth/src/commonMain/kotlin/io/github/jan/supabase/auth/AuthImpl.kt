@file:Suppress("LargeClass")
package io.github.jan.supabase.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.admin.AdminApi
import io.github.jan.supabase.auth.admin.AdminApiImpl
import io.github.jan.supabase.auth.api.authenticatedSupabaseApi
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.identities.IdentitiesApi
import io.github.jan.supabase.auth.jwt.ClaimsRequestBuilder
import io.github.jan.supabase.auth.jwt.ClaimsResponse
import io.github.jan.supabase.auth.mfa.MfaApi
import io.github.jan.supabase.auth.mfa.MfaApiImpl
import io.github.jan.supabase.auth.otp.OtpApi
import io.github.jan.supabase.auth.passwords.PasswordsApi
import io.github.jan.supabase.auth.providers.AuthProvider
import io.github.jan.supabase.auth.providers.ExternalAuthConfigDefaults
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.sso.SsoApi
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.user.UserUpdateBuilder
import io.github.jan.supabase.bodyOrNull
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.exceptions.UnknownRestException
import io.github.jan.supabase.logging.SupabaseLogger
import io.github.jan.supabase.logging.createLogger
import io.github.jan.supabase.logging.d
import io.github.jan.supabase.logging.e
import io.github.jan.supabase.logging.i
import io.github.jan.supabase.network.supabaseApi
import io.github.jan.supabase.putJsonObject
import io.github.jan.supabase.safeBody
import io.github.jan.supabase.supabaseJson
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SESSION_REFRESH_THRESHOLD = 0.8
@Suppress("MagicNumber") // see #631
private val SIGN_OUT_IGNORE_CODES = listOf(401, 403, 404)
@Suppress("MagicNumber") // see #1260
private val NETWORK_ERROR_CODES = listOf(500, 502, 503, 504, 520, 521, 522, 523, 524, 530)

@PublishedApi
internal class AuthImpl(
    override val supabaseClient: SupabaseClient,
    override val config: AuthConfig
) : Auth {

    override val logger: SupabaseLogger = supabaseClient.createLogger(Auth.LOGGING_TAG, config)
    private val _sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
    override val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()
    private val _events = MutableSharedFlow<AuthEvent>(replay = 1)
    override val events: SharedFlow<AuthEvent> = _events.asSharedFlow()
    @Suppress("DEPRECATION")
    override val authScope = config.authScope ?: CoroutineScope((config.coroutineDispatcher ?: supabaseClient.coroutineDispatcher) + SupervisorJob())
    override val sessionManager = config.sessionManager ?: createDefaultSessionManager()
    override val codeVerifierCache = config.codeVerifierCache ?: createDefaultCodeVerifierCache()

    internal val publicApi = supabaseClient.authenticatedSupabaseApi(this, requireSession = false)
    @OptIn(SupabaseInternal::class)
    internal val unauthenticatedApi = supabaseClient.supabaseApi(this)
    @OptIn(SupabaseInternal::class)
    internal val userApi = if(config.requireValidSession) supabaseClient.authenticatedSupabaseApi(this) else publicApi
    override val admin: AdminApi = AdminApiImpl(publicApi)
    override val mfa: MfaApi = MfaApiImpl(userApi.resolve("factors"), this)
    override val otp: OtpApi = io.github.jan.supabase.auth.otp.OtpApiImpl(this)
    override val identities: IdentitiesApi = io.github.jan.supabase.auth.identities.IdentitiesApiImpl(this)
    override val passwords: PasswordsApi = io.github.jan.supabase.auth.passwords.PasswordsApiImpl(this)
    override val sso: SsoApi = io.github.jan.supabase.auth.sso.SsoApiImpl(this)
    private val jwtValidator = io.github.jan.supabase.auth.jwt.JwtValidator(this)
    var sessionJob: Job? = null
    var refreshInformation: SessionRefreshInformation? = null
    override val isAutoRefreshRunning: Boolean
        get() = sessionJob?.isActive == true

    override val serializer = config.serializer ?: supabaseClient.defaultSerializer

    override val apiVersion: Int
        get() = Auth.API_VERSION

    override val pluginKey: String
        get() = Auth.key

    init {
        if (supabaseClient.accessToken != null) error("The Auth plugin is not available when using a custom access token provider. Please uninstall the Auth plugin.")
    }

    override fun init() {
        logger.d { "Initializing Auth plugin..." }
        if (config.autoLoadFromStorage) {
            authScope.launch {
                logger.i {
                    "Loading session from storage..."
                }
                val successful = loadFromStorage(initializing = true)
                if (successful) {
                    logger.i {
                        "Successfully loaded session from storage!"
                    }
                } else {
                    logger.i {
                        "No session found in storage."
                    }
                }
                if(config.autoSetupPlatform) {
                    setupPlatform()
                }
            }
        } else {
            logger.d { "Skipping loading from storage (autoLoadFromStorage is set to false)" }
            if(config.autoSetupPlatform) {
                authScope.launch {
                    setupPlatform()
                }
            }
        }

        logger.d { "Initialized Auth plugin" }
    }

    override suspend fun <C, R, Provider : AuthProvider<C, R>> signInWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ) = provider.login(supabaseClient, {
        importSession(it, source = SessionSource.SignIn(provider))
    }, redirectUrl, config)

    override suspend fun signInAnonymously(data: JsonObject?, captchaToken: String?) {
        val response = publicApi.postJson("signup", buildJsonObject {
            data?.let { put("data", it) }
            captchaToken?.let(::putCaptchaToken)
        })
        val session = response.safeBody<UserSession>()
        importSession(session, source = SessionSource.AnonymousSignIn)
    }

    override suspend fun <C, R, Provider : AuthProvider<C, R>> signUpWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?
    ): R? = provider.signUp(supabaseClient, {
        importSession(it, source = SessionSource.SignUp(provider))
    }, redirectUrl, config)

    override suspend fun updateUser(
        updateCurrentUser: Boolean,
        redirectUrl: String?,
        config: UserUpdateBuilder.() -> Unit
    ): UserInfo {
        val updateBuilder = UserUpdateBuilder(serializer = serializer).apply(config)
        val codeChallenge = preparePKCEIfEnabled()
        val body = buildJsonObject {
            putJsonObject(supabaseJson.encodeToJsonElement(updateBuilder).jsonObject)
            codeChallenge?.let(::putCodeChallenge)
        }.toString()
        val response = userApi.putJson("user", body) {
            redirectUrl?.let { url.parameters.append("redirect_to", it) }
        }
        val userInfo = response.safeBody<UserInfo>()
        if (updateCurrentUser && sessionStatus.value is SessionStatus.Authenticated) {
            val newSession =
                (sessionStatus.value as SessionStatus.Authenticated).session.copy(user = userInfo)
            if (this.config.autoSaveToStorage) {
                sessionManager.saveSession(newSession)
            }
            setSessionStatus(SessionStatus.Authenticated(newSession, SessionSource.UserChanged(newSession)))
        }
        return userInfo
    }

    override suspend fun reauthenticate() {
        userApi.get("reauthenticate")
    }

    override suspend fun signOut(scope: SignOutScope) {
        if (currentSessionOrNull() != null) {
            try {
                userApi.post("logout") {
                    parameter("scope", scope.name.lowercase())
                }
            } catch(e: RestException) {
                if(e.statusCode in SIGN_OUT_IGNORE_CODES) {
                    logger.d { "Received error code ${e.statusCode} while signing out user. This can happen if the user doesn't exist anymore or the JWT is invalid/expired. Proceeding to clean up local data..." }
                } else throw e
            }
            logger.d { "Logged out session in Supabase" }
        } else {
            logger.i { "Skipping session logout as there is no session available. Proceeding to clean up local data..." }
        }
        if (scope != SignOutScope.OTHERS) {
            clearSession()
        }
        logger.d { "Successfully logged out" }
    }

    override suspend fun getClaims(jwt: String?, options: ClaimsRequestBuilder.() -> Unit): ClaimsResponse {
        return jwtValidator.getClaims(jwt, options)
    }

    override suspend fun retrieveUser(jwt: String): UserInfo {
        val response = userApi.get("user") {
            headers["Authorization"] = "Bearer $jwt"
        }
        val body = response.bodyAsText()
        return supabaseJson.decodeFromString(body)
    }

    override suspend fun retrieveUserForCurrentSession(updateSession: Boolean): UserInfo {
        val user = retrieveUser(currentAccessTokenOrNull() ?: error("No session found"))
        if (updateSession) {
            val session = currentSessionOrNull() ?: error("No session found")
            val newStatus = SessionStatus.Authenticated(session.copy(user = user), SessionSource.UserChanged(currentSessionOrNull() ?: error("Session shouldn't be null")))
            setSessionStatus(newStatus)
            if (config.autoSaveToStorage) sessionManager.saveSession(newStatus.session)
        }
        return user
    }

    override suspend fun exchangeCodeForSession(code: String, saveSession: Boolean): UserSession {
        val codeVerifier = codeVerifierCache.loadCodeVerifier()
        require(codeVerifier != null) {
            "No code verifier stored. Make sure to use `getOAuthUrl` for the OAuth Url to prepare the PKCE flow."
        }
        val session = unauthenticatedApi.postJson("token?grant_type=pkce", buildJsonObject {
            put("auth_code", code)
            put("code_verifier", codeVerifier)
        }).safeBody<UserSession>()
        codeVerifierCache.deleteCodeVerifier()
        if (saveSession) {
            importSession(session, source = SessionSource.External)
        }
        return session
    }

    override suspend fun refreshSession(refreshToken: String): UserSession {
        logger.d {
            "Refreshing session"
        }
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
        }
        val response = unauthenticatedApi.postJson("token?grant_type=refresh_token", body)
        return response.safeBody("Auth#refreshSession")
    }

    override suspend fun refreshCurrentSession() {
        val newSession = refreshSession(
            currentSessionOrNull()?.refreshToken
                ?: error("No refresh token found in current session")
        )
        importSession(newSession, source = SessionSource.Refresh(currentSessionOrNull() ?: error("No session found")))
        updateRefreshInformation(null, Clock.System.now())
    }

    override suspend fun importSession(
        session: UserSession,
        autoRefresh: Boolean,
        source: SessionSource
    ) = importSession(session, autoRefresh, source, false)

    suspend fun importSession(
        session: UserSession,
        autoRefresh: Boolean,
        source: SessionSource,
        initializing: Boolean
    ) {
        logger.d { "Importing session $session from $source, auto refresh is set to $autoRefresh." }
        if (!autoRefresh) {
            if (session.refreshToken.isNotBlank() && session.expiresIn != 0L && config.autoSaveToStorage) {
                sessionManager.saveSession(session)
                logger.d { "Session saved to storage (no auto refresh)" }
            }
            setSessionStatus(SessionStatus.Authenticated(session, source))
            logger.d { "Session imported successfully." }
            return
        }
        val thresholdDate = session.expiresAt - session.expiresIn.seconds * (1 - SESSION_REFRESH_THRESHOLD)
        if (thresholdDate <= Clock.System.now()) {
            logger.d { "Session is under the threshold date. Refreshing session..." }
            recreateSessionJob(session, source, false, initializing)
        } else {
            if (config.autoSaveToStorage) {
                sessionManager.saveSession(session)
                logger.d { "Session saved to storage (auto refresh enabled)" }
            }
            setSessionStatus(SessionStatus.Authenticated(session, source))
            logger.d { "Session imported successfully. Starting auto refresh..." }
            recreateSessionJob(session, source, true, initializing)
            logger.d { "Auto refresh started." }
        }
    }

    private suspend fun recreateSessionJob(
        session: UserSession,
        source: SessionSource,
        delay: Boolean,
        initializing: Boolean
    ) {
        sessionJob?.cancel()
        sessionJob = authScope.launch {
            if(delay) delayBeforeExpiry(session)
            tryImportingSession(
                { handleExpiredSession(session, config.alwaysAutoRefresh) },
                { importSession(session, source = source) },
                { updateStatusIfExpired(session, it) }
            )
        }
        if (initializing) sessionJob?.join()
    }

    @Suppress("MagicNumber")
    private suspend fun tryImportingSession(
        importRefreshedSession: suspend () -> Unit,
        retry: suspend () -> Unit,
        updateStatus: suspend (RefreshFailureCause) -> Unit
    ) {
        try {
            importRefreshedSession()
        } catch (e: RestException) {
            if (e.statusCode in NETWORK_ERROR_CODES) {
                logger.e(e) { "Couldn't refresh session due to an internal server error. Retrying in ${config.retryDelay} (Status code ${e.statusCode})..." }
                updateStatus(RefreshFailureCause.InternalServerError(e))
                delay(config.retryDelay)
                retry()
            } else {
                logger.e(e) { "Couldn't refresh session. The refresh token may have been revoked. Clearing session (Status code ${e.statusCode})... " }
                clearSession()
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.d(e) { "Couldn't reach Supabase. Either the address doesn't exist or the network might not be on. Retrying in ${config.retryDelay}..." }
            updateStatus(RefreshFailureCause.NetworkError(e))
            delay(config.retryDelay)
            retry()
        }
    }

    private fun updateStatusIfExpired(session: UserSession, reason: RefreshFailureCause) {
        if (session.expiresAt <= Clock.System.now()) {
            logger.d { "Session expired while trying to refresh the session. Updating status..." }
            setSessionStatus(SessionStatus.RefreshFailure(reason))
        }
        emitEvent(AuthEvent.RefreshFailure(reason))
    }

    private suspend fun delayBeforeExpiry(session: UserSession) {
        val now = Clock.System.now()
        val timeAtBeginningOfSession = session.expiresAt - session.expiresIn.seconds

        // 80% of the way to session.expiresAt
        val targetRefreshTime = timeAtBeginningOfSession + (session.expiresIn.seconds * SESSION_REFRESH_THRESHOLD)

        val delayDuration = targetRefreshTime - now
        updateRefreshInformation(targetRefreshTime, null)
        logger.d {
            "Refreshing session in $delayDuration."
        }
        // if the delayDuration is negative, delay() will not delay
        delay(delayDuration)
    }

    private fun updateRefreshInformation(
        refreshingAt: Instant?,
        lastRefreshedAt: Instant?,
    ) {
        refreshInformation = refreshInformation?.copy(refreshingAt = refreshingAt ?: refreshInformation?.refreshingAt, lastRefreshedAt = lastRefreshedAt ?: refreshInformation?.lastRefreshedAt)
            ?: SessionRefreshInformation(
                Clock.System.now(),
                lastRefreshedAt,
                refreshingAt
            )
    }

    private suspend fun handleExpiredSession(session: UserSession, autoRefresh: Boolean = true) {
        logger.d {
            "Session expired. Refreshing session..."
        }
        val newSession = refreshSession(session.refreshToken)
        importSession(newSession, autoRefresh, SessionSource.Refresh(session))
    }

    override suspend fun startAutoRefreshForCurrentSession() =
        importSession(
            currentSessionOrNull() ?: error("No session found"),
            true,
            (sessionStatus.value as SessionStatus.Authenticated).source
        )

    override fun stopAutoRefreshForCurrentSession() {
        logger.d { "Stopping auto refresh for current session" }
        sessionJob?.cancel()
        sessionJob = null
    }

    @SupabaseInternal
    override fun autoRefreshInformation(): SessionRefreshInformation? = refreshInformation

    override suspend fun loadFromStorage(autoRefresh: Boolean): Boolean = loadFromStorage(autoRefresh, false)

    suspend fun loadFromStorage(autoRefresh: Boolean = config.alwaysAutoRefresh, initializing: Boolean): Boolean {
        val session = try { sessionManager.loadSession() } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Failed to load session" }
            null
        }
        session?.let {
            importSession(it, autoRefresh, SessionSource.Storage, initializing)
        }
        return session != null
    }

    override suspend fun close() {
        authScope.cancel()
    }

    override suspend fun parseErrorResponse(response: HttpResponse): RestException {
        val errorBody =
            supabaseClient.bodyOrNull<GoTrueErrorResponse>(response) ?: GoTrueErrorResponse("Unknown error", "")
        checkErrorCodes(errorBody, response)?.let { return it }
        return when (response.status) {
            HttpStatusCode.Unauthorized -> UnauthorizedRestException(
                errorBody.error ?: "Unauthorized",
                response,
                errorBody.description
            )
            HttpStatusCode.BadRequest -> BadRequestRestException(
                errorBody.error ?: "Bad Request",
                response,
                errorBody.description
            )
            HttpStatusCode.UnprocessableEntity -> BadRequestRestException(
                errorBody.error ?: "Unprocessable Entity",
                response,
                errorBody.description
            )
            else -> UnknownRestException(errorBody.error ?: "Unknown Error", response)
        }
    }

    private fun checkErrorCodes(error: GoTrueErrorResponse, response: HttpResponse): RestException? {
        return when (error.error) {
            AuthWeakPasswordException.CODE -> AuthWeakPasswordException(error.description, response, error.weakPassword?.reasons ?: emptyList())
            AuthSessionMissingException.CODE -> {
                authScope.launch {
                    logger.e { "Received session not found api error. Clearing session..." }
                    clearSession()
                }
                AuthSessionMissingException(response)
            }
            else -> {
                error.error?.let { AuthRestException(it, error.description, response) }
            }
        }
    }

    @OptIn(SupabaseExperimental::class)
    override fun getOAuthUrl(
        provider: OAuthProvider,
        redirectUrl: String?,
        url: String,
        additionalConfig: ExternalAuthConfigDefaults.() -> Unit
    ): String {
        val config = ExternalAuthConfigDefaults().apply(additionalConfig)
        val codeChallenge = preparePKCEIfEnabled()
        codeChallenge?.let {
            config.queryParams["code_challenge"] = it
            config.queryParams["code_challenge_method"] = PKCEConstants.CHALLENGE_METHOD
        }
        return resolveUrl(buildString {
            append("$url?provider=${provider.name}&redirect_to=${redirectUrl?.encodeURLParameter()}")
            if (config.scopes.isNotEmpty()) append("&scopes=${config.scopes.joinToString("+")}")
            if (config.queryParams.isNotEmpty()) {
                for ((key, value) in config.queryParams) {
                    append("&$key=${value.encodeURLParameter()}")
                }
            }
        })
    }

    override suspend fun clearSession() {
        codeVerifierCache.deleteCodeVerifier()
        sessionManager.deleteSession()
        setSessionStatus(SessionStatus.NotAuthenticated(true))
        stopAutoRefreshForCurrentSession()
    }

    override suspend fun awaitInitialization() {
        sessionStatus.first { it !is SessionStatus.Initializing }
    }

    override fun setSessionStatus(status: SessionStatus) {
        logger.d { "Setting session status to $status" }
        _sessionStatus.value = status
    }

    override fun emitEvent(event: AuthEvent) {
        logger.d { "Emitting event $event" }
        _events.tryEmit(event)
    }

    /**
     * Prepares PKCE if enabled and returns the code challenge.
     */
    internal fun preparePKCEIfEnabled(): String? {
        if (this.config.flowType != FlowType.PKCE) return null
        val codeVerifier = generateCodeVerifier()
        authScope.launch {
            supabaseClient.auth.codeVerifierCache.saveCodeVerifier(codeVerifier)
        }
        return generateCodeChallenge(codeVerifier)
    }

}

@SupabaseInternal
expect suspend fun Auth.setupPlatform()

@SupabaseInternal
expect fun Auth.createDefaultSessionManager(): SessionManager

@SupabaseInternal
expect fun Auth.createDefaultCodeVerifierCache(): CodeVerifierCache