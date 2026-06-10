package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

object MessageMediaEmpty : TlObject {
    override val typeId = 0x3ded6320.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaPhoto(val dummy: Int = 0) : TlObject {
    override val typeId = 0xe216eb63.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaDocument(
    val mimeType: String = "",
    val fileName: String = "",
    val isSticker: Boolean = false,
    val stickerAlt: String = "",
    val isAnimated: Boolean = false,
    val isVoice: Boolean = false,
    val isRoundVideo: Boolean = false,
    val isVideo: Boolean = false,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
) : TlObject {
    override val typeId = 0x52d8ccd9.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaContact(
    val phoneNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val vcard: String = "",
    val userId: Long = 0,
) : TlObject {
    override val typeId = 0x70322949.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaGeo(val lat: Double = 0.0, val long: Double = 0.0) : TlObject {
    override val typeId = 0x56e0d474.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaGeoLive(
    val lat: Double = 0.0,
    val long: Double = 0.0,
    val heading: Int = 0,
    val period: Int = 0,
    val proximityNotificationRadius: Int = 0,
) : TlObject {
    override val typeId = 0xb940c666.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaVenue(
    val lat: Double = 0.0,
    val long: Double = 0.0,
    val title: String = "",
    val address: String = "",
    val provider: String = "",
    val venueId: String = "",
    val venueType: String = "",
) : TlObject {
    override val typeId = 0x2ec0533f.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaPoll(val pollQuestion: String = "") : TlObject {
    override val typeId = 0x773f4e66.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaDice(val value: Int = 0, val emoticon: String = "") : TlObject {
    override val typeId = 0x08cbec07.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageMediaUnsupported : TlObject {
    override val typeId = 0x9f84f49e.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Input types for sending media
data class InputFile(val id: Long, val parts: Int, val name: String, val md5Checksum: String = "") : TlObject {
    override val typeId = 0xf52ff27f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt32(parts)
        buf.putString(name)
        buf.putString(md5Checksum)
    }
}

data class InputFileBig(val id: Long, val parts: Int, val name: String) : TlObject {
    override val typeId = 0xfa4f0bb5.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt32(parts)
        buf.putString(name)
    }
}

data class InputMediaUploadedPhoto(val file: TlObject) : TlObject {
    override val typeId = 0x7d8375da.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
    }
}

data class InputMediaUploadedDocument(
    val file: TlObject,
    val mimeType: String,
    val attributes: List<TlObject> = emptyList(),
) : TlObject {
    override val typeId = 0x037c9330.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
        buf.putString(mimeType)
        buf.putId(0x1cb5c415.toInt()) // vector of attributes
        buf.putInt32(attributes.size)
        for (attr in attributes) attr.encode(buf)
    }
}

data class DocumentAttributeFilename(val fileName: String) : TlObject {
    override val typeId = 0x15590068.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(fileName) }
}

// Reaction types
data class InputMessageReactionEmoji(val emoticon: String) : TlObject {
    override val typeId = 0x1b2286b8.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(emoticon) }
}
