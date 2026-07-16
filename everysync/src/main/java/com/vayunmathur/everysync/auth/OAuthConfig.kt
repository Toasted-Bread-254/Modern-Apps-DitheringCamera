package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.BuildConfig

/**
 * OAuth2 endpoints + scopes for a provider. Client IDs come from [BuildConfig]
 * (public PKCE clients — no secret shipped, except Withings which requires one
 * per its API and is passed at token exchange).
 */
data class OAuthConfig(
    val authEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    /** Withings' API is a confidential client; empty for pure PKCE providers. */
    val clientSecret: String = "",
    /** Extra query params appended to the authorization request. */
    val extraAuthParams: Map<String, String> = emptyMap(),
) {
    companion object {
        const val REDIRECT_URI = "com.vayunmathur.everysync:/oauth"

        val GOOGLE = OAuthConfig(
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            scopes = listOf(
                "https://www.googleapis.com/auth/contacts",
                "https://www.googleapis.com/auth/calendar",
            ),
            // Force a refresh token to be returned on first consent.
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        )

        val WITHINGS = OAuthConfig(
            authEndpoint = "https://account.withings.com/oauth2_user/authorize2",
            tokenEndpoint = "https://wbsapi.withings.net/v2/oauth2",
            clientId = BuildConfig.WITHINGS_OAUTH_CLIENT_ID,
            clientSecret = BuildConfig.WITHINGS_OAUTH_CLIENT_SECRET,
            scopes = listOf("user.metrics", "user.activity"),
        )
    }
}
