package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.DeterministicSeed
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

data class WalletInfo(val id: String, val name: String)
data class TransactionInfo(val txId: String,val amount: Double,val type: String,val time: Date)
data class FeeRates(val slow: Int,val normal: Int,val fast: Int)

class WalletManager(private val ctx: Context) {

    private val params = MainNetParams.get()

    private var active: WalletInfo? = null
    private var cachedSeed: String? = null
    private var cachedPassword: CharArray? = null

    private val prefs =
        ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)

    private var lastPrice =
        prefs.getFloat("last_price",65000f).toDouble()

    fun hasWallets(): Boolean {
        return prefs.all.keys.any { it.endsWith("_seed") }
    }

    fun getActive(): WalletInfo? = active

    fun getActiveId(): String? = active?.id

    fun unlock(id: String,password: String): Boolean {

        return try {

            val enc =
                prefs.getString("${id}_seed","") ?: return false

            val seed =
                CryptoUtil.decrypt(enc,password)

            val name =
                prefs.getString("${id}_name","Wallet") ?: "Wallet"

            cachedSeed = seed
            cachedPassword = password.toCharArray()

            active = WalletInfo(id,name)

            true

        } catch (e: Exception) {
            false
        }
    }

    fun lock() {

        cachedPassword?.fill('0')

        cachedPassword = null
        cachedSeed = null
    }

    fun create(name: String,password: String): WalletInfo {

        val id = UUID.randomUUID().toString()

        val seed =
            DeterministicSeed(
                SecureRandom(),
                128,
                ""
            )

        val mnemonic =
            seed.mnemonicCode!!.joinToString(" ")

        val walletName =
            if (name.isBlank()) "Ví Bitcoin" else name

        val address =
            getNativeSegwitAddress(mnemonic)

        val enc =
            CryptoUtil.encrypt(mnemonic,password)

        prefs.edit()
            .putString("${id}_name",walletName)
            .putString("${id}_seed",enc)
            .putString("${id}_address",address)
            .apply()

        val info =
            WalletInfo(id,walletName)

        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        active = info

        return info
    }

    private fun deriveKey(
        seedPhrase: String,
        purpose: Int
    ): DeterministicKey {

        val seed =
            DeterministicSeed(
                seedPhrase.split(" "),
                null,
                "",
                0L
            )

        val seedBytes = seed.seedBytes!!

        var key =
            HDKeyDerivation.createMasterPrivateKey(seedBytes)

        val path = listOf(
            ChildNumber(purpose,true),
            ChildNumber(0,true),
            ChildNumber(0,true),
            ChildNumber.ZERO,
            ChildNumber.ZERO
        )

        for (p in path) {
            key = HDKeyDerivation.deriveChildKey(key,p)
        }

        return key
    }

    private fun getLegacyAddress(seedPhrase: String): String {

        return try {

            val key =
                deriveKey(seedPhrase,44)

            LegacyAddress
                .fromKey(params,key)
                .toString()

        } catch (_: Exception) {
            ""
        }
    }

    private fun getNestedSegwitAddress(seedPhrase: String): String {

        return try {

            val key =
                deriveKey(seedPhrase,49)

            SegwitAddress
                .fromKey(params,key)
                .toString()

        } catch (_: Exception) {
            ""
        }
    }


    private fun getNativeSegwitAddress(seedPhrase: String): String {

        return try {

            val key =
                deriveKey(seedPhrase,84)

            SegwitAddress
                .fromKey(params,key)
                .toString()

        } catch (_: Exception) {
            ""
        }
    }

    fun import(
        name: String,
        phrase: String,
        password: String
    ): WalletInfo? {

        return try {

            val clean =
                phrase.trim()
                    .lowercase()
                    .replace(Regex("\\s+")," ")

            val words =
                clean.split(" ")

            if (words.size != 12 && words.size != 24) {
                return null
            }

            DeterministicSeed(words,null,"",0L)

            val id =
                UUID.randomUUID().toString()

            val walletName =
                if (name.isBlank()) "Imported Wallet" else name

            val address =
                getNativeSegwitAddress(clean)

            val enc =
                CryptoUtil.encrypt(clean,password)

            prefs.edit()
                .putString("${id}_name",walletName)
                .putString("${id}_seed",enc)
                .putString("${id}_address",address)
                .apply()

            val info =
                WalletInfo(id,walletName)

            cachedSeed = clean
            cachedPassword = password.toCharArray()
            active = info

            info

        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String) {

        lock()

        prefs.edit()
            .remove("${id}_name")
            .remove("${id}_seed")
            .remove("${id}_attempts")
            .remove("${id}_address")
            .commit()
    }

    fun getSeed(): String = cachedSeed ?: ""

    fun getAddress(): String {

        val id = active?.id ?: return ""

        return prefs.getString(
            "${id}_address",
            ""
        ) ?: ""
    }

    fun getBalance(): Double = 0.0

    fun getTransactions(): List<TransactionInfo> = emptyList()

    fun estimateFee(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): Double {

        return (feeRateSatVb * 250.0) / 100000000.0
    }

    fun send(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): String {

        return "Send mainnet sẽ làm tiếp"
    }

    fun price(): Double {

        return try {

            val json =
                httpGet(
                    "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"
                )

            if (json.isBlank()) {
                return lastPrice
            }

            val obj = JSONObject(json)

            val rate =
                obj.getString("price").toDouble()

            lastPrice = rate

            prefs.edit()
                .putFloat("last_price",rate.toFloat())
                .apply()

            rate

        } catch (_: Exception) {
            lastPrice
        }
    }

    fun getFeeRates(): FeeRates {

        return FeeRates(
            slow = 5,
            normal = 10,
            fast = 20
        )
    }

    private fun httpGet(url: String): String {

        return try {

            val conn =
                URL(url).openConnection() as HttpURLConnection

            conn.requestMethod = "GET"

            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0"
            )

            conn.inputStream.bufferedReader().use {
                it.readText()
            }

        } catch (_: Exception) {
            ""
        }
    }

    fun init() {}

    fun stop() {}

    fun onProgress(
        cb: (Int,String) -> Unit
    ) {
        cb(100,"Ví sẵn sàng")
    }

    fun changePassword(
        oldPassword: String,
        newPassword: String
    ): Boolean {

        return try {

            val id =
                active?.id ?: return false

            val enc =
                prefs.getString("${id}_seed", null)
                    ?: return false

            val seed =
                CryptoUtil.decrypt(enc, oldPassword)

            val newEnc =
                CryptoUtil.encrypt(seed, newPassword)

            prefs.edit()
                .putString("${id}_seed", newEnc)
                .apply()

            cachedPassword =
                newPassword.toCharArray()

            true

        } catch (e: Exception) {

            false
        }
    }

    fun rename(
        newName: String
    ) {

        val id =
            active?.id ?: return

        prefs.edit()
            .putString("${id}_name", newName)
            .apply()

        active =
            WalletInfo(id,newName)
    }
}
