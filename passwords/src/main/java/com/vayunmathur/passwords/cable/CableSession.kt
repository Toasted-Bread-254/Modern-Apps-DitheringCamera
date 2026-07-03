package com.vayunmathur.passwords.cable

import android.util.Log

/**
 * Drives one caBLE v2 authenticator session end-to-end:
 *
 *  1. derive keys (EID key, tunnel id, PSK) from the QR secret;
 *  2. open the tunnel "new" endpoint and learn the routing id;
 *  3. BLE-advertise the encrypted EID so the browser confirms proximity + finds the tunnel;
 *  4. run the Noise `KNpsk0` handshake (responder), sending the getInfo payload;
 *  5. relay CTAP2 traffic over the encrypted channel until a getAssertion completes or we stop.
 *
 * The heavy lifting lives in the phase modules ([CableKeys], [CableTunnel], [CableAdvertiser],
 * [CableEid], [NoiseResponder], [CtapProcessor]); this class only sequences them and owns the
 * per-session protocol state.
 *
 * ⚠️ UNVERIFIED transport details (flagged inline): whether getInfo rides on the handshake payload
 * vs. a first transport message, and the caBLE post-handshake framing (message-type prefix +
 * length padding). Confirm against Chromium before relying on interop.
 */
class CableSession(
    private val qr: CableQrData,
    private val ctapProcessor: CtapProcessor,
    private val advertiser: CableAdvertiser,
    private val aaguid: ByteArray,
    private val onStatus: (String) -> Unit = {},
) {
    private var tunnel: CableTunnel? = null

    /** Runs the session to completion. Returns true if a CTAP command was answered successfully. */
    suspend fun run(): Boolean {
        try {
            val eidKey = CableKeys.eidKey(qr.qrSecret)
            val tunnelId = CableKeys.tunnelId(qr.qrSecret)
            val psk = CableKeys.psk(qr.qrSecret)

            val domainId = TunnelDomains.DEFAULT_ID
            val domain = TunnelDomains.decode(domainId)
            onStatus("Connecting to tunnel $domain")
            val tun = CableTunnel.connectNew(domain, tunnelId).also { tunnel = it }

            val routingId = tun.routingId ?: ByteArray(CableEid.ROUTING_ID_SIZE)
            val advert = CableEid.encrypt(
                CableEid.buildPlaintext(CableEid.randomNonce(), routingId, domainId),
                eidKey,
            )
            onStatus("Advertising over Bluetooth")
            advertiser.start(advert) { ok ->
                if (!ok) Log.w(TAG, "BLE advertising unavailable; proximity check may fail")
            }

            onStatus("Performing secure handshake")
            val responder = NoiseResponder(P256.decodePoint(qr.peerPublicKey), psk)
            responder.readMessage1(tun.receive())
            val getInfo = CtapGetInfoResponse(aaguid = aaguid).encode()
            val (msg2, keys) = responder.writeMessage2(getInfo)
            tun.send(msg2)

            onStatus("Waiting for sign-in request")
            return ctapLoop(tun, keys)
        } catch (e: Exception) {
            Log.e(TAG, "caBLE session error", e)
            onStatus("Sign-in failed")
            return false
        } finally {
            advertiser.stop()
            tunnel?.close()
        }
    }

    private suspend fun ctapLoop(tun: CableTunnel, keys: NoiseResponder.TransportKeys): Boolean {
        // v1: answer commands until a getAssertion succeeds, then stop.
        while (true) {
            val ciphertext = tun.receive()
            val command = keys.decrypt.decrypt(ciphertext)
            val response = ctapProcessor.process(command)
            tun.send(keys.encrypt.encrypt(response))

            val isAssertion = command.isNotEmpty() &&
                (command[0].toInt() and 0xFF) == Ctap.CMD_GET_ASSERTION
            if (isAssertion && response.isNotEmpty() && response[0].toInt() == Ctap.OK) {
                onStatus("Sign-in complete")
                return true
            }
        }
    }

    companion object {
        private const val TAG = "CableSession"
    }
}
