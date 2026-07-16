package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.provider.DataType
import kotlinx.serialization.Serializable

/**
 * A configured EverySync account. Persisted as JSON in [AccountStore]; the
 * matching [android.accounts.Account] (type `com.vayunmathur.everysync`) is
 * registered with AccountManager so other apps see synced data under it.
 *
 * Secrets (OAuth tokens, DAV passwords) live in the encrypted [TokenStore],
 * keyed by [accountName] — never in this object.
 */
@Serializable
data class AccountConfig(
    /** Unique account name, also the AccountManager account name. */
    val accountName: String,
    val providerId: String,
    /** For DAV providers: discovered/base principal URL. */
    val davBaseUrl: String? = null,
    val davUsername: String? = null,
    /** Data types the user enabled for this account. */
    val enabledTypes: Set<DataType> = emptySet(),
    /** Per-account sync interval override in minutes (null = use global). */
    val intervalMinutes: Int? = null,
    val lastSyncEpochMs: Long = 0L,
    val lastSyncError: String? = null,
)
