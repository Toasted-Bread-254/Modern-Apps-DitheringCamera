package com.vayunmathur.email

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["accountEmail", "fullName"])
data class EmailFolder(
    val accountEmail: String,
    val fullName: String,
    val name: String,
    val parentFullName: String? = null,
    val holdsMessages: Boolean = true,
    val delimiter: String = "/"
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "folderName", "id"])
data class EmailMessage(
    val accountEmail: String,
    val folderName: String,
    val id: Long, // IMAP UID
    val serverId: String? = null, // Message-ID header
    val threadId: String? = null, // Gmail Thread ID or custom grouping
    val subject: String,
    val from: String,
    val to: String? = null,
    val cc: String? = null,
    val date: String,
    val body: String? = null,
    val isHtml: Boolean = false,
    val references: String? = null, // References/In-Reply-To for threading
    val hasAttachments: Boolean = false
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "messageId", "partId"])
data class Attachment(
    val accountEmail: String,
    val folderName: String,
    val messageId: Long, // IMAP UID
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val localUri: String? = null
)

@Serializable
@Entity
data class EmailAccount(
    @PrimaryKey val email: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0
)
