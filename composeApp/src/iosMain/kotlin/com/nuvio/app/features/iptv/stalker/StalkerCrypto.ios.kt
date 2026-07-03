package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.plugins.cryptointerop.CC_MD5
import com.nuvio.app.features.plugins.cryptointerop.CC_MD5_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo

@OptIn(ExperimentalForeignApi::class)
actual object StalkerCrypto {
    actual fun md5Hex(value: String): String {
        val input = value.encodeToByteArray()
        val output = UByteArray(CC_MD5_DIGEST_LENGTH.toInt())
        CC_MD5(input.refTo(0), input.size.toUInt(), output.refTo(0))
        return output.joinToString(separator = "") { byte -> byte.toString(16).padStart(2, '0') }
    }

    actual fun sha256Hex(value: String): String {
        val input = value.encodeToByteArray()
        val output = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        CC_SHA256(input.refTo(0), input.size.toUInt(), output.refTo(0))
        return output.joinToString(separator = "") { byte -> byte.toString(16).padStart(2, '0') }
    }
}
