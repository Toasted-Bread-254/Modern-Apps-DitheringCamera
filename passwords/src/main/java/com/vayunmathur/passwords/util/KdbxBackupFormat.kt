package com.vayunmathur.passwords.util

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import org.linguafranca.pwdb.SerializableDatabase
import org.linguafranca.pwdb.kdbx.Helpers
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomHelper
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.linguafranca.pwdb.security.StreamEncryptor
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants

class KdbxBackupFormat(
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
) : BackupFormat {
    override val mimeType = "application/octet-stream"
    override val defaultFileName = "passwords.kdbx"
    override val needsPassword = true

    override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
        requireNotNull(password) { "Password required for KDBX export" }
        val creds = KdbxCreds(password.toByteArray())
        val db = DomDatabaseWrapper()

        val root = db.rootGroup

        for (pw in passwordDao.getAll()) {
            val entry = db.newEntry()
            entry.title = pw.name
            entry.username = pw.userId
            entry.password = pw.password
            if (pw.websites.isNotEmpty()) {
                entry.url = pw.websites.first()
                if (pw.websites.size > 1) {
                    entry.setProperty("Websites", pw.websites.joinToString("\n"))
                }
            }
            pw.totpSecret?.let { secret ->
                entry.setProperty("otp", "otpauth://totp/?secret=$secret")
            }
            entry.setProperty("_Type", "password")
            root.addEntry(entry)
        }

        for (pk in passkeyDao.getAll()) {
            val entry = db.newEntry()
            entry.title = pk.rpName
            entry.username = pk.userName
            entry.url = pk.rpId
            entry.setProperty("_Type", "passkey")
            entry.setProperty("KPEX_PASSKEY_USERNAME", pk.userName)
            entry.setProperty("KPEX_PASSKEY_PRIVATE_KEY_PEM", Base64.encodeToString(pk.privateKeyBytes, Base64.NO_WRAP))
            entry.setProperty("KPEX_PASSKEY_CREDENTIAL_ID", pk.credentialId)
            entry.setProperty("KPEX_PASSKEY_USER_HANDLE", pk.userId)
            entry.setProperty("KPEX_PASSKEY_RELYING_PARTY", pk.rpId)
            root.addEntry(entry)
        }

        saveKdbx(db, creds, outputStream)
    }

    /**
     * Android's Document.cloneNode(true) produces a malformed tree with nested
     * Document nodes (type 9). DomSerializableDatabase.save() uses cloneNode
     * internally, so we bypass it entirely: serialize the DOM to XML, reparse
     * into a clean copy, apply protection/date processing, then serialize out.
     * The KDBX encryption layer is handled by KdbxStreamFormat.
     */
    private fun saveKdbx(db: DomDatabaseWrapper, creds: KdbxCreds, outputStream: OutputStream) {
        val domDbField = DomDatabaseWrapper::class.java.getDeclaredField("domDatabase")
        domDbField.isAccessible = true
        val realDomDb = domDbField.get(db) as DomSerializableDatabase

        DomHelper.setElementContent("//Generator", realDomDb.doc.documentElement, "KeePassJava2-DOM")

        val wrapper = object : SerializableDatabase {
            private var encryption: StreamEncryptor? = null
            private var headerHash: ByteArray? = null

            override fun load(inputStream: InputStream) = this

            override fun save(os: OutputStream) {
                val doc = realDomDb.doc

                // Serialize→reparse to get a clean copy (avoids broken cloneNode)
                val baos = ByteArrayOutputStream()
                val tf = TransformerFactory.newInstance()
                tf.newTransformer().transform(DOMSource(doc), StreamResult(baos))
                val copyDoc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(ByteArrayInputStream(baos.toByteArray()))

                val xpath = DomHelper.xpath

                // Mark fields for protection based on Meta/MemoryProtection settings
                for (field in listOf("Title", "UserName", "Password", "Notes", "URL")) {
                    val query = "//Meta/MemoryProtection/Protect$field"
                    val protect = xpath.evaluate(query, copyDoc, XPathConstants.STRING) as String
                    if (protect.equals("true", ignoreCase = true)) {
                        val path = "//String/Key[text()='$field']/following-sibling::Value"
                        val nodes = xpath.evaluate(path, copyDoc, XPathConstants.NODESET) as NodeList
                        for (i in 0 until nodes.length) {
                            (nodes.item(i) as Element).setAttribute("kpj2-ProtectOnOutput", "True")
                        }
                    }
                }

                // Encrypt all protected fields
                val protectedContent = xpath.evaluate(
                    "//*[@kpj2-ProtectOnOutput='True']", copyDoc, XPathConstants.NODESET
                ) as NodeList
                for (i in 0 until protectedContent.length) {
                    val element = protectedContent.item(i) as Element
                    element.removeAttribute("kpj2-ProtectOnOutput")
                    element.setAttribute("Protected", "True")
                    val decrypted = DomHelper.getElementContent(".", element) ?: ""
                    val encrypted = encryption!!.encrypt(decrypted.toByteArray())
                    val base64 = String(org.apache.commons.codec.binary.Base64.encodeBase64(encrypted))
                    DomHelper.setElementContent(".", element, base64)
                }

                // Format dates
                val timeContent = xpath.evaluate(
                    "//*[substring(name(),string-length(name())-6) = 'Changed'] | " +
                        "//*[substring(name(),string-length(name())-3) = 'Time']",
                    copyDoc, XPathConstants.NODESET
                ) as NodeList
                for (i in 0 until timeContent.length) {
                    val element = timeContent.item(i) as Element
                    val time = DomHelper.getElementContent(".", element) ?: continue
                    val date = if (time == "\${creationDate}") {
                        Date.from(Instant.now())
                    } else {
                        Helpers.toDate(time)
                    }
                    DomHelper.setElementContent(".", element, Helpers.fromDate(date))
                }

                // Serialize processed copy to output
                val transformer = tf.newTransformer()
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                transformer.transform(DOMSource(copyDoc), StreamResult(os))
            }

            override fun getHeaderHash() = headerHash ?: ByteArray(0)
            override fun setHeaderHash(hash: ByteArray?) { headerHash = hash }
            override fun getEncryption() = encryption
            override fun setEncryption(enc: StreamEncryptor?) { encryption = enc }
            override fun addBinary(index: Int, payload: ByteArray?) = realDomDb.addBinary(index, payload)
            override fun getBinary(index: Int): ByteArray = realDomDb.getBinary(index)
            override fun getBinaryCount() = realDomDb.binaryCount
        }

        val streamFormat = KdbxStreamFormat(KdbxHeader(4))
        streamFormat.save(wrapper, creds, outputStream)
    }

    override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
        requireNotNull(password) { "Password required for KDBX import" }
        val creds = KdbxCreds(password.toByteArray())
        val db = DomDatabaseWrapper.load(creds, inputStream)

        suspend fun processGroup(group: org.linguafranca.pwdb.Group<*, *, *, *>) {
            for (i in 0 until group.entriesCount) {
                @Suppress("UNCHECKED_CAST")
                val entry = group.entries[i] as org.linguafranca.pwdb.Entry<*, *, *, *>
                val isPasskey = entry.propertyNames.any { it.startsWith("KPEX_PASSKEY_") }
                if (isPasskey) {
                    importPasskey(entry)
                } else {
                    importPassword(entry)
                }
            }
            for (i in 0 until group.groupsCount) {
                processGroup(group.groups[i])
            }
        }

        processGroup(db.rootGroup)
    }

    private suspend fun importPassword(entry: org.linguafranca.pwdb.Entry<*, *, *, *>) {
        val websites = mutableListOf<String>()
        val url = entry.url.orEmpty()
        if (url.isNotEmpty()) websites.add(url)
        val extraWebsites = entry.getProperty("Websites")
        if (!extraWebsites.isNullOrEmpty()) {
            extraWebsites.split("\n").filter { it.isNotBlank() }.forEach { w ->
                if (w !in websites) websites.add(w)
            }
        }
        var totpSecret: String? = null
        val otp = entry.getProperty("otp")
        if (!otp.isNullOrEmpty()) {
            val match = Regex("[?&]secret=([^&]+)").find(otp)
            totpSecret = match?.groupValues?.get(1) ?: otp
        }
        if (totpSecret == null) {
            totpSecret = entry.getProperty("TOTP Seed")
        }

        val pw = Password(
            name = entry.title.orEmpty(),
            userId = entry.username.orEmpty(),
            password = entry.getProperty("Password").orEmpty(),
            websites = websites,
            totpSecret = totpSecret,
        )
        passwordDao.upsert(pw)
    }

    private suspend fun importPasskey(entry: org.linguafranca.pwdb.Entry<*, *, *, *>) {
        val privateKeyB64 = entry.getProperty("KPEX_PASSKEY_PRIVATE_KEY_PEM").orEmpty()
        val privateKeyBytes = if (privateKeyB64.isNotEmpty()) Base64.decode(privateKeyB64, Base64.NO_WRAP) else ByteArray(0)

        val pk = Passkey(
            rpId = entry.getProperty("KPEX_PASSKEY_RELYING_PARTY").orEmpty().ifEmpty { entry.url.orEmpty() },
            rpName = entry.title.orEmpty(),
            credentialId = entry.getProperty("KPEX_PASSKEY_CREDENTIAL_ID").orEmpty(),
            userId = entry.getProperty("KPEX_PASSKEY_USER_HANDLE").orEmpty(),
            userName = entry.getProperty("KPEX_PASSKEY_USERNAME").orEmpty().ifEmpty { entry.username.orEmpty() },
            userDisplayName = entry.username.orEmpty(),
            privateKeyBytes = privateKeyBytes,
        )
        passkeyDao.upsert(pk)
    }
}
