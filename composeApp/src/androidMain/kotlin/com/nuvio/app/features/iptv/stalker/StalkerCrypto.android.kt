package com.nuvio.app.features.iptv.stalker

import java.security.MessageDigest

actual object StalkerCrypto {
    actual fun md5Hex(value: String): String = hex("MD5", value)
    actual fun sha256Hex(value: String): String = hex("SHA-256", value)

    private fun hex(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm).digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}
