package com.vayunmathur.photos.util

import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal SentencePiece tokenizer that parses a `.model` protobuf file
 * and provides encode/decode using greedy longest-match.
 *
 * The `.model` format is a serialized `ModelProto` with:
 *   field 1 = TrainerSpec (message)
 *   field 2 = NormalizerSpec (message)
 *   field 3 = repeated SentencePiece (message), each containing:
 *       field 1 = piece (string)
 *       field 2 = score (float)
 *       field 3 = type (enum: NORMAL=1, UNKNOWN=2, CONTROL=3, USER_DEFINED=4, BYTE=6)
 */
class SentencePieceTokenizer {

    private val pieceToId = mutableMapOf<String, Int>()
    private val idToPiece = mutableMapOf<Int, String>()
    private var unknownId = 0
    private var bosId = 1
    private var eosId = 2

    companion object {
        private const val TAG = "SentencePieceTokenizer"
        // SentencePiece uses U+2581 (▁) as the word-boundary marker
        private const val SPACE_SYMBOL = "\u2581"
    }

    /**
     * Load vocabulary from a SentencePiece .model file.
     * Returns true if loading succeeded.
     */
    fun load(file: File): Boolean {
        return try {
            file.inputStream().use { load(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer model", e)
            false
        }
    }

    fun load(inputStream: InputStream): Boolean {
        return try {
            val data = inputStream.readBytes()
            parseModelProto(data)
            Log.i(TAG, "Loaded ${pieceToId.size} vocabulary pieces")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tokenizer model", e)
            false
        }
    }

    /**
     * Encode text into token IDs using greedy longest-match.
     */
    fun encode(text: String): IntArray {
        // Prepend space symbol and replace spaces with the SentencePiece marker
        val normalized = SPACE_SYMBOL + text.replace(" ", SPACE_SYMBOL)
        val tokens = mutableListOf<Int>()
        var i = 0

        while (i < normalized.length) {
            var bestLen = 0
            var bestId = unknownId

            // Greedy longest match
            for (len in minOf(normalized.length - i, 64) downTo 1) {
                val candidate = normalized.substring(i, i + len)
                val id = pieceToId[candidate]
                if (id != null) {
                    bestLen = len
                    bestId = id
                    break
                }
            }

            if (bestLen == 0) {
                // Fallback: try byte-level encoding for unknown character
                val ch = normalized[i]
                val byteToken = pieceToId["<0x${"%02X".format(ch.code.toByte())}>"]
                if (byteToken != null) {
                    tokens.add(byteToken)
                } else {
                    tokens.add(unknownId)
                }
                i++
            } else {
                tokens.add(bestId)
                i += bestLen
            }
        }

        return tokens.toIntArray()
    }

    /**
     * Decode token IDs back into text.
     */
    fun decode(tokens: IntArray): String {
        val sb = StringBuilder()
        for (id in tokens) {
            if (id == eosId || id == bosId) continue
            val piece = idToPiece[id] ?: continue

            // Skip byte-fallback tokens that look like <0xHH>
            if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                val byte = piece.substring(3, 5).toIntOrNull(16)
                if (byte != null) {
                    sb.append(byte.toChar())
                    continue
                }
            }

            sb.append(piece)
        }
        // Replace SentencePiece space markers with actual spaces and trim leading space
        return sb.toString().replace(SPACE_SYMBOL, " ").trimStart()
    }

    val vocabSize: Int get() = pieceToId.size
    val eosTokenId: Int get() = eosId
    val bosTokenId: Int get() = bosId

    // --- Raw protobuf parsing ---

    private fun parseModelProto(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var pieceIndex = 0

        while (buf.hasRemaining()) {
            val tag = readVarInt(buf).toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (wireType) {
                0 -> readVarInt(buf) // varint - skip
                1 -> buf.position(buf.position() + 8) // 64-bit - skip
                2 -> { // length-delimited
                    val length = readVarInt(buf).toInt()
                    if (fieldNumber == 3) {
                        // This is a SentencePiece entry
                        val pieceData = ByteArray(length)
                        buf.get(pieceData)
                        parseSentencePiece(pieceData, pieceIndex)
                        pieceIndex++
                    } else {
                        // Skip other fields
                        buf.position(buf.position() + length)
                    }
                }
                5 -> buf.position(buf.position() + 4) // 32-bit - skip
                else -> break
            }
        }

        // Identify special token IDs
        pieceToId["<unk>"]?.let { unknownId = it }
        pieceToId["<s>"]?.let { bosId = it }
        pieceToId["</s>"]?.let { eosId = it }
    }

    private fun parseSentencePiece(data: ByteArray, index: Int) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var piece: String? = null

        while (buf.hasRemaining()) {
            val tag = readVarInt(buf).toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (wireType) {
                0 -> readVarInt(buf) // varint (type field, etc.) - skip value
                1 -> buf.position(buf.position() + 8)
                2 -> {
                    val length = readVarInt(buf).toInt()
                    if (fieldNumber == 1) {
                        // piece string
                        val strBytes = ByteArray(length)
                        buf.get(strBytes)
                        piece = String(strBytes, Charsets.UTF_8)
                    } else {
                        buf.position(buf.position() + length)
                    }
                }
                5 -> {
                    if (fieldNumber == 2) {
                        // score float - skip (we don't need it for greedy matching)
                        buf.position(buf.position() + 4)
                    } else {
                        buf.position(buf.position() + 4)
                    }
                }
                else -> break
            }
        }

        if (piece != null) {
            pieceToId[piece] = index
            idToPiece[index] = piece
        }
    }

    private fun readVarInt(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}
