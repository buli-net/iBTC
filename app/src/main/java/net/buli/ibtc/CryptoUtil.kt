package net.buli.ibtc

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility object for encrypting and decrypting seed phrases using AES-GCM.
 * Password-based key derivation with PBKDF2.
 */
object CryptoUtil {
    private const val ITERATIONS = 200_000
    private const val KEY_LENGTH = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    /**
     * Encrypts a plaintext string with a password.
     * @param plain the seed phrase to encrypt
     * @param password the password used for encryption
     * @return Base64-encoded ciphertext containing salt + IV + encrypted data
     */
    fun encrypt(plain: String, password: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + ct
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64-encoded ciphertext with the password.
     * @param enc the encrypted data (salt+IV+ct)
     * @param password the password used for decryption
     * @return the original plaintext seed phrase
     */
    fun decrypt(enc: String, password: String): String {
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val salt = data.copyOfRange(0, SALT_LEN)
        val iv = data.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = data.copyOfRange(SALT_LEN + IV_LEN, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    /**
     * Derives an AES key from password and salt using PBKDF2.
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}