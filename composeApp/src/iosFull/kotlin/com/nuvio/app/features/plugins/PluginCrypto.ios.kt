package com.nuvio.app.features.plugins

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.nuvio.app.features.plugins.cryptointerop.CC_MD5
import com.nuvio.app.features.plugins.cryptointerop.CC_MD5_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA1
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA1_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA512
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA512_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CCHmac
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgMD5
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA1
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA512
import com.nuvio.app.features.plugins.cryptointerop.CCKeyDerivationPBKDF
import com.nuvio.app.features.plugins.cryptointerop.kCCPBKDF2
import com.nuvio.app.features.plugins.cryptointerop.kCCPRFHmacAlgSHA1
import com.nuvio.app.features.plugins.cryptointerop.kCCPRFHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.CCCrypt
import com.nuvio.app.features.plugins.cryptointerop.kCCDecrypt
import com.nuvio.app.features.plugins.cryptointerop.kCCAlgorithmAES
import com.nuvio.app.features.plugins.cryptointerop.kCCOptionECBMode
import com.nuvio.app.features.plugins.cryptointerop.kCCEncrypt
import com.nuvio.app.features.plugins.cryptointerop.kCCOptionPKCS7Padding
import com.nuvio.app.features.plugins.cryptointerop.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal fun pluginGetRandomValues(length: Int): ByteArray {
    val bytes = ByteArray(length)
    @OptIn(ExperimentalForeignApi::class)
    SecRandomCopyBytes(kSecRandomDefault, length.toULong(), bytes.refTo(0))
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    val normalized = algorithm.uppercase()
    val output = ByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    data.usePinned { pinnedData ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (data.isNotEmpty()) pinnedData.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0).reinterpret<UByteVar>()

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, data.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, data.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, data.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, data.size.toUInt(), outputPtr)
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    val prf = when (algorithm.uppercase()) {
        "SHA256" -> kCCPRFHmacAlgSHA256
        "SHA1" -> kCCPRFHmacAlgSHA1
        else -> kCCPRFHmacAlgSHA256
    }
    
    val derivedKeyLen = keySizeBits / 8
    val derivedKey = ByteArray(derivedKeyLen)
    
    password.usePinned { pinnedPassword ->
        salt.usePinned { pinnedSalt ->
            derivedKey.usePinned { pinnedDerivedKey ->
                val passwordPtr = if (password.isNotEmpty()) pinnedPassword.addressOf(0).reinterpret<ByteVar>() else null
                val saltPtr = if (salt.isNotEmpty()) pinnedSalt.addressOf(0).reinterpret<UByteVar>() else null
                val derivedKeyPtr = pinnedDerivedKey.addressOf(0).reinterpret<UByteVar>()

                val status = CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = passwordPtr,
                    passwordLen = password.size.toULong(),
                    salt = saltPtr,
                    saltLen = salt.size.toULong(),
                    prf = prf,
                    rounds = iterations.toUInt(),
                    derivedKey = derivedKeyPtr,
                    derivedKeyLen = derivedKeyLen.toULong()
                )
                
                require(status == kCCSuccess) { "PBKDF2 failed with status: $status" }
            }
        }
    }
    
    return derivedKey
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        throw UnsupportedOperationException("AES-GCM Encrypt is not yet implemented on iOS")
    }
    val isEcb = mode.uppercase().contains("ECB")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<kotlinx.cinterop.size_tVar>()
        
        val options = if (isEcb) {
            kCCOptionPKCS7Padding or kCCOptionECBMode
        } else {
            kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCEncrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt Encrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        throw UnsupportedOperationException("AES-GCM Decrypt is not yet implemented on iOS")
    }
    val isEcb = mode.uppercase().contains("ECB")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<kotlinx.cinterop.size_tVar>()
        
        val options = if (isEcb) {
            kCCOptionPKCS7Padding or kCCOptionECBMode
        } else {
            kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCDecrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    throw UnsupportedOperationException("Asymmetric signing is currently implemented natively only on Android")
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    throw UnsupportedOperationException("Asymmetric verification is currently implemented natively only on Android")
}

private fun UByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toString(16).padStart(2, '0')
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = algorithm.uppercase()
    val input = data.encodeToByteArray()
    val output = UByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    input.usePinned { pinnedInput ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (input.isNotEmpty()) pinnedInput.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0)

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, input.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, input.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, input.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, input.size.toUInt(), outputPtr)
            }
        }
    }

    return output.toHex()
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val normalized = algorithm.uppercase()
    val keyBytes = key.encodeToByteArray()
    val input = data.encodeToByteArray()

    val (alg, outputSize) = when (normalized) {
        "MD5" -> kCCHmacAlgMD5 to CC_MD5_DIGEST_LENGTH.toInt()
        "SHA1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH.toInt()
        "SHA256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH.toInt()
        "SHA512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH.toInt()
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }

    val output = UByteArray(outputSize)
    
    keyBytes.usePinned { pinnedKey ->
        input.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                val keyPtr = if (keyBytes.isNotEmpty()) pinnedKey.addressOf(0) else null
                val inputPtr = if (input.isNotEmpty()) pinnedInput.addressOf(0) else null
                val outputPtr = pinnedOutput.addressOf(0)

                CCHmac(
                    alg,
                    keyPtr,
                    keyBytes.size.toULong(),
                    inputPtr,
                    input.size.toULong(),
                    outputPtr,
                )
            }
        }
    }

    return output.toHex()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String =
    Base64.encode(data.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    val normalized = data.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    val decoded = Base64.decode(normalized)
    return decoded.decodeToString()
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToUtf8(hex: String): String {
    val normalized = hex.trim().lowercase()
        .replace(" ", "")
        .removePrefix("0x")
    if (normalized.isEmpty()) return ""

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out.decodeToString()
}
