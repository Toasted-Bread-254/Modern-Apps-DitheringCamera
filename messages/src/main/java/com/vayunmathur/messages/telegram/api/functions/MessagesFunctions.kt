package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// messages.getDialogs
data class MessagesGetDialogs(
    val offsetDate: Int = 0,
    val offsetId: Int = 0,
    val offsetPeer: TlObject,
    val limit: Int,
    val hash: Long = 0,
) : TlMethod<TlObject> {
    override val typeId = 0xa0f4cb4f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putInt32(offsetDate)
        buf.putInt32(offsetId)
        offsetPeer.encode(buf)
        buf.putInt32(limit)
        buf.putInt64(hash)
    }
}

// messages.getHistory
data class MessagesGetHistory(
    val peer: TlObject,
    val offsetId: Int = 0,
    val offsetDate: Int = 0,
    val addOffset: Int = 0,
    val limit: Int,
    val maxId: Int = 0,
    val minId: Int = 0,
    val hash: Long = 0,
) : TlMethod<TlObject> {
    override val typeId = 0x4423e6c5.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        peer.encode(buf)
        buf.putInt32(offsetId)
        buf.putInt32(offsetDate)
        buf.putInt32(addOffset)
        buf.putInt32(limit)
        buf.putInt32(maxId)
        buf.putInt32(minId)
        buf.putInt64(hash)
    }
}

// messages.sendMessage
data class MessagesSendMessage(
    val peer: TlObject,
    val message: String,
    val randomId: Long,
) : TlMethod<TlObject> {
    override val typeId = 0x545cd15a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        peer.encode(buf)
        buf.putString(message)
        buf.putInt64(randomId)
    }
}

// messages.sendMedia
data class MessagesSendMedia(
    val peer: TlObject,
    val media: TlObject,
    val message: String,
    val randomId: Long,
) : TlMethod<TlObject> {
    override val typeId = 0x0330e77f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        peer.encode(buf)
        media.encode(buf)
        buf.putString(message)
        buf.putInt64(randomId)
    }
}

// messages.readHistory
data class MessagesReadHistory(val peer: TlObject, val maxId: Int) : TlMethod<TlObject> {
    override val typeId = 0x0e306d3a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        peer.encode(buf)
        buf.putInt32(maxId)
    }
}

// messages.deleteHistory
data class MessagesDeleteHistory(val peer: TlObject, val maxId: Int = 0) : TlMethod<TlObject> {
    override val typeId = 0xb08f922a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(1 shl 1) // flags: revoke = true (bit 1)
        peer.encode(buf)
        buf.putInt32(maxId)
    }
}

// messages.editMessage
data class MessagesEditMessage(
    val peer: TlObject,
    val id: Int,
    val message: String,
) : TlMethod<TlObject> {
    override val typeId = 0x51e842e1.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(1 shl 11) // flags: has message
        peer.encode(buf)
        buf.putInt32(id)
        buf.putString(message)
    }
}

// messages.sendReaction
data class MessagesSendReaction(
    val peer: TlObject,
    val msgId: Int,
    val reaction: List<TlObject>,
    val big: Boolean = false,
) : TlMethod<TlObject> {
    override val typeId = 0xd30d78d4.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = if (reaction.isNotEmpty()) 1 else 0 // bit 0: has reaction list
        if (big) flags = flags or (1 shl 1) // bit 1: big
        flags = flags or (1 shl 2) // bit 2: add_to_recent
        buf.putInt32(flags)
        peer.encode(buf)
        buf.putInt32(msgId)
        if (reaction.isNotEmpty()) {
            buf.putId(0x1cb5c415.toInt()) // vector
            buf.putInt32(reaction.size)
            for (r in reaction) r.encode(buf)
        }
    }
}

// messages.setTyping
data class MessagesSetTyping(val peer: TlObject) : TlMethod<TlObject> {
    override val typeId = 0x58943ee2.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        peer.encode(buf)
        buf.putId(0x16bf744e.toInt()) // sendMessageTypingAction
    }
}

// GeoPoint for sending locations
data class InputGeoPoint(val lat: Double, val long: Double) : TlObject {
    override val typeId = 0x48222faf.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putDouble(lat)
        buf.putDouble(long)
    }
}

// inputMediaGeoPoint for sending locations as media
data class InputMediaGeoPoint(val geoPoint: InputGeoPoint) : TlObject {
    override val typeId = 0xf9c44144.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        geoPoint.encode(buf)
    }
}

// inputMediaGeoLive for sending live locations
data class InputMediaGeoLive(
    val geoPoint: InputGeoPoint,
    val stopped: Boolean = false,
    val period: Int = 0,
    val heading: Int = 0,
    val proximityNotificationRadius: Int = 0,
) : TlObject {
    override val typeId = 0x971fa843.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        val flags = (if (stopped) 1 else 0) or
            (if (period != 0) 1 shl 1 else 0) or
            (if (heading != 0) 1 shl 2 else 0) or
            (if (proximityNotificationRadius != 0) 1 shl 3 else 0)
        buf.putInt32(flags)
        geoPoint.encode(buf)
        if (heading != 0) buf.putInt32(heading)
        if (period != 0) buf.putInt32(period)
        if (proximityNotificationRadius != 0) buf.putInt32(proximityNotificationRadius)
    }
}
