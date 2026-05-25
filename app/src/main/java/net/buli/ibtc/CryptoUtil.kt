package net.buli.ibtc

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object CryptoUtil {
    private const val ITER = 10000
    private const val KEYLEN = 256
    private const val SALT = 16
    private const val IV = 12
    fun encrypt(txt: String, pass: String): String {
        val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, ITER, KEYLEN))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, iv))
        val enc = c.doFinal(txt.toByteArray())
        return Base64.encodeToString(salt + iv + enc, Base64.NO_WRAP)
    }
    fun decrypt(b64: String, pass: String): String {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val salt = all.copyOfRange(0, SALT)
        val iv = all.copyOfRange(SALT, SALT + IV)
        val data = all.copyOfRange(SALT + IV, all.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, ITER, KEYLEN))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, iv))
        return String(c.doFinal(data))
    }
}