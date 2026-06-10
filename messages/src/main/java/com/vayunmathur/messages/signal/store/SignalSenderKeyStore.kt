package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

class SignalSenderKeyStore(private val db: SignalDatabase) : SenderKeyStore {

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        runBlocking {
            db.senderKeyDao().insert(
                SignalSenderKeyEntity(sender.name, sender.deviceId, distributionId.toString(), record.serialize())
            )
        }
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        val entity = runBlocking {
            db.senderKeyDao().get(sender.name, sender.deviceId, distributionId.toString())
        } ?: return null
        return SenderKeyRecord(entity.record)
    }

    fun deleteSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ) {
        runBlocking {
            db.senderKeyDao().delete(sender.name, sender.deviceId, distributionId.toString())
        }
    }

    suspend fun getSenderKeyInfo(groupId: String): SignalSenderKeyInfoEntity? {
        return db.senderKeyInfoDao().get(groupId)
    }

    suspend fun putSenderKeyInfo(groupId: String, info: SignalSenderKeyInfoEntity) {
        db.senderKeyInfoDao().insert(info)
    }

    suspend fun deleteSenderKeyInfo(groupId: String) {
        db.senderKeyInfoDao().delete(groupId)
    }
}
