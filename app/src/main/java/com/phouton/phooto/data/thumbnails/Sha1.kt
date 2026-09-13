package com.phouton.phooto.data.thumbnails

import java.security.MessageDigest

/**
 * Lower-case hex SHA-1 of [input]. Used to derive stable, filesystem-safe cache
 * keys from media URIs (shared by [ThumbCache] and [VideoFrameCache]).
 */
internal fun sha1Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        if (v < 0x10) hex.append('0')
        hex.append(v.toString(16))
    }
    return hex.toString()
}
