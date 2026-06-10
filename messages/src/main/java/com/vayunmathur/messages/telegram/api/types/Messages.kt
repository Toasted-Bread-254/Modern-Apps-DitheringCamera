package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Message(
    val id: Int,
    val fromId: TlObject? = null,
    val peerId: TlObject,
    val date: Int,
    val message: String,
    val out: Boolean = false,
    val mediaTypeId: Int = 0,
    val media: TlObject? = null,
    val replyToTopId: Int = 0,
    val forumTopic: Boolean = false,
    val replyMarkup: ByteArray? = null,
) : TlObject {
    override val typeId = 0x95ef6f2b.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Message {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            if (flags.has(29)) buf.int32() // from_boosts_applied
            if (flags2.has(12)) buf.string() // from_rank
            val peerId = decodePeer(buf)
            if (flags.has(28)) decodePeer(buf) // saved_peer_id
            if (flags.has(2)) {
                buf.int32() // fwd_from constructor id
                TlSkip.skipMessageFwdHeader(buf)
            }
            if (flags.has(11)) buf.int64() // via_bot_id
            if (flags2.has(0)) buf.int64() // via_business_bot_id
            if (flags2.has(19)) decodePeer(buf) // guestchat_via_from
            var replyToTopId = 0
            var forumTopic = false
            if (flags.has(3)) {
                val replyInfo = parseReplyTo(buf)
                replyToTopId = replyInfo.first
                forumTopic = replyInfo.second
            }
            val date = buf.int32()
            val message = buf.string()
            val mediaTypeId = if (flags.has(9)) buf.peekId() else 0
            val media = if (flags.has(9)) decodeMedia(buf) else null
            if (flags.has(6)) TlSkip.skipReplyMarkup(buf) // reply_markup
            if (flags.has(7)) TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // entities
            if (flags.has(10)) { buf.int32(); buf.int32() } // views, forwards
            if (flags.has(23)) TlSkip.skipMessageReplies(buf) // replies
            if (flags.has(15)) buf.int32() // edit_date
            if (flags.has(16)) buf.string() // post_author
            if (flags.has(17)) buf.int64() // grouped_id
            if (flags.has(20)) TlSkip.skipReactions(buf) // reactions
            if (flags.has(22)) TlSkip.skipVector(buf) { TlSkip.skipRestrictionReason(it) } // restriction_reason
            if (flags.has(25)) buf.int32() // ttl_period
            if (flags.has(30)) buf.int32() // quick_reply_shortcut_id
            if (flags2.has(2)) buf.int64() // effect
            if (flags2.has(3)) TlSkip.skipFactCheck(buf) // factcheck
            if (flags2.has(5)) buf.int32() // report_delivery_until_date
            if (flags2.has(6)) buf.int64() // paid_message_stars
            if (flags2.has(7)) TlSkip.skipBoxedType(buf) // suggested_post
            if (flags2.has(10)) buf.int32() // schedule_repeat_period
            if (flags2.has(11)) buf.string() // summary_from_language
            return Message(id, fromId, peerId, date, message, out, mediaTypeId, media, replyToTopId, forumTopic)
        }

        private fun parseReplyTo(buf: TlBuffer): Pair<Int, Boolean> {
            val typeId = buf.int32()
            when (typeId) {
                0x0e5af939.toInt() -> { // messageReplyStoryHeader
                    decodePeer(buf)
                    buf.int32()
                    return Pair(0, false)
                }
                0x1b97dd66.toInt() -> { // messageReplyHeader
                    val flags = Fields.decode(buf)
                    val forumTopic = flags.has(3)
                    val replyToMsgId = if (flags.has(4)) buf.int32() else 0
                    if (flags.has(0)) decodePeer(buf) // reply_to_peer_id
                    if (flags.has(5)) TlSkip.skipBoxedType(buf) // reply_from (MessageFwdHeader)
                    if (flags.has(8)) TlSkip.skipBoxedType(buf) // reply_media
                    val replyToTopId = if (flags.has(1)) buf.int32() else 0
                    if (flags.has(6)) buf.string() // quote_text
                    if (flags.has(7)) TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // quote_entities
                    if (flags.has(10)) buf.int32() // quote_offset
                    if (flags.has(11)) buf.int32() // todo_item_id
                    if (flags.has(12)) buf.bytes() // poll_option
                    val topicId = if (forumTopic) {
                        if (replyToTopId != 0) replyToTopId else replyToMsgId
                    } else 0
                    return Pair(topicId, forumTopic)
                }
            }
            return Pair(0, false)
        }

        private fun decodeMedia(buf: TlBuffer): TlObject {
            val typeId = buf.int32()
            return when (typeId) {
                0x3ded6320.toInt() -> MessageMediaEmpty // messageMediaEmpty
                0xe216eb63.toInt() -> { // messageMediaPhoto
                    val flags = Fields.decode(buf)
                    if (flags.has(0)) TlSkip.skipBoxedType(buf)
                    if (flags.has(2)) buf.int32()
                    if (flags.has(4)) TlSkip.skipBoxedType(buf) // video
                    MessageMediaPhoto()
                }
                0x52d8ccd9.toInt() -> decodeMediaDocument(buf)
                0x56e0d474.toInt() -> decodeMediaGeo(buf) // messageMediaGeo
                0xb940c666.toInt() -> decodeMediaGeoLive(buf)
                0x70322949.toInt() -> { // messageMediaContact
                    val phone = buf.string()
                    val firstName = buf.string()
                    val lastName = buf.string()
                    val vcard = buf.string()
                    val userId = buf.int64()
                    MessageMediaContact(phone, firstName, lastName, vcard, userId)
                }
                0x9f84f49e.toInt() -> MessageMediaUnsupported
                0x2ec0533f.toInt() -> decodeMediaVenue(buf)
                0xfdb19008.toInt() -> { TlSkip.skipBoxedType(buf); MessageMediaUnsupported } // game
                0x08cbec07.toInt() -> { // messageMediaDice
                    val flags = Fields.decode(buf)
                    val value = buf.int32()
                    val emoticon = buf.string()
                    if (flags.has(0)) TlSkip.skipBoxedType(buf) // game_outcome
                    MessageMediaDice(value, emoticon)
                }
                0xddf10c3b.toInt() -> { // messageMediaWebPage
                    Fields.decode(buf)
                    TlSkip.skipWebPageBoxed(buf)
                    MessageMediaUnsupported
                }
                0x773f4e66.toInt() -> decodeMediaPoll(buf)
                else -> {
                    MessageMediaUnsupported
                }
            }
        }

        private fun decodeMediaDocument(buf: TlBuffer): MessageMediaDocument {
            val flags = Fields.decode(buf)
            var mimeType = ""
            var fileName = ""
            var isSticker = false
            var stickerAlt = ""
            var isAnimated = false
            var isVoice = false
            var isRoundVideo = false
            var isVideo = false
            var duration = 0.0
            var width = 0
            var height = 0
            if (flags.has(0)) {
                val docTypeId = buf.int32()
                if (docTypeId == 0x8fd4c4d8.toInt()) { // document
                    val docFlags = Fields.decode(buf)
                    buf.int64() // id
                    buf.int64() // access_hash
                    buf.bytes() // file_reference
                    buf.int32() // date
                    mimeType = buf.string()
                    buf.int64() // size
                    if (docFlags.has(0)) TlSkip.skipVector(buf) { TlSkip.skipPhotoSizeBoxed(it) }
                    if (docFlags.has(1)) TlSkip.skipVector(buf) { TlSkip.skipVideoSizeBoxed(it) }
                    buf.int32() // dc_id
                    // parse attributes
                    buf.int32() // vector constructor
                    val attrCount = buf.int32()
                    repeat(attrCount) {
                        val attrId = buf.int32()
                        when (attrId) {
                            0x6c37c15c.toInt() -> { width = buf.int32(); height = buf.int32() } // imageSize
                            0x11b58939.toInt() -> { isAnimated = true } // animated
                            0x6319d612.toInt() -> { // sticker
                                isSticker = true
                                val stickerFlags = Fields.decode(buf)
                                stickerAlt = buf.string()
                                TlSkip.skipInputStickerSetBoxed(buf)
                                if (stickerFlags.has(0)) {
                                    buf.int32(); buf.int32(); buf.double(); buf.double(); buf.double()
                                }
                            }
                            0x17399fad.toInt(), 0x43c57c48.toInt() -> { // video
                                isVideo = true
                                val vFlags = Fields.decode(buf)
                                isRoundVideo = vFlags.has(0)
                                duration = buf.double()
                                width = buf.int32()
                                height = buf.int32()
                                if (vFlags.has(2)) buf.int32()
                                if (vFlags.has(4)) buf.double()
                                if (vFlags.has(5)) buf.string()
                            }
                            0x9852f9c6.toInt() -> { // audio
                                val aFlags = Fields.decode(buf)
                                isVoice = aFlags.has(10)
                                duration = buf.int32().toDouble()
                                if (aFlags.has(0)) buf.string()
                                if (aFlags.has(1)) buf.string()
                                if (aFlags.has(2)) buf.bytes()
                            }
                            0x15590068.toInt() -> { fileName = buf.string() } // filename
                            0x9801d2f7.toInt() -> {} // hasStickers
                            0xfd149899.toInt() -> { // customEmoji
                                Fields.decode(buf)
                                buf.string()
                                TlSkip.skipInputStickerSetBoxed(buf)
                            }
                            else -> {}
                        }
                    }
                } else if (docTypeId == 0x36f8c871.toInt()) {
                    buf.int64() // documentEmpty: id
                }
            }
            if (flags.has(5)) TlSkip.skipVectorBoxed(buf) // alt_documents (vector)
            if (flags.has(9)) TlSkip.skipBoxedType(buf) // video_cover
            if (flags.has(10)) buf.int32() // video_timestamp
            if (flags.has(2)) buf.int32() // ttl_seconds
            return MessageMediaDocument(mimeType, fileName, isSticker, stickerAlt, isAnimated, isVoice, isRoundVideo, isVideo, duration, width, height)
        }

        private fun decodeMediaGeo(buf: TlBuffer): MessageMediaGeo {
            val geoTypeId = buf.int32()
            return when (geoTypeId) {
                0x1117dd5f.toInt() -> MessageMediaGeo(0.0, 0.0) // geoPointEmpty
                0xb2a2f663.toInt() -> { // geoPoint
                    val f = Fields.decode(buf)
                    val long = buf.double()
                    val lat = buf.double()
                    buf.int64() // access_hash
                    if (f.has(0)) buf.int32() // accuracy_radius
                    MessageMediaGeo(lat, long)
                }
                else -> MessageMediaGeo(0.0, 0.0)
            }
        }

        private fun decodeMediaGeoLive(buf: TlBuffer): MessageMediaGeoLive {
            val flags = Fields.decode(buf)
            val geoTypeId = buf.int32()
            var lat = 0.0
            var long = 0.0
            when (geoTypeId) {
                0xb2a2f663.toInt() -> {
                    val f = Fields.decode(buf)
                    long = buf.double()
                    lat = buf.double()
                    buf.int64()
                    if (f.has(0)) buf.int32()
                }
                0x1117dd5f.toInt() -> {}
            }
            val heading = if (flags.has(0)) buf.int32() else 0
            val period = buf.int32()
            val proximityNotificationRadius = if (flags.has(1)) buf.int32() else 0
            return MessageMediaGeoLive(lat, long, heading, period, proximityNotificationRadius)
        }

        private fun decodeMediaVenue(buf: TlBuffer): MessageMediaVenue {
            val geoTypeId = buf.int32()
            var lat = 0.0
            var long = 0.0
            when (geoTypeId) {
                0xb2a2f663.toInt() -> {
                    val f = Fields.decode(buf)
                    long = buf.double()
                    lat = buf.double()
                    buf.int64()
                    if (f.has(0)) buf.int32()
                }
                0x1117dd5f.toInt() -> {}
            }
            val title = buf.string()
            val address = buf.string()
            val provider = buf.string()
            val venueId = buf.string()
            val venueType = buf.string()
            return MessageMediaVenue(lat, long, title, address, provider, venueId, venueType)
        }

        private fun decodeMediaPoll(buf: TlBuffer): MessageMediaPoll {
            val flags = Fields.decode(buf)
            val pollTypeId = buf.int32() // poll constructor
            val pollId = buf.int64()
            val pollFlags = Fields.decode(buf)
            val question = try {
                val textTypeId = buf.int32() // textWithEntities constructor
                val questionText = buf.string()
                TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // entities
                questionText
            } catch (_: Exception) { "" }
            // Skip remaining poll data
            try {
                TlSkip.skipVector(buf) { // answers
                    buf.int32() // pollAnswer constructor
                    buf.int32() // textWithEntities
                    buf.string() // text
                    TlSkip.skipVector(it) { TlSkip.skipMessageEntity(it) }
                    buf.bytes() // option
                }
                if (pollFlags.has(4)) buf.int32() // close_period
                if (pollFlags.has(5)) buf.int32() // close_date
            } catch (_: Exception) {}
            // Skip poll results
            try { TlSkip.skipBoxedType(buf) } catch (_: Exception) {}
            // Skip attached_media (new in layer 225)
            try { if (flags.has(0)) TlSkip.skipBoxedType(buf) } catch (_: Exception) {}
            return MessageMediaPoll(question)
        }
    }
}

object MessageEmpty : TlObject {
    override val typeId = 0x90a6ca84.toInt()
    override fun encode(buf: TlBuffer) {}
    fun decode(buf: TlBuffer): MessageEmpty {
        val flags = Fields.decode(buf)
        buf.int32() // id
        if (flags.has(0)) decodePeer(buf) // peer_id
        return MessageEmpty
    }
}

data class MessageService(
    val id: Int,
    val peerId: TlObject,
    val date: Int,
    val out: Boolean,
) : TlObject {
    override val typeId = 0x7a800e0a.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer): MessageService {
            val flags = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            val peerId = decodePeer(buf)
            if (flags.has(28)) decodePeer(buf) // saved_peer_id
            if (flags.has(3)) TlSkip.skipReplyTo(buf) // reply_to
            val date = buf.int32()
            TlSkip.skipMessageAction(buf) // action (mandatory)
            if (flags.has(20)) TlSkip.skipReactions(buf) // reactions
            if (flags.has(25)) buf.int32() // ttl_period
            return MessageService(id, peerId, date, out)
        }
    }
}
