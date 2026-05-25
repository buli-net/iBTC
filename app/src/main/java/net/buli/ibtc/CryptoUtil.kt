package net.buli.ibtc
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val ITER = 200_000
    fun encrypt(txt:String, pw:String):String {
        val salt = ByteArray(16).also{SecureRandom().nextBytes(it)}
        val iv = ByteArray(12).also{SecureRandom().nextBytes(it)}
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(),salt,ITER,256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,iv))
        return Base64.encodeToString(salt+iv+c.doFinal(txt.toByteArray()),0)
    }
    fun decrypt(enc:String, pw:String):String {
        val d = Base64.decode(enc,0)
        val salt=d.copyOfRange(0,16); val iv=d.copyOfRange(16,28); val ct=d.copyOfRange(28,d.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(),salt,ITER,256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,iv))
        return String(c.doFinal(ct))
    }
}