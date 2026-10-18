package sh.ahmd.wallflower.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val FRAME_JSON: Int = 1
const val FRAME_DATA: Int = 2
const val FRAME_UPLOAD: Int = 3

/** Bounds a frame's length field, so a corrupt stream can't make a reader allocate without limit. */
const val MAX_FRAME = 16 shl 20

class ProtocolException(message: String) : IOException(message)

sealed interface Frame {
    data class Message(val message: ServerMessage) : Frame

    /** A message type newer than this client; safe to ignore. */
    data class Unknown(val json: String) : Frame

    /** Pane output from the gateway: [length] bytes of [bytes] from [offset]. */
    class Data(val pane: String, val bytes: ByteArray, val offset: Int, val length: Int) : Frame
}

class FrameReader(input: InputStream) {
    private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))

    /** Blocks for the next frame; null at a clean end of stream. */
    fun next(): Frame? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length !in 1..MAX_FRAME) throw ProtocolException("bad frame length $length")
        val type = input.readUnsignedByte()
        val payload = ByteArray(length - 1)
        input.readFully(payload)

        return when (type) {
            FRAME_JSON -> decodeMessage(payload.decodeToString())
            FRAME_DATA -> {
                val n = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xff
                if (n < 0 || n > payload.size - 1) throw ProtocolException("truncated pane id")
                Frame.Data(payload.decodeToString(1, 1 + n), payload, 1 + n, payload.size - 1 - n)
            }
            else -> throw ProtocolException("unknown frame type $type")
        }
    }

    private fun decodeMessage(json: String): Frame {
        try {
            val obj = WireJson.parseToJsonElement(json).jsonObject
            val type = obj["t"]?.jsonPrimitive?.content
            if (type == null || type !in serverTypes) return Frame.Unknown(json)
            return Frame.Message(WireJson.decodeFromJsonElement(ServerMessage.serializer(), obj))
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("bad message: ${e.message}")
        }
    }

    private companion object {
        // The "t" names of the sealed hierarchy's subclasses.
        val serverTypes = ServerMessage.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
    }
}

/** Writes frames, buffered until [flush]; safe to call from multiple threads. */
class FrameWriter(output: OutputStream) {
    private val out = BufferedOutputStream(output, 64 * 1024)

    @Synchronized
    fun send(message: ClientMessage) {
        val body = encode(message)
        header(1 + body.size, FRAME_JSON)
        out.write(body)
    }

    @Synchronized
    fun flush() = out.flush()

    fun sendData(pane: String, bytes: ByteArray) = sendBytes(FRAME_DATA, pane, bytes, bytes.size)

    /** The next [length] of upload [id]'s bytes. */
    fun sendUpload(id: String, bytes: ByteArray, length: Int = bytes.size) = sendBytes(FRAME_UPLOAD, id, bytes, length)

    @Synchronized
    private fun sendBytes(type: Int, id: String, bytes: ByteArray, length: Int) {
        val idBytes = id.encodeToByteArray()
        require(idBytes.size <= 255) { "id longer than 255 bytes" }
        header(2 + idBytes.size + length, type)
        out.write(idBytes.size)
        out.write(idBytes)
        out.write(bytes, 0, length)
    }

    private fun header(length: Int, type: Int) {
        out.write(length ushr 24)
        out.write(length ushr 16)
        out.write(length ushr 8)
        out.write(length)
        out.write(type)
    }

    companion object {
        fun encode(message: ClientMessage): ByteArray =
            WireJson.encodeToString(ClientMessage.serializer(), message).encodeToByteArray()
    }
}
