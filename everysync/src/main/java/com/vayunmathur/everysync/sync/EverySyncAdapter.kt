package com.vayunmathur.everysync.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import com.vayunmathur.everysync.provider.SyncDirection
import kotlinx.coroutines.runBlocking

/**
 * SyncAdapter that bridges the platform sync framework to [SyncEngine]. Both the
 * contacts and calendar authorities point at the same account, so a full account
 * sync (idempotent) runs regardless of which authority triggered it.
 */
class EverySyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        runBlocking {
            SyncEngine.syncAccount(context, account.name, SyncDirection.BOTH)
        }
    }
}

class ContactsSyncService : Service() {
    private val adapter by lazy { EverySyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

class CalendarSyncService : Service() {
    private val adapter by lazy { EverySyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}
