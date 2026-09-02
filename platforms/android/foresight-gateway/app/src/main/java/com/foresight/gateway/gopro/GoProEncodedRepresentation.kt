package com.foresight.gateway.gopro

/** Pure mirrors of B1a's conservative native payload classification rules for JVM tests. */
object GoProEncodedRepresentation {
    fun detectH264Sample(data: ByteArray, nalLengthSize: Int?): GoProH264Representation = when {
        hasAnnexBStartCode(data) -> GoProH264Representation.ANNEX_B
        nalLengthSize != null && hasLengthPrefixedNals(data, nalLengthSize) -> GoProH264Representation.AVCC
        else -> GoProH264Representation.UNKNOWN
    }

    fun detectAacSample(data: ByteArray, hasAudioSpecificConfig: Boolean): GoProAacRepresentation = when {
        hasAdtsSyncword(data) -> GoProAacRepresentation.ADTS
        data.isNotEmpty() && hasAudioSpecificConfig -> GoProAacRepresentation.RAW_AAC
        else -> GoProAacRepresentation.UNKNOWN
    }

    private fun hasAnnexBStartCode(data: ByteArray): Boolean =
        data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))

    private fun hasLengthPrefixedNals(data: ByteArray, lengthSize: Int): Boolean {
        if (data.isEmpty() || lengthSize !in 1..4) return false
        var offset = 0
        var nalCount = 0
        while (offset + lengthSize <= data.size) {
            var nalSize = 0
            repeat(lengthSize) { index -> nalSize = (nalSize shl 8) or (data[offset + index].toInt() and 0xff) }
            offset += lengthSize
            if (nalSize <= 0 || nalSize > data.size - offset) return false
            offset += nalSize
            nalCount += 1
        }
        return nalCount > 0 && offset == data.size
    }

    private fun hasAdtsSyncword(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xff.toByte() && (data[1].toInt() and 0xf6) == 0xf0
}
