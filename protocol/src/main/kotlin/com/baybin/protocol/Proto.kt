package com.baybin.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Glasses <-> phone messages over a plain Bluetooth RFCOMM socket.
 *
 * The glasses app listens (insecure RFCOMM on [SERVICE_UUID], so no pairing dialog) and
 * the phone app connects to the glasses' Bluetooth address. Rokid's own CXR message
 * channel would need a per-device SN licence from Rokid's developer portal before
 * `connectBluetooth` does anything, so the two apps talk over their own socket.
 *
 * Every frame: magic "BB" (2 bytes), type (1 byte), payload length (int32 big-endian),
 * payload. Ints are big-endian, strings are `DataOutput.writeUTF`.
 */
object Proto {
    val SERVICE_UUID: UUID = UUID.fromString("6f6a2b8e-3b1d-4c55-9a43-2f0d6b1b7a10")
    const val SERVICE_NAME = "BayBin"

    // ---- frame types ----

    /** Both directions, right after connecting. Payload: app version (UTF). */
    const val HELLO = 1

    /**
     * glasses -> phone. Payload: reqId (int), rotation (int, clockwise degrees to make the
     * image upright; the glasses don't rotate pixels), then the JPEG (≤1024px long side, q80).
     */
    const val SCAN = 2

    /** phone -> glasses. Payload: reqId (int), kind (int, KIND_*), line1 (UTF), line2 (UTF). */
    const val RESULT = 3

    /** glasses -> phone, transfer test. Payload: reqId (int), then filler bytes. */
    const val PROBE = 4

    /** phone -> glasses. Payload: reqId (int), filler bytes received (int). */
    const val PROBE_ACK = 5

    /**
     * glasses -> phone, empty: the key was just pressed and a photo is coming in ~1.3 s.
     * The phone uses the time to open its HTTPS connection to the model (a cold TLS
     * handshake to the cloud region costs most of a second).
     */
    const val PREPARE = 6

    const val KIND_OK = 0
    const val KIND_UNSURE = 1
    const val KIND_NO_CONNECTION = 2
    const val KIND_ERROR = 3
    /** Diagnostic text, not a classification. */
    const val KIND_INFO = 4

    const val TEXT_NO_CONNECTION = "No connection"
    const val TEXT_UNSURE = "Not sure — check city guide"

    const val MAX_PAYLOAD = 4 * 1024 * 1024
    private const val MAGIC = 0x4242

    class Frame(val type: Int, val payload: ByteArray)

    /** Blocking read of one frame. Throws IOException when the socket closes or sends garbage. */
    fun read(input: DataInputStream): Frame {
        val magic = input.readUnsignedShort()
        if (magic != MAGIC) throw IOException("bad frame magic 0x%04x".format(magic))
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad frame length $len")
        val payload = ByteArray(len)
        input.readFully(payload)
        return Frame(type, payload)
    }

    /** Header + parts, without joining the parts first (the JPEG is not copied). One writer at a time. */
    fun write(out: DataOutputStream, type: Int, vararg parts: ByteArray) {
        out.writeShort(MAGIC)
        out.writeByte(type)
        out.writeInt(parts.sumOf { it.size })
        for (p in parts) out.write(p)
        out.flush()
    }

    fun ints(vararg values: Int): ByteArray {
        val b = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            b[i * 4] = (v ushr 24).toByte()
            b[i * 4 + 1] = (v ushr 16).toByte()
            b[i * 4 + 2] = (v ushr 8).toByte()
            b[i * 4 + 3] = v.toByte()
        }
        return b
    }

    fun intAt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xff) shl 24) or ((b[offset + 1].toInt() and 0xff) shl 16) or
            ((b[offset + 2].toInt() and 0xff) shl 8) or (b[offset + 3].toInt() and 0xff)

    fun strings(vararg values: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { d -> values.forEach { d.writeUTF(it) } }
        return bytes.toByteArray()
    }

    fun stringsAt(b: ByteArray, offset: Int, count: Int): List<String> {
        val d = DataInputStream(ByteArrayInputStream(b, offset, b.size - offset))
        return List(count) { d.readUTF() }
    }
}
