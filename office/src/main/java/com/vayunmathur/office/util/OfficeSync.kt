package com.vayunmathur.office.util

import android.content.Context
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeIdentity
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.io.encoding.Base64

/**
 * End-to-end-encrypted cloud sync + sharing client for the Office app, talking to the `/office`
 * endpoints on the shared server. All key generation / storage / crypto comes from
 * `:library:e2ee-p2p`, so Office and FindFamily use identical techniques.
 *
 * Model (last-writer-wins snapshot sync — not real-time CRDT):
 *  - each device has an [E2eeIdentity] registered in the server key directory under a random id;
 *  - a document is encrypted with a random AES content key ([newDocumentKey]); the ciphertext
 *    snapshot is pushed to the document's append log;
 *  - sharing wraps the content key to a recipient's public key (fetched by id) so only they can
 *    unwrap it. The server only ever stores ciphertext + wrapped keys — it can't read documents.
 */
object OfficeSync {
    private const val URL = "https://findfamily.cc/office"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var identity: E2eeIdentity
    var deviceId: String = ""
        private set
    private var initialized = false

    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override fun getBytes(name: String): ByteArray? = ds.getByteArray(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    /** Loads/creates this device's identity + id and registers the public key in the directory. */
    suspend fun init(context: Context) {
        if (initialized) return
        val ds = DataStoreUtils.getInstance(context)
        identity = E2eeIdentity.loadOrCreate(DataStoreKeyStore(ds), "officePublicKey", "officePrivateKey")
        var id = ds.getString("officeDeviceId")
        if (id == null) {
            id = UUID.randomUUID().toString()
            ds.setString("officeDeviceId", id, true)
        }
        deviceId = ds.getString("officeDeviceId") ?: id
        initialized = true
        register()
    }

    private suspend fun register(): Boolean =
        post("/register", RegisterReq(deviceId, Base64.encode(identity.publicKeyPem)))

    /** A fresh random AES content key for a new document. */
    fun newDocumentKey(): ByteArray = E2ee.newContentKey()

    /** Encrypts and pushes a full document snapshot; returns the new log length, or null on failure. */
    suspend fun pushSnapshot(docId: String, docKey: ByteArray, plaintext: ByteArray): Int? {
        val ct = Base64.encode(E2ee.aesEncrypt(docKey, plaintext))
        val r = raw("/doc/push", PushReq(docId, listOf(ct))) ?: return null
        if (r.status != 200) return null
        return runCatching { json.decodeFromString<SeqResp>(r.body).seq }.getOrNull()
    }

    /** Pulls and decrypts the latest snapshot at/after [since]; null plaintext means nothing new. */
    suspend fun pullLatest(docId: String, docKey: ByteArray, since: Int = 0): PullResult? {
        val r = raw("/doc/pull", PullReq(docId, since)) ?: return null
        if (r.status != 200) return null
        val resp = runCatching { json.decodeFromString<PullResp>(r.body) }.getOrNull() ?: return null
        val latest = resp.updates.lastOrNull() ?: return PullResult(null, resp.seq)
        val plain = runCatching { E2ee.aesDecrypt(docKey, Base64.decode(latest)) }.getOrNull()
        return PullResult(plain, resp.seq)
    }

    /** Shares a document with another device by id: wraps [docKey] to their public key. */
    suspend fun shareWith(docId: String, recipientId: String, docKey: ByteArray, rosterEntry: String? = null): Boolean {
        val peerPem = getKey(recipientId) ?: return false
        val wrapped = Base64.encode(E2ee.encryptTo(peerPem, docKey))
        return post("/doc/share", ShareReq(docId, recipientId, wrapped, rosterEntry))
    }

    /** Fetches and unwraps this device's copy of a shared document's content key, if any. */
    suspend fun fetchDocumentKey(docId: String): ByteArray? {
        val r = raw("/doc/keys", DocKeysReq(docId, deviceId)) ?: return null
        if (r.status != 200) return null
        val resp = runCatching { json.decodeFromString<WrappedKeysResp>(r.body) }.getOrNull() ?: return null
        val blob = resp.wrappedKeys.lastOrNull()?.blob ?: return null
        return runCatching { identity.decrypt(Base64.decode(blob)) }.getOrNull()
    }

    /** Fetches a peer's public key (PEM bytes) by id from the directory. */
    suspend fun getKey(id: String): ByteArray? {
        val r = raw("/getkey", IdReq(id)) ?: return null
        return if (r.status == 200) Base64.decode(r.body) else null
    }

    /**
     * Verification security code between this device and a peer (given the peer's PEM public key).
     * Identical on both devices; compare out-of-band to confirm no key substitution.
     */
    suspend fun securityCode(peerPublicKeyPem: ByteArray): String? =
        runCatching { E2ee.securityCode(identity.publicKeyPem, peerPublicKeyPem) }.getOrNull()

    // --- HTTP helpers ---

    private suspend inline fun <reified T> post(path: String, body: T): Boolean {
        val r = raw(path, body) ?: return false
        return r.status in 200..299
    }

    private suspend inline fun <reified T> raw(path: String, body: T) =
        try {
            NetworkClient.performRequest(
                url = "$URL$path",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(body),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class PushReq(val docId: String, val updates: List<String>)
    @Serializable private data class PullReq(val docId: String, val since: Int)
    @Serializable private data class ShareReq(val docId: String, val recipientId: String, val wrappedKey: String, val rosterEntry: String?)
    @Serializable private data class DocKeysReq(val docId: String, val id: String)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable private data class PullResp(val updates: List<String> = emptyList(), val seq: Int = 0)
    @Serializable private data class WrappedKeysResp(val wrappedKeys: List<WrappedKey> = emptyList())
    @Serializable private data class WrappedKey(val recipient: String = "", val blob: String = "")

    /** Result of [pullLatest]: decrypted [plaintext] (null if nothing new) and the current [seq]. */
    class PullResult(val plaintext: ByteArray?, val seq: Int)
}
