package net.buli.ibtc

import android.content.Context
import android.util.Log
import org.bitcoinj.core.*
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*

data class WalletInfo(val id: String, val name: String)
data class TransactionInfo(val txId: String, val amount: Double, val type: String, val time: Date)
data class FeeRates(val slow: Int, val normal: Int, val fast: Int)

class WalletManager(private val ctx: Context) {

    private val params = MainNetParams.get()

    private var active: WalletInfo? = null
    private var locked = false

    private var cachedSeed: String? = null
    private var cachedPassword: CharArray? = null

    private val prefs = ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)

    private var kit: WalletAppKit? = null
    private var wallet: Wallet? = null

    private var lastPrice = prefs.getFloat("last_price", 65000f).toDouble()

    init {
        restoreActiveWallet()
    }

    // ========================= RESTORE =========================
    private fun restoreActiveWallet() {
        try {
            var id = prefs.getString("active_wallet_id", null)
            if (id == null) {
                val seedKey = prefs.all.keys.firstOrNull { it.endsWith("_seed") }
                if (seedKey != null) {
                    id = seedKey.removeSuffix("_seed")
                    prefs.edit().putString("active_wallet_id", id).apply()
                }
            }
            if (id != null) {
                val name = prefs.getString("${id}_name", "Wallet") ?: "Wallet"
                active = WalletInfo(id, name)
                locked = true
            }
        } catch (_: Exception) {}
    }

    // ========================= INIT WALLET CORE =========================

    private fun initWallet(seedPhrase: String) {
        val seed = DeterministicSeed(seedPhrase, null, "", 0L)

        // Dùng file riêng cho từng ví để không bị conflict
        val walletId = active?.id ?: "temp"
        kit = object : WalletAppKit(params, ctx.filesDir, "btc_wallet_$walletId") {
            override fun onSetupCompleted() {
                wallet = this.wallet()
                Log.d("WalletManager", "Wallet ready: ${wallet?.currentReceiveAddress()}")
            }
        }

        kit!!.setBlockingStartup(false)
        kit!!.setAutoSave(true)
        // FIX: restoreFromSeed là property, không phải function
        kit!!.restoreFromSeed = seed
        kit!!.startAsync()
    }

    // ========================= WALLET BASIC =========================

    fun hasWallets(): Boolean {
        return prefs.all.keys.any { it.endsWith("_seed") }
    }

    fun getActive(): WalletInfo? = active
    fun getActiveId(): String? = active?.id
    fun isLocked(): Boolean = locked

    fun unlock(id: String, password: String): Boolean {
        return try {
            val enc = prefs.getString("${id}_seed", null) ?: return false
            val seed = CryptoUtil.decrypt(enc, password)

            val name = prefs.getString("${id}_name", "Wallet") ?: "Wallet"
            active = WalletInfo(id, name)
            prefs.edit().putString("active_wallet_id", id).apply()

            initWallet(seed)

            cachedSeed = seed
            cachedPassword = password.toCharArray()
            locked = false
            true
        } catch (e: Exception) {
            Log.e("WalletManager", "unlock failed", e)
            false
        }
    }

    fun lock() {
        locked = true
        cachedSeed = null
        cachedPassword = null
    }

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val walletName = if (name.isBlank()) "Ví Bitcoin" else name
        val enc = CryptoUtil.encrypt(mnemonic, password)

        prefs.edit()
            .putString("${id}_name", walletName)
            .putString("${id}_seed", enc)
            .apply()

        val info = WalletInfo(id, walletName)
        active = info
        prefs.edit().putString("active_wallet_id", id).apply()

        initWallet(mnemonic)
        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        locked = false
        return info
    }

    fun import(name: String, phrase: String, password: String): WalletInfo? {
        return try {
            val clean = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
            val words = clean.split(" ")
            if (words.size != 12 && words.size != 24) return null

            DeterministicSeed(words, null, "", 0L)
            val id = UUID.randomUUID().toString()
            val walletName = if (name.isBlank()) "Imported Wallet" else name
            val enc = CryptoUtil.encrypt(clean, password)

            prefs.edit()
                .putString("${id}_name", walletName)
                .putString("${id}_seed", enc)
                .apply()

            val info = WalletInfo(id, walletName)
            active = info
            prefs.edit().putString("active_wallet_id", id).apply()

            initWallet(clean)
            cachedSeed = clean
            cachedPassword = password.toCharArray()
            locked = false
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
            .remove("active_wallet_id")
            .apply()
        try { kit?.stopAsync() } catch (_:Exception){}
    }

    fun rename(newName: String) {
        val id = active?.id ?: return
        prefs.edit().putString("${id}_name", newName).apply()
        active = WalletInfo(id, newName)
    }

    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val id = active?.id ?: return false
            val enc = prefs.getString("${id}_seed", null) ?: return false
            val seed = CryptoUtil.decrypt(enc, oldPassword)
            val newEnc = CryptoUtil.encrypt(seed, newPassword)
            prefs.edit().putString("${id}_seed", newEnc).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ========================= WALLET INFO =========================

    fun getSeed(): String = cachedSeed ?: ""

    fun getAddress(): String {
        return try {
            wallet?.currentReceiveAddress()?.toString() ?: ""
        } catch (e: Exception) { "" }
    }

    fun getBalance(): Double {
        return try {
            wallet?.balance?.value?.toDouble()?.div(100000000.0) ?: 0.0
        } catch (e: Exception) { 0.0 }
    }

    fun isValidAddress(address: String): Boolean {
        return try { Address.fromString(params, address); true } catch (e: Exception) { false }
    }

    fun getTransactions(): List<TransactionInfo> {
        val w = wallet ?: return emptyList()
        return try {
            w.transactionsByTime.map {
                TransactionInfo(
                    it.txId.toString(),
                    it.getValue(w).value.toDouble() / 100000000.0,
                    if (it.getValue(w).isPositive) "RECEIVE" else "SEND",
                    Date(it.updateTime.time)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ========================= SEND BTC =========================

    fun send(to: String, amountBTC: Double, feeSatVb: Int): String {
        val w = wallet ?: throw Exception("Wallet chưa sẵn sàng")
        if (feeSatVb < 1 || feeSatVb > 1000) throw Exception("Fee không hợp lệ")
        val address = Address.fromString(params, to)
        val coin = Coin.parseCoin(amountBTC.toString())
        val request = SendRequest.to(address, coin)
        request.feePerKb = Coin.valueOf((feeSatVb * 1000).toLong())
        request.ensureMinRequiredFee = true
        val result = w.sendCoins(request) ?: throw Exception("Send failed")
        Log.d("WalletManager", "TX sent: ${result.tx.hashAsString}")
        return result.tx.hashAsString
    }

    // ========================= ESTIMATE FEE (FIX cho MainActivity) =========================
    // MainActivity đang gọi estimateFee ở nhiều chỗ, thêm lại để build được
    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        // ước tính đơn giản 250 vbytes cho tx P2WPKH 1 in 2 out
        return (feeRateSatVb * 250.0) / 100000000.0
    }
    fun estimateFee(amountBTC: Double, feeRateSatVb: Int): Double {
        return estimateFee("", amountBTC, feeRateSatVb)
    }

    // ========================= PRICE & API =========================

    fun price(): Double {
        return try {
            val json = httpGet("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")
            val rate = JSONObject(json).getString("price").toDouble()
            lastPrice = rate
            prefs.edit().putFloat("last_price", rate.toFloat()).apply()
            rate
        } catch (_: Exception) { lastPrice }
    }

    fun getFeeRates(): FeeRates {
        return try {
            val json = httpGet("https://mempool.space/api/v1/fees/recommended")
            val obj = JSONObject(json)
            FeeRates(
                slow = obj.optInt("hourFee", 5),
                normal = obj.optInt("halfHourFee", 10),
                fast = obj.optInt("fastestFee", 20)
            )
        } catch (e: Exception) { FeeRates(5, 10, 20) }
    }

    private fun httpGet(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) { "" }
    }

    // ========================= LIFECYCLE =========================

    fun init() {}
    fun stop() { try { kit?.stopAsync() } catch (_: Exception) {} }
    fun onProgress(cb: (Int, String) -> Unit) { cb(100, "Wallet ready") }
}
