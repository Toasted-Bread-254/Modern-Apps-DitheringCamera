package com.vayunmathur.everysync.sync

import android.content.Context
import android.util.Log
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.provider.SyncDirection

/**
 * Runs a sync pass for an account: resolve its provider, pull remote changes into
 * the on-device sinks and (two-way) push local DIRTY/DELETED rows back, then
 * record the outcome on the [com.vayunmathur.everysync.auth.AccountConfig].
 */
object SyncEngine {
    private const val TAG = "SyncEngine"

    suspend fun syncAccount(context: Context, accountName: String, direction: SyncDirection = SyncDirection.BOTH) {
        val store = AccountStore.getInstance(context)
        val config = store.get(accountName) ?: return
        val provider = ProviderRegistry.get(config.providerId) ?: return
        try {
            provider.sync(context, config, direction)
            store.upsert(config.copy(lastSyncEpochMs = System.currentTimeMillis(), lastSyncError = null))
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for $accountName", e)
            store.upsert(config.copy(lastSyncEpochMs = System.currentTimeMillis(), lastSyncError = e.message ?: "Sync failed"))
        }
    }

    suspend fun syncAll(context: Context, direction: SyncDirection = SyncDirection.BOTH) {
        AccountStore.getInstance(context).getAll().forEach { syncAccount(context, it.accountName, direction) }
    }
}
