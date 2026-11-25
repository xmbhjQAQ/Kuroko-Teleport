package com.kuroko.teleport.rf

import com.kuroko.teleport.diag.DiagnosticCollector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

class RfFormattingTest {

    private fun hexToBytes(hex: String): ByteArray {
        assertEquals("Hex length must be even", 0, hex.length % 2)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        assertFalse("Decoded UTF-8 must never contain U+FFFD", decoded.contains('\uFFFD'))
        return decoded
    }

    private fun assertNoDanglingSurrogates(s: String) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("Dangling high surrogate at index $i", i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
                i += 2
            } else {
                assertFalse("Dangling low surrogate at index $i", Character.isLowSurrogate(ch))
                i += 1
            }
        }
    }

    @Test
    fun truncateUtf8_chineseWifiSsid32ByteBoundary_preservesWholeChars() {
        // 11 Chinese chars = 33 UTF-8 bytes; must truncate cleanly to 10 chars = 30 bytes
        val input = "\u4E2D".repeat(11)
        assertEquals(33, input.toByteArray(StandardCharsets.UTF_8).size)

        val truncated = RfFormatting.truncateUtf8(input, 32)
        assertEquals("\u4E2D".repeat(10), truncated)
        assertEquals(30, truncated.toByteArray(StandardCharsets.UTF_8).size)
        assertNoDanglingSurrogates(truncated)

        val hex = RfFormatting.utf8ToHex(input, 32)
        assertEquals(60, hex.length)
        val roundTripped = decodeStrictUtf8(hexToBytes(hex))
        assertEquals(truncated, roundTripped)
    }

    @Test
    fun truncateUtf8_chineseBleName24ByteBoundary_exactAndMixedAscii() {
        // 9 Chinese chars = 27 bytes -> exact 8 chars = 24 bytes
        val nineCjk = "\u6D4B".repeat(9)
        val t1 = RfFormatting.truncateUtf8(nineCjk, 24)
        assertEquals("\u6D4B".repeat(8), t1)
        assertEquals(24, t1.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(t1, decodeStrictUtf8(hexToBytes(RfFormatting.utf8ToHex(nineCjk, 24))))

        // "a" (1B) + 8 Chinese chars (24B) = 25B -> "a" + 7 Chinese chars = 22B
        val mixed = "a" + "\u6D4B".repeat(8)
        val t2 = RfFormatting.truncateUtf8(mixed, 24)
        assertEquals("a" + "\u6D4B".repeat(7), t2)
        assertEquals(22, t2.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(t2, decodeStrictUtf8(hexToBytes(RfFormatting.utf8ToHex(mixed, 24))))
    }

    @Test
    fun truncateUtf8_fourByteEmojiAndFlagEmoji_across32_24_15Boundaries() {
        val house = "\uD83C\uDFE0" // U+1F3E0 (4 UTF-8 bytes, 2 UTF-16 chars)
        val rocket = "\uD83D\uDE80" // U+1F680 (4 UTF-8 bytes, 2 UTF-16 chars)
        val flagCn = "\uD83C\uDDE8\uD83C\uDDF3" // 2 regional indicators = 8 UTF-8 bytes

        // 32-byte Wi-Fi boundary with 4-byte emoji + 1-byte prefix (1 + 8*4 = 33B -> 1 + 7*4 = 29B)
        val wifiEmoji = "x" + house.repeat(8)
        val t32 = RfFormatting.truncateUtf8(wifiEmoji, 32)
        assertEquals("x" + house.repeat(7), t32)
        assertEquals(29, t32.toByteArray(StandardCharsets.UTF_8).size)
        assertNoDanglingSurrogates(t32)
        assertEquals(t32, decodeStrictUtf8(hexToBytes(RfFormatting.utf8ToHex(wifiEmoji, 32))))

        // 24-byte BLE boundary with 4-byte emoji + 2-byte prefix (2 + 6*4 = 26B -> 2 + 5*4 = 22B)
        val bleEmoji = "ab" + rocket.repeat(6)
        val t24 = RfFormatting.truncateUtf8(bleEmoji, 24)
        assertEquals("ab" + rocket.repeat(5), t24)
        assertEquals(22, t24.toByteArray(StandardCharsets.UTF_8).size)
        assertNoDanglingSurrogates(t24)
        assertEquals(t24, decodeStrictUtf8(hexToBytes(RfFormatting.utf8ToHex(bleEmoji, 24))))

        // 15-byte Carrier Alpha boundary with multi-codepoint flag emoji (each codepoint 4B -> max 3 codepoints = 12B)
        val alphaFlags = flagCn.repeat(3)
        val t15 = RfFormatting.truncateUtf8(alphaFlags, 15)
        val t15Bytes = t15.toByteArray(StandardCharsets.UTF_8)
        assertTrue("Byte length must be <= 15", t15Bytes.size <= 15)
        assertEquals(12, t15Bytes.size)
        assertNoDanglingSurrogates(t15)
        assertEquals(t15, decodeStrictUtf8(t15Bytes))

        val alphaB64 = RfFormatting.alphaToBase64OrAuto(alphaFlags)
        val b64Decoded = decodeStrictUtf8(Base64.getDecoder().decode(alphaB64))
        assertEquals(t15, b64Decoded)
    }

    @Test
    fun truncateUtf8_ascii_twoByteUtf8_empty_andZeroLimit() {
        assertEquals("hello", RfFormatting.truncateUtf8("hello world", 5))
        assertEquals("", RfFormatting.truncateUtf8("hello", 0))
        assertEquals("", RfFormatting.truncateUtf8("hello", -4))
        assertEquals("", RfFormatting.truncateUtf8("", 32))
        assertEquals("", RfFormatting.utf8ToHex("", 32))
        assertEquals("AUTO", RfFormatting.alphaToBase64OrAuto("   "))

        // 2-byte UTF-8: 'é' (U+00E9) = 2 bytes; "a" + "é"*3 = 7B -> limit 6 -> "a" + "é"*2 = 5B
        val twoByte = "a" + "\u00E9".repeat(3)
        val t6 = RfFormatting.truncateUtf8(twoByte, 6)
        assertEquals("a" + "\u00E9".repeat(2), t6)
        assertEquals(5, t6.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(t6, decodeStrictUtf8(hexToBytes(RfFormatting.utf8ToHex(twoByte, 6))))
    }

    @Test
    fun truncateUtf8_carrierAlphaChinese15ByteBoundary() {
        // 6 Chinese chars = 18B -> truncated to 5 chars = 15B exact
        val sixCjk = "\u4E2D\u56FD\u79FB\u52A8\u901A\u4FE1"
        val b64 = RfFormatting.alphaToBase64OrAuto(sixCjk)
        val decoded = decodeStrictUtf8(Base64.getDecoder().decode(b64))
        assertEquals(5, decoded.length)
        assertEquals(15, decoded.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun executeGmsToggle_revertsToPreviousConfirmedStateOnFailureAndException() = runBlocking {
        // Daemon initially confirms GMS enabled = true; user tries to disable, daemon returns false
        val failedDisable = executeGmsToggle(
            prevConfirmed = true,
            requestedEnabled = false,
            onSetGmsEnabled = { false }
        )
        assertFalse(failedDisable.succeeded)
        assertTrue("Switch must revert to confirmed=true on failure", failedDisable.confirmedState)
        assertEquals(GmsToggleFeedback.FAILED, failedDisable.feedback)

        // Daemon initially confirms GMS enabled = false; user tries to enable, IPC throws exception
        val threwEnable = executeGmsToggle(
            prevConfirmed = false,
            requestedEnabled = true,
            onSetGmsEnabled = { throw IllegalStateException("daemon offline") }
        )
        assertFalse(threwEnable.succeeded)
        assertFalse("Switch must revert to confirmed=false on exception", threwEnable.confirmedState)
        assertEquals(GmsToggleFeedback.FAILED, threwEnable.feedback)

        // Happy paths: enable & disable succeed
        val okDisable = executeGmsToggle(
            prevConfirmed = true,
            requestedEnabled = false,
            onSetGmsEnabled = { true }
        )
        assertTrue(okDisable.succeeded)
        assertFalse(okDisable.confirmedState)
        assertEquals(GmsToggleFeedback.DISABLED, okDisable.feedback)

        val okEnable = executeGmsToggle(
            prevConfirmed = false,
            requestedEnabled = true,
            onSetGmsEnabled = { true }
        )
        assertTrue(okEnable.succeeded)
        assertTrue(okEnable.confirmedState)
        assertEquals(GmsToggleFeedback.ENABLED, okEnable.feedback)
    }

    @Test
    fun diagnosticsGmsProcessSanitizer_filtersNonHostAndPrefixSpoofedNames() {
        val raw = listOf(
            "u:r:gmscore_app:s0 com.google.android.gms",
            "u:r:gmscore_app:s0 com.google.android.gms.persistent",
            "u:r:gmscore_app:s0 com.google.android.gms.unstable",
            "u:r:gmscore_app:s0 com.google.android.gms.ui",
            "u:r:untrusted_app:s0 com.google.android.gmsfake",
            "12345 u:r:gmscore_app:s0 com.google.android.gms"
        ).joinToString("\n")

        val sanitized = DiagnosticCollector.sanitizeGmsProcs(raw)
        assertEquals(
            listOf(
                "u:r:gmscore_app:s0 com.google.android.gms",
                "u:r:gmscore_app:s0 com.google.android.gms.persistent",
                "u:r:gmscore_app:s0 com.google.android.gms.unstable",
                "u:r:gmscore_app:s0 com.google.android.gms.ui"
            ),
            sanitized
        )
        assertTrue(DiagnosticCollector.isGmsFlpHostProcess("u:r:gmscore_app:s0 com.google.android.gms"))
        assertTrue(DiagnosticCollector.isGmsFlpHostProcess("u:r:gmscore_app:s0 com.google.android.gms.persistent"))
        assertFalse(DiagnosticCollector.isGmsFlpHostProcess("u:r:gmscore_app:s0 com.google.android.gms.unstable"))
        assertFalse(DiagnosticCollector.isGmsFlpHostProcess("u:r:gmscore_app:s0 com.google.android.gms.ui"))
    }
}
