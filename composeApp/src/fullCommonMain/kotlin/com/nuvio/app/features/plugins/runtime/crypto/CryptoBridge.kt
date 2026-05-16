package com.nuvio.app.features.plugins.runtime.crypto

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.nuvio.app.features.plugins.runtime.host.HostModule
import com.nuvio.app.features.plugins.pluginDigestHex
import com.nuvio.app.features.plugins.pluginHmacHex
import com.nuvio.app.features.plugins.pluginBase64Encode
import com.nuvio.app.features.plugins.pluginBase64Decode
import com.nuvio.app.features.plugins.pluginUtf8ToHex
import com.nuvio.app.features.plugins.pluginHexToUtf8
import com.nuvio.app.features.plugins.pluginGetRandomValues
import com.nuvio.app.features.plugins.pluginDigest
import com.nuvio.app.features.plugins.pluginPbkdf2
import com.nuvio.app.features.plugins.pluginAesDecrypt
import com.nuvio.app.features.plugins.pluginAesEncrypt
import com.nuvio.app.features.plugins.pluginSign
import com.nuvio.app.features.plugins.pluginVerify

internal class CryptoBridge : HostModule {
    override fun register(runtime: QuickJs) {
        // --- Binary-Safe Bridges (New) ---
        
        runtime.function("__crypto_get_random_values") { args ->
            val length = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            runCatching {
                pluginGetRandomValues(length)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_digest_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            runCatching {
                pluginDigest(algorithm, data)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_pbkdf2_raw") { args ->
            val password = args.getOrNull(0) as? ByteArray ?: ByteArray(0)
            val salt = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
            val keySizeBits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
            val algorithm = args.getOrNull(4)?.toString() ?: "SHA256"
            runCatching {
                pluginPbkdf2(password, salt, iterations, keySizeBits, algorithm)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_aes_encrypt_raw") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            val iv = args.getOrNull(2) as? ByteArray ?: ByteArray(0)
            val data = args.getOrNull(3) as? ByteArray ?: ByteArray(0)
            runCatching {
                pluginAesEncrypt(mode, key, iv, data)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_aes_decrypt_raw") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            val iv = args.getOrNull(2) as? ByteArray ?: ByteArray(0)
            val data = args.getOrNull(3) as? ByteArray ?: ByteArray(0)
            runCatching {
                pluginAesDecrypt(mode, key, iv, data)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_sign_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val privateKey = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            val data = args.getOrNull(2) as? ByteArray ?: ByteArray(0)
            runCatching {
                pluginSign(algorithm, privateKey, data)
            }.getOrElse { ByteArray(0) }
        }

        runtime.function("__crypto_verify_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val publicKey = args.getOrNull(1) as? ByteArray ?: ByteArray(0)
            val signature = args.getOrNull(2) as? ByteArray ?: ByteArray(0)
            val data = args.getOrNull(3) as? ByteArray ?: ByteArray(0)
            runCatching {
                pluginVerify(algorithm, publicKey, signature, data)
            }.getOrDefault(false)
        }

        // --- Legacy Hex/String Bridges (Backward Compatibility) ---

        runtime.function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1)?.toString() ?: ""
            runCatching {
                pluginDigestHex(algorithm, data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString() ?: ""
            runCatching {
                pluginHmacHex(algorithm, key, data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_base64_encode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginBase64Encode(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_base64_decode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginBase64Decode(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_utf8_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginUtf8ToHex(data)
            }.getOrDefault("")
        }

        runtime.function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                pluginHexToUtf8(data)
            }.getOrDefault("")
        }
    }
}
