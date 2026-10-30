package cloud.ohiofiles.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Web-compatible ohiofiles-chat-e2ee-v1: P-256 ECDH, HKDF-SHA256, AES-256-GCM. */
object ChatCrypto {
    private const val PROTOCOL = "ohiofiles-chat-e2ee-v1"
    data class Identity(val sessionId: String, val deviceId: String, val pair: KeyPair) {
        val publicKey: String get() = encode(rawPublic(pair.public as ECPublicKey))
    }
    data class Target(val sessionId: String, val deviceId: String, val publicKey: String)
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun random(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun coordinate(value: BigInteger): ByteArray = value.toByteArray().let { input -> ByteArray(32).also { output ->
        val length = minOf(input.size, 32); input.copyInto(output, 32 - length, input.size - length)
    } }
    private fun rawPublic(key: ECPublicKey) = byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    private fun publicKey(raw: String): PublicKey {
        val point = decode(raw); require(point.size == 65 && point[0] == 4.toByte()) { "Invalid chat public key" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params))
    }
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun hmac(key: ByteArray, content: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(content)
    private fun wrappingKey(privateKey: PrivateKey, publicKey: PublicKey, context: String, target: Target): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(privateKey); doPhase(publicKey, true) }.generateSecret()
        val extracted = hmac(decode(context), shared)
        return hmac(extracted, bytes("$PROTOCOL|wrap|${target.sessionId}|${target.deviceId}") + byteArrayOf(1))
    }
    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: String, input: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(bytes(aad)); doFinal(input)
    }
    fun encrypt(text: String, senderId: String, recipientId: String, targets: List<Target>): JsonObject {
        require(targets.isNotEmpty()) { "No encrypted chat devices are available" }
        val context = encode(random(18)); val messageKey = random(32); val iv = random(12); val ephemeral = pair()
        val encrypted = cipher(Cipher.ENCRYPT_MODE, messageKey, iv, "$PROTOCOL|body|$context|$senderId|$recipientId", bytes(text))
        return buildJsonObject {
            put("version", 1); put("ciphertext", encode(encrypted)); put("iv", encode(iv)); put("context", context)
            put("ephemeralPublicKey", encode(rawPublic(ephemeral.public as ECPublicKey)))
            putJsonArray("envelopes") { targets.distinctBy { it.sessionId to it.deviceId }.forEach { target ->
                val wrapIv = random(12)
                val wrapped = cipher(Cipher.ENCRYPT_MODE, wrappingKey(ephemeral.private, publicKey(target.publicKey), context, target), wrapIv,
                    "$PROTOCOL|envelope|$context|${target.sessionId}|${target.deviceId}", messageKey)
                add(buildJsonObject { put("sessionId", target.sessionId); put("deviceId", target.deviceId); put("wrappedKey", encode(wrapped)); put("iv", encode(wrapIv)) })
            } }
        }
    }
    fun decrypt(encrypted: JsonObject, identity: Identity, senderId: String, recipientId: String): String {
        fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content ?: error("Incomplete encrypted message")
        require(encrypted["version"]?.jsonPrimitive?.int == 1) { "Unsupported encrypted message" }
        val envelope = encrypted["envelope"] as? JsonObject ?: error("This message is unavailable on this device")
        val context = encrypted.string("context"); val target = Target(identity.sessionId, identity.deviceId, identity.publicKey)
        val key = cipher(Cipher.DECRYPT_MODE, wrappingKey(identity.pair.private, publicKey(encrypted.string("ephemeralPublicKey")), context, target),
            decode(envelope.string("iv")), "$PROTOCOL|envelope|$context|${identity.sessionId}|${identity.deviceId}", decode(envelope.string("wrappedKey")))
        return cipher(Cipher.DECRYPT_MODE, key, decode(encrypted.string("iv")), "$PROTOCOL|body|$context|$senderId|$recipientId", decode(encrypted.string("ciphertext"))).toString(Charsets.UTF_8)
    }

    /** Private ECDH keys are encrypted at rest using a non-exportable Android Keystore AES key. */
    @Synchronized fun identity(context: Context, server: String, sessionId: String, deviceId: String): Identity {
        val prefs = context.getSharedPreferences("chat_identity_v1", Context.MODE_PRIVATE)
        val id = MessageDigest.getInstance("SHA-256").digest(bytes("$server|$sessionId|$deviceId")).joinToString("") { "%02x".format(it) }
        val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "ohiofiles.chat.identity.v1"
        val key = (vault.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val stored = prefs.getString(id, null)
        val pair = if (stored != null) {
            val parts = stored.split('.'); require(parts.size == 3) { "Stored chat key is damaged" }
            val privateBytes = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(parts[0]))); doFinal(decode(parts[1])) }
            KeyPair(publicKey(parts[2]), KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
        } else {
            pair().also { generated ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                val encrypted = cipher.doFinal(generated.private.encoded)
                check(prefs.edit().putString(id, "${encode(cipher.iv)}.${encode(encrypted)}.${encode(rawPublic(generated.public as ECPublicKey))}").commit()) { "Chat identity could not be saved" }
            }
        }
        return Identity(sessionId, deviceId, pair)
    }
    fun deviceId(context: Context): String = context.getSharedPreferences("chat_identity_v1", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { check(prefs.edit().putString("device_id", it).commit()) }
    }
}
