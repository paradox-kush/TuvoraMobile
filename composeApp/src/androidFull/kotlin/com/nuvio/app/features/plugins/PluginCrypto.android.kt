package com.nuvio.app.features.plugins

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val secureRandom = SecureRandom()

internal fun pluginGetRandomValues(length: Int): ByteArray {
    val bytes = ByteArray(length)
    secureRandom.nextBytes(bytes)
    return bytes
}

internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    return MessageDigest.getInstance(algorithm.uppercase()).digest(data)
}

internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    val normalizedAlgo = when (algorithm.uppercase()) {
        "SHA256" -> "PBKDF2WithHmacSHA256"
        "SHA1" -> "PBKDF2WithHmacSHA1"
        else -> "PBKDF2WithHmacSHA256"
    }
    val factory = SecretKeyFactory.getInstance(normalizedAlgo)
    val passChars = password.map { (it.toInt() and 0xFF).toChar() }.toCharArray()
    val spec = PBEKeySpec(passChars, salt, iterations, keySizeBits)
    return factory.generateSecret(spec).encoded
}

internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = when (mode.uppercase()) {
        "AES-CBC", "CBC" -> "AES/CBC/PKCS5Padding"
        "AES-GCM", "GCM" -> "AES/GCM/NoPadding"
        "AES-ECB", "ECB" -> "AES/ECB/PKCS5Padding"
        else -> "AES/CBC/PKCS5Padding"
    }

    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = when (mode.uppercase()) {
        "AES-CBC", "CBC" -> "AES/CBC/PKCS5Padding"
        "AES-GCM", "GCM" -> "AES/GCM/NoPadding"
        "AES-ECB", "ECB" -> "AES/ECB/PKCS5Padding"
        else -> "AES/CBC/PKCS5Padding"
    }

    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
        "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
        "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
        else -> "RSA" to "SHA256withRSA"
    }
    val factory = KeyFactory.getInstance(keyAlgo)
    val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initSign(privKey)
    sig.update(data)
    return sig.sign()
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
        "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
        "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
        else -> "RSA" to "SHA256withRSA"
    }
    val factory = KeyFactory.getInstance(keyAlgo)
    val pubKey = factory.generatePublic(X509EncodedKeySpec(publicKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initVerify(pubKey)
    sig.update(data)
    return sig.verify(signature)
}

internal fun pluginDigestHex(algorithm: String, data: String): String {
    val digest = pluginDigest(algorithm, data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val normalized = when (algorithm.uppercase()) {
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        "MD5" -> "HmacMD5"
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }
    val mac = Mac.getInstance(normalized)
    mac.init(SecretKeySpec(key.encodeToByteArray(), normalized))
    val digest = mac.doFinal(data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
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
