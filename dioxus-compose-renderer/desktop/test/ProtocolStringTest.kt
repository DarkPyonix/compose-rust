package dioxus.compose.test

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.protocol.ProtocolException
import dioxus.compose.protocol.PropertyValue

/**
 * What the Renderer accepts as a string, byte for byte.
 *
 * Strings live in the Host's arena and are read where they lie, so the decoder decides on
 * its own whether a range of bytes is text. It has to make exactly the decisions the
 * platform's own UTF-8 decoding makes, and it has to refuse a malformed range rather than
 * turn it into replacement characters: bad bytes mean the two sides disagree about the
 * arena, and the Renderer's answer to that is to report it, not to draw something.
 *
 * These are the cases an in-place validator gets wrong. The interesting ones are the
 * sequences that are well-formed by shape and forbidden by Unicode anyway: an overlong
 * encoding, a surrogate half, a code point past U+10FFFF.
 */
class ProtocolStringTest {

    /** One `SetProp(node, Text, <bytes>)` record whose string payload is `payload` verbatim. */
    private fun textRecord(payload: ByteArray): ByteBuffer {
        val records = ENVELOPE_BYTES + SET_PROP_BYTES
        val out = ByteBuffer.allocate(records + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0)
        out.putShort(ENVELOPE_BYTES.toShort())
        out.putInt(records)
        out.putInt(1)
        out.putShort(2)
        out.putShort(SET_PROP_BYTES.toShort())
        out.putInt(7)
        out.putShort(1)
        out.putShort(1)
        out.putInt(records)
        out.putInt(payload.size)
        out.putInt(0)
        out.put(payload)
        out.rewind()
        return out
    }

    private fun decodeText(payload: ByteArray): String {
        var decoded: Mutation? = null
        Protocol.decode(textRecord(payload)) { decoded = it }
        return ((decoded as Mutation.SetProp).value as PropertyValue.Text).value
    }

    @Test
    fun pr4_a_string_decodes_to_what_the_platform_decodes_it_to() {
        for (text in VALID) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            assertEquals(
                text,
                decodeText(bytes),
                "the decoder read ${bytes.size} bytes of UTF-8 as something else than the text " +
                    "they were encoded from",
            )
        }
    }

    /** Length is bytes, not characters, so a multi-byte string must not be truncated. */
    @Test
    fun pr4_a_multibyte_string_keeps_every_byte_of_its_length() {
        val text = "한글 텍스트 🌗 combining ǹ"
        assertEquals(text, decodeText(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun nfr7_malformed_utf8_in_a_string_is_a_protocol_error() {
        for ((name, payload) in MALFORMED) {
            val failure = assertFailsWith<ProtocolException>(
                "$name was accepted as text; malformed bytes have to be reported, and " +
                    "silently replacing them with U+FFFD draws nonsense instead",
            ) {
                decodeText(payload)
            }
            assertEquals(
                true,
                failure.message?.contains("not valid UTF-8"),
                "$name failed for the wrong reason: ${failure.message}",
            )
        }
    }

    /**
     * The platform agrees with every judgement above. If the JDK were to start accepting
     * one of these, the two sides of the boundary would no longer read the same arena the
     * same way, and this is where that shows up rather than in a drawing.
     */
    @Test
    fun nfr7_the_platform_refuses_the_same_bytes_the_decoder_refuses() {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        for ((name, payload) in MALFORMED) {
            assertFailsWith<java.nio.charset.CharacterCodingException>(
                "the platform accepts $name, which the decoder refuses",
            ) {
                decoder.reset().decode(ByteBuffer.wrap(payload))
            }
        }
    }

    private companion object {
        const val ENVELOPE_BYTES = 12
        const val SET_PROP_BYTES = 24

        val VALID = listOf(
            "",
            "a",
            "ascii only",
            "\u0000ends and begins with nul\u0000",
            "\u007f",
            "\u0080",
            "ǹ",
            "é accented",
            "한글",
            "߿",
            "ࠀ",
            "￿",
            "🌗 a code point above the basic plane",
            "􏿿",
            "mixed: a é 한 🌗 z",
        )

        /** Named so a failure says which shape of bad input got through. */
        val MALFORMED = listOf(
            "a lone continuation byte" to byteArrayOf(0x80.toByte()),
            "a continuation byte after text" to byteArrayOf(0x61, 0xbf.toByte()),
            "0xfe, which starts nothing" to byteArrayOf(0xfe.toByte()),
            "0xff, which starts nothing" to byteArrayOf(0xff.toByte()),
            "a two byte lead with nothing after it" to byteArrayOf(0xc3.toByte()),
            "a two byte lead followed by ASCII" to byteArrayOf(0xc3.toByte(), 0x61),
            "a three byte sequence one byte short" to byteArrayOf(0xe0.toByte(), 0xa0.toByte()),
            "a four byte sequence one byte short" to
                byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x8c.toByte()),
            "an overlong encoding of nul" to byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            "an overlong encoding of '/'" to byteArrayOf(0xc1.toByte(), 0xaf.toByte()),
            "an overlong three byte encoding of U+007f" to
                byteArrayOf(0xe0.toByte(), 0x81.toByte(), 0xbf.toByte()),
            "an overlong four byte encoding of U+07ff" to
                byteArrayOf(0xf0.toByte(), 0x8d.toByte(), 0xbf.toByte(), 0xbf.toByte()),
            "the first surrogate half, U+D800" to
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            "the last surrogate half, U+DFFF" to
                byteArrayOf(0xed.toByte(), 0xbf.toByte(), 0xbf.toByte()),
            "U+110000, one past the last code point" to
                byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            "0xf5, which starts nothing" to
                byteArrayOf(0xf5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            "a five byte sequence" to
                byteArrayOf(
                    0xf8.toByte(), 0x88.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                ),
            "valid text with one bad byte in the middle" to
                byteArrayOf(0x61, 0x62, 0xc3.toByte(), 0x28, 0x63),
        )
    }
}
