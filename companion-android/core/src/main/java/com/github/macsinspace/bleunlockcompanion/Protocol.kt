package com.github.macsinspace.bleunlockcompanion

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol shared with the Mac (BLEUnlock/Companion.swift). Keep in sync.
 *
 * Advert (legacy, connectable):  128-bit service UUID [SERVICE_UUID]
 * Scan response:                 service data for SERVICE_UUID =
 *                                [VERSION][keyId: 3 bytes][token: 8 bytes]
 *   token   = HMAC-SHA256(K, "adv" || u32be(unixTime / WINDOW_SECONDS))[0..8]
 *   keyId   = SHA-256("id" || K)[0..3]
 * Challenge characteristic [CHALLENGE_UUID]: the Mac writes a 16-byte nonce, then reads
 *   HMAC-SHA256(K, "unlock" || nonce)[0..16]
 * Pairing string (QR):           "bleunlock-companion:1:<base64url K (32 bytes)>"
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("B1E0C0DE-0001-4C0C-8A11-6E0B1EC0FFEE")
    val CHALLENGE_UUID: UUID = UUID.fromString("B1E0C0DE-0002-4C0C-8A11-6E0B1EC0FFEE")
    const val VERSION: Byte = 1
    const val WINDOW_SECONDS = 600L
    const val NONCE_LEN = 16
    const val RESPONSE_LEN = 16
    private const val PAIR_PREFIX = "bleunlock-companion:1:"

    fun parsePairing(text: String): ByteArray? {
        val t = text.trim()
        if (!t.startsWith(PAIR_PREFIX)) return null
        return try {
            val k = Base64.decode(t.removePrefix(PAIR_PREFIX), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (k.size == 32) k else null
        } catch (_: IllegalArgumentException) { null }
    }

    fun keyId(k: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("id".toByteArray() + k).copyOf(3)

    fun window(nowMillis: Long = System.currentTimeMillis()): Long = nowMillis / 1000 / WINDOW_SECONDS

    fun advPayload(key: SecretKey, keyId: ByteArray, window: Long = window()): ByteArray {
        val w = ByteBuffer.allocate(4).putInt(window.toInt()).array()
        val token = hmac(key, "adv".toByteArray() + w).copyOf(8)
        return byteArrayOf(VERSION) + keyId + token
    }

    fun challengeResponse(key: SecretKey, nonce: ByteArray): ByteArray =
        hmac(key, "unlock".toByteArray() + nonce).copyOf(RESPONSE_LEN)

    private fun hmac(key: SecretKey, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(key); doFinal(data) }
}

/** Pairing secret lives in Android Keystore (non-exportable); only the public key id is in prefs. */
object KeyStorage {
    private const val ALIAS = "bleunlock-companion-k"
    private const val PREFS = "companion"

    fun store(context: Context, k: ByteArray) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.setEntry(
            ALIAS,
            KeyStore.SecretKeyEntry(SecretKeySpec(k, "HmacSHA256")),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build()
        )
        prefs(context).edit().putString("keyId", Protocol.keyId(k).toHex()).apply()
        k.fill(0)
    }

    fun key(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    fun keyId(context: Context): ByteArray? = prefs(context).getString("keyId", null)?.hexToBytes()

    fun isPaired(context: Context): Boolean = keyId(context) != null && key() != null

    fun clear(context: Context) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        prefs(context).edit().clear().apply()
    }

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
