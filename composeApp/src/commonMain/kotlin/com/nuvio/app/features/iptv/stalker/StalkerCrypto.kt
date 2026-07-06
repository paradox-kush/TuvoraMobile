package com.nuvio.app.features.iptv.stalker

/**
 * Minimal MD5 / SHA-256 hex for the Stalker device-identity derivation. KMP commonMain has no
 * built-in digest, so this is platform-backed (JVM [java.security.MessageDigest] / iOS CommonCrypto),
 * mirroring [com.nuvio.app.features.profiles.ProfilePinCrypto]. Inputs are never empty (a MAC).
 */
internal expect object StalkerCrypto {
    fun md5Hex(value: String): String
    fun sha256Hex(value: String): String
}
