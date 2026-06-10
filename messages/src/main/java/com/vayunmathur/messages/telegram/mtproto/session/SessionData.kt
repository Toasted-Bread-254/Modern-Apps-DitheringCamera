package com.vayunmathur.messages.telegram.mtproto.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
data class SessionConfig(
    @SerialName("BlockedMode") val blockedMode: Boolean = false,
    @SerialName("ForceTryIpv6") val forceTryIpv6: Boolean = false,
    @SerialName("Date") val date: Int = 0,
    @SerialName("Expires") val expires: Int = 0,
    @SerialName("TestMode") val testMode: Boolean = false,
    @SerialName("ThisDC") val thisDC: Int = 0,
    @SerialName("DCTxtDomainName") val dcTxtDomainName: String = "",
    @SerialName("TmpSessions") val tmpSessions: Int = 0,
    @SerialName("WebfileDCID") val webfileDCID: Int = 0,
)

@Serializable
private data class VersionedSession(
    @SerialName("Version") val version: Int = 0,
    @SerialName("Data") val data: SessionData,
)

@Serializable
data class SessionData(
    @SerialName("DC") val dc: Int,
    @SerialName("Addr") val address: String,
    @SerialName("AuthKey") val authKey: String,   // Base64-encoded 256-byte key
    @SerialName("AuthKeyID") val authKeyId: String, // Base64-encoded 8 bytes
    @SerialName("Salt") val salt: Long,
    @SerialName("Config") val config: SessionConfig = SessionConfig(),
) {
    fun toJson(): String = lenientJson.encodeToString(
        VersionedSession.serializer(),
        VersionedSession(version = LATEST_VERSION, data = this)
    )

    companion object {
        private const val LATEST_VERSION = 1

        fun fromJson(json: String): SessionData {
            val versioned = lenientJson.decodeFromString(VersionedSession.serializer(), json)
            require(versioned.version == LATEST_VERSION) {
                "Session version mismatch (${versioned.version} != $LATEST_VERSION)"
            }
            return versioned.data
        }
    }
}
