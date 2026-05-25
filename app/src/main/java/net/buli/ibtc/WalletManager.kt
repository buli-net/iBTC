package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

data class WalletInfo(val id: String, val name: String)
data class TransactionInfo(val txId: String, val amount: Double, val type: String, val time: Date)
data class FeeRates(val slow: Int, val normal: Int, val fast: Int)

class WalletManager(private val ctx: Context) {
    private val params = MainNetParams.get()
    private var active: WalletInfo? = null
    private var cachedSeed: String? = null
    private var cachedPassword: CharArray? = null
    private val prefs = ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)
    private var lastPrice = prefs.getFloat("last_price", 67500f).toDouble()

    fun hasWallets(): Boolean {
        return prefs.all.keys.any { key -> key.endsWith("_seed") }
    }

    fun getActive(): WalletInfo? = active

    fun getActiveId(): String? {
        return prefs.all.keys.mapNotNull { key ->
            if (key.endsWith("_seed")) key.removeSuffix("_seed") else null
        }.firstOrNull()
    }

    fun unlock(id: String, password: String): Boolean {
        if (prefs.getInt("${id}_attempts", 0) >= 5) return false
        return try {
            val enc = prefs.getString("${id}_seed", "") ?: return false
            val seed = CryptoUtil.decrypt(enc, password)
            val name = prefs.getString("${id}_name", "") ?: ""
            cachedSeed = seed
            cachedPassword = password.toCharArray()
            active = WalletInfo(id, name)
            prefs.edit().putInt("${id}_attempts", 0).apply()
            true
        } catch (e: Exception) {
            val attempts = prefs.getInt("${id}_attempts", 0) + 1
            prefs.edit().putInt("${id}_attempts", attempts).apply()
            false
        }
    }

    fun lock() {
        cachedPassword?.fill('0')
        cachedPassword = null
        cachedSeed = null
        active = null
    }

    fun changePassword(id: String, oldPass: String, newPass: String): Boolean {
        return try {
            val enc = prefs.getString("${id}_seed", "") ?: return false
            val seed = CryptoUtil.decrypt(enc, oldPass)
            val newEnc = CryptoUtil.encrypt(seed, newPass)
            prefs.edit().putString("${id}_seed", newEnc).apply()
            if (active?.id == id) {
                cachedSeed = seed
                cachedPassword = newPass.toCharArray()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun rename(id: String, newName: String): Boolean {
        return try {
            prefs.edit().putString("${id}_name", newName).apply()
            if (active?.id == id) active = active?.copy(name = newName)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val info = WalletInfo(id, if (name.isBlank()) "Ví $id" else name)
        val enc = CryptoUtil.encrypt(mnemonic, password)
        prefs.edit().putString("${id}_name", info.name).putString("${id}_seed", enc).apply()
        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        active = info
        return info
    }

    fun import(name: String, phrase: String, password: String): WalletInfo? {
        return try {
            val clean = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
            val words = clean.split(" ")
            if (words.size < 12) return null
            DeterministicSeed(words, null, "", System.currentTimeMillis() / 1000)
            val id = UUID.randomUUID().toString()
            val info = WalletInfo(id, if (name.isBlank()) "Imported" else name)
            val enc = CryptoUtil.encrypt(clean, password)
            prefs.edit().putString("${id}_name", info.name).putString("${id}_seed", enc).apply()
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
        prefs.edit().remove("${id}_name").remove("${id}_seed").remove("${id}_attempts").commit()
    }

    fun init() {}
    fun stop() {}
    fun onProgress(cb: (Int, String) -> Unit) { cb(100, "Đã sẵn sàng") }

    fun getBalance(): Double {
        val addr = getAddress()
        if (addr.isEmpty()) return 0.0
        return try {
            val text = httpGet("https://blockstream.info/api/address/$addr")
            val json = JSONObject(text)
            val funded = json.getJSONObject("chain_stats").getLong("funded_txo_sum")
            val spent = json.getJSONObject("chain_stats").getLong("spent_txo_sum")
            (funded - spent) / 1e8
        } catch (_: Exception) { 0.0 }
    }

    fun getAddress(): String {
        val seedStr = cachedSeed ?: return ""
        return try {
            val seed = DeterministicSeed(seedStr.split(" "), null, "", 0L)
            val chain = DeterministicKeyChain.builder().seed(seed).build()
            val key = chain.getKeyByPath(HDUtils.parsePath("M/44H/0H/0H/0/0"), true)
            LegacyAddress.fromKey(params, key).toString()
        } catch (_: Exception) { "" }
    }

    fun getSeed(): String = cachedSeed ?: ""

    fun getTransactions(): List<TransactionInfo> {
        return emptyList()
    }

    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        return "Chức năng gửi đang phát triển"
    }

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        return feeRateSatVb * 250.0 / 1e8
    }

    private fun httpGet(url: String): String {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) { "" }
    }

    private fun updatePrice(price: Double): Double {
        if (price != lastPrice) {
            lastPrice = price
            prefs.edit().putFloat("last_price", price.toFloat()).apply()
        }
        return price
    }

    fun price(): Double {
        try {
            val text = httpGet("https://api.coinbase.com/v2/prices/BTC-USD/spot")
            val amount = JSONObject(text).getJSONObject("data").getString("amount").toDoubleOrNull()
            if (amount != null) return updatePrice(amount)
        } catch (_: Exception) {}
        try {
            val text = httpGet("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")
            val amount = JSONObject(text).getString("price").toDoubleOrNull()
            if (amount != null) return updatePrice(amount)
        } catch (_: Exception) {}
        return lastPrice
    }

    fun getFeeRates(): FeeRates {
        return try {
            val text = httpGet("https://mempool.space/api/v1/fees/recommended")
            val json = JSONObject(text)
            FeeRates(json.getInt("hourFee"), json.getInt("halfHourFee"), json.getInt("fastestFee"))
        } catch (_: Exception) {
            FeeRates(5, 10, 20)
        }
    }
}