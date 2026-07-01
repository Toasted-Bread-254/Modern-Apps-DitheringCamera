package com.vayunmathur.messages.signal.store

class SignalRecipientStore(private val db: SignalDatabase) {

    suspend fun getRecipient(aci: String): SignalRecipientEntity? {
        return db.recipientDao().get(aci)
    }

    suspend fun storeRecipient(entity: SignalRecipientEntity) {
        db.recipientDao().insert(entity)
    }

    suspend fun getByE164(phone: String): SignalRecipientEntity? {
        return db.recipientDao().getByE164(phone)
    }

    suspend fun getByPni(pni: String): SignalRecipientEntity? {
        return db.recipientDao().getByPni(pni)
    }

    suspend fun search(query: String): List<SignalRecipientEntity> {
        return db.recipientDao().search(query)
    }

    suspend fun getAllRecipients(): List<SignalRecipientEntity> {
        return db.recipientDao().getAll()
    }

    suspend fun getAllContacts(): List<SignalRecipientEntity> {
        return db.recipientDao().getAllContacts()
    }

    suspend fun loadProfileKey(aci: String): ByteArray? {
        return db.recipientDao().getProfileKey(aci)
    }

    suspend fun myProfileKey(ownAci: String): ByteArray? {
        return db.recipientDao().getProfileKey(ownAci)
    }

    suspend fun storeProfileKey(aci: String, profileKey: ByteArray) {
        if (profileKey.size != 32) {
            return
        }
        val existing = getRecipient(aci)
        if (existing != null) {
            storeRecipient(existing.copy(profileKey = profileKey))
        } else {
            storeRecipient(SignalRecipientEntity(aci = aci, profileKey = profileKey))
        }
    }

    suspend fun isBlocked(aci: String): Boolean {
        val entity = getRecipient(aci) ?: return false
        return entity.blocked
    }

    suspend fun isWhitelisted(aci: String): Boolean {
        val entity = getRecipient(aci) ?: return false
        return entity.whitelisted
    }

    suspend fun setWhitelisted(aci: String, whitelisted: Boolean) {
        val existing = getRecipient(aci)
        if (existing != null) {
            storeRecipient(existing.copy(whitelisted = whitelisted))
        } else {
            storeRecipient(SignalRecipientEntity(aci = aci, whitelisted = whitelisted))
        }
    }

    suspend fun setBlocked(aci: String, blocked: Boolean) {
        val existing = getRecipient(aci)
        if (existing != null) {
            storeRecipient(existing.copy(blocked = blocked))
        } else {
            storeRecipient(SignalRecipientEntity(aci = aci, blocked = blocked))
        }
    }

    suspend fun updateRecipientE164(aci: String, pni: String?, e164: String) {
        val existing = getRecipient(aci)
        if (existing != null) {
            storeRecipient(existing.copy(e164 = e164, pni = pni ?: existing.pni))
        } else {
            storeRecipient(SignalRecipientEntity(aci = aci, pni = pni, e164 = e164))
        }
    }

    suspend fun deleteRecipientByPni(pni: String) {
        db.recipientDao().deleteByPni(pni)
    }

    suspend fun markUnregistered(aci: String, unregistered: Boolean) {
        val existing = getRecipient(aci) ?: return
        storeRecipient(existing.copy(unregistered = unregistered))
    }

    suspend fun isUnregistered(aci: String): Boolean {
        return getRecipient(aci)?.unregistered == true
    }
}
