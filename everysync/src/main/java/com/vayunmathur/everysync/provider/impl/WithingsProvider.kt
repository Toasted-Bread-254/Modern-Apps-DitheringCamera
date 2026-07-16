package com.vayunmathur.everysync.provider.impl

import android.content.Context
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.OAuthConfig
import com.vayunmathur.everysync.auth.OAuthManager
import com.vayunmathur.everysync.auth.OAuthTokens
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.remote.WithingsClient
import com.vayunmathur.everysync.sink.HealthSink

/**
 * Withings, OAuth2, the one direct health-cloud integration. Pulls body + vitals
 * measurements into Health Connect (pull-dominant — Withings is upstream).
 */
class WithingsProvider : SyncProvider {
    override val id = "withings"
    override val displayName = "Withings"
    override val iconRes = R.drawable.ic_provider
    override val authType = AuthType.OAUTH
    override val capabilities = setOf(DataType.HEALTH)

    override fun oauthConfig(): OAuthConfig = OAuthConfig.WITHINGS

    override suspend fun resolveAccountName(context: Context, tokens: OAuthTokens): String =
        "Withings (${tokens.accessToken.take(6)})"

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        if (DataType.HEALTH !in config.enabledTypes) return
        val token = OAuthManager.validAccessToken(context, config.accountName, id) ?: return
        val since = SyncState.get(context, config.accountName, "withings_lastupdate")?.toLongOrNull() ?: 0L
        val measurements = WithingsClient(token).getMeasurements(since)
        HealthSink.upsert(context, measurements)
        SyncState.set(context, config.accountName, "withings_lastupdate", (System.currentTimeMillis() / 1000).toString())
    }
}
