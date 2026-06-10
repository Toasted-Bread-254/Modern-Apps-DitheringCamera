package com.vayunmathur.messages.signal.store

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SignalDeviceData(
    val aci: String,
    val pni: String,
    val deviceId: Int,
    val number: String,
    val password: String,
    val aciIdentityKeyPair: String,
    val pniIdentityKeyPair: String,
    val aciRegistrationId: Int,
    val pniRegistrationId: Int,
    val masterKey: String? = null,
    val accountEntropyPool: String? = null,
    val ephemeralBackupKey: String? = null,
    val mediaRootBackupKey: String? = null,
    val accountRecord: String? = null,
) {
    fun basicAuthCreds(): Pair<String, String> {
        return Pair("$aci.$deviceId", password)
    }

    fun isDeviceLoggedIn(): Boolean {
        return aci.isNotBlank() && aci != "00000000-0000-0000-0000-000000000000" && deviceId != 0 && password.isNotEmpty()
    }

    fun clearPassword(): SignalDeviceData {
        return copy(password = "")
    }

    suspend fun clearPasswordAndSave(context: Context) {
        val cleared = copy(password = "")
        cleared.save(context)
    }

    suspend fun save(context: Context) {
        DataStoreUtils.getInstance(context).setString(
            DATA_STORE_KEY,
            Json.encodeToString(serializer(), this),
        )
    }

    companion object {
        private const val DATA_STORE_KEY = "signal_device_data"

        suspend fun load(context: Context): SignalDeviceData? {
            val json = DataStoreUtils.getInstance(context).getString(DATA_STORE_KEY) ?: return null
            if (json.isBlank()) return null
            return runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
        }

        suspend fun clear(context: Context) {
            DataStoreUtils.getInstance(context).setString(DATA_STORE_KEY, "")
        }
    }
}
