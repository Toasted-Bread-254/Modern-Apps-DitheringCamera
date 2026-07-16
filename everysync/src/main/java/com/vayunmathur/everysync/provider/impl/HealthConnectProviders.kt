package com.vayunmathur.everysync.provider.impl

import android.content.Context
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.remote.HealthConnectBridge

/**
 * Health-Connect-backed provider (Samsung Health / Google Health). These have no
 * open cloud REST API — their data is written into Health Connect on-device — so
 * "sync" walks the Health Connect changes feed to confirm/track records. Clearly
 * labelled in the UI as being serviced through Health Connect.
 */
abstract class HealthConnectProvider(
    override val id: String,
    override val displayName: String,
) : SyncProvider {
    override val iconRes = R.drawable.ic_provider
    override val authType = AuthType.HEALTH_CONNECT
    override val capabilities = setOf(DataType.HEALTH)
    override val viaHealthConnect = true

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        if (DataType.HEALTH !in config.enabledTypes) return
        val token = SyncState.get(context, config.accountName, "hc_changes_token")
        val result = HealthConnectBridge.pullChanges(context, token)
        SyncState.set(context, config.accountName, "hc_changes_token", result.nextToken)
    }
}

class SamsungHealthProvider : HealthConnectProvider("samsung_health", "Samsung Health")

class GoogleHealthProvider : HealthConnectProvider("google_health", "Google Health")
