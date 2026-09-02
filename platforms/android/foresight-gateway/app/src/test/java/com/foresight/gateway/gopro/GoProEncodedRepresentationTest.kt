package com.foresight.gateway.gopro

import org.junit.Assert.assertEquals
import org.junit.Test

class GoProEncodedRepresentationTest {
    @Test
    fun `h264 detects annex b start codes`() {
        assertEquals(
            GoProH264Representation.ANNEX_B,
            GoProEncodedRepresentation.detectH264Sample(byteArrayOf(0, 0, 0, 1, 0x65.toByte()), 4),
        )
    }

    @Test
    fun `h264 detects only valid avcc length prefixes`() {
        assertEquals(
            GoProH264Representation.AVCC,
            GoProEncodedRepresentation.detectH264Sample(byteArrayOf(0, 0, 0, 2, 0x65.toByte(), 0x88.toByte()), 4),
        )
        assertEquals(
            GoProH264Representation.UNKNOWN,
            GoProEncodedRepresentation.detectH264Sample(byteArrayOf(0, 0, 0, 5, 0x65.toByte()), 4),
        )
    }

    @Test
    fun `aac detects adts raw and malformed payloads conservatively`() {
        assertEquals(
            GoProAacRepresentation.ADTS,
            GoProEncodedRepresentation.detectAacSample(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0), false),
        )
        assertEquals(
            GoProAacRepresentation.RAW_AAC,
            GoProEncodedRepresentation.detectAacSample(byteArrayOf(0x11, 0x22), true),
        )
        assertEquals(
            GoProAacRepresentation.UNKNOWN,
            GoProEncodedRepresentation.detectAacSample(byteArrayOf(), true),
        )
    }
}
