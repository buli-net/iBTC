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

    private val prefs = ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)

    private var kit: WalletAppKit? = null
    private var wallet: Wallet? = null

    init {
        restoreActiveWallet()
    }

    // ================= INIT =================

    private fun initWallet(seedPhrase: String) {
        val seed = DeterministicSeed(seedPhrase, null, "", 0L)

        val dir = ctx.getDir("btc_wallet", Context.MODE_PRIVATE)

        kit = object : WalletAppKit(params, dir, "wallet") {
            override fun onSetupCompleted() {
                wallet = wallet()
                Log.d("WalletManager", "Wallet ready")
            }
        }

        kit!!.setBlockingStartup(false)
        kit!!.restoreWalletFromSeed(seed)

        kit!!.startAsync()
    }

    // ================= CREATE =================

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()

        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")

        val enc = CryptoUtil.encrypt(mnemonic, password)

        prefs.edit()
            .putString("${id}_seed", enc)
            .putString("${id}_name", name.ifBlank { "Wallet" })
            .apply()

        initWallet(mnemonic)

        val info = WalletInfo(id, name)

        active = info
        prefs.edit().putString("active_wallet_id", id).apply()

        cachedSeed = mnemonic
        locked = false

        return info
    }

    // ================= UNLOCK =================

    fun unlock(id: String, password: String): Boolean {
        return try {
            val enc = prefs.getString("${id}_seed", null) ?: return false
            val seed = CryptoUtil.decrypt(enc, password)

            initWallet(seed)

            active = WalletInfo(
                id,
                prefs.getString("${id}_name", "Wallet") ?: "Wallet"
            )

            cachedSeed = seed
            locked = false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun lock() {
        locked = true
        cachedSeed = null
    }

    // ================= INFO =================

    fun getAddress(): String {
        return try {
            wallet?.currentReceiveAddress()?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getBalance(): Double {
        return try {
            wallet?.balance?.value?.toDouble()?.div(1e8) ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    // ================= SEND BTC (FIXED BUILD OK) =================

    fun send(to: String, amountBTC: Double, feeSatVb: Int): String {

        val w = wallet ?: throw Exception("Wallet chưa sẵn sàng")

        val address = Address.fromString(params, to)

        // FIX: tránh parseCoin crash
        val coin = Coin.valueOf((amountBTC * 1e8).toLong())

        val request = SendRequest.to(address, coin)

        request.feePerKb = Coin.valueOf((feeSatVb * 1000).toLong())
        request.ensureMinRequiredFee = true

        val result = w.sendCoins(request)
            ?: throw Exception("Send failed")

        return result.tx.hashAsString
    }

    // ================= TRANSACTIONS =================

    fun getTransactions(): List<TransactionInfo> {
        val w = wallet ?: return emptyList()

        return try {
            w.transactionsByTimeImmutable.map {
                val value = it.getValue(w).value.toDouble() / 1e8

                TransactionInfo(
                    it.txId.toString(),
                    kotlin.math.abs(value),
                    if (value > 0) "RECEIVE" else "SEND",
                    Date(it.updateTime.time)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ================= RESTORE =================

    private fun restoreActiveWallet() {
        val id = prefs.getString("active_wallet_id", null) ?: return
        val name = prefs.getString("${id}_name", "Wallet") ?: "Wallet"
        active = WalletInfo(id, name)
    }

    // ================= NETWORK =================

    private fun httpGet(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
    }

    fun init() {}

    fun stop() {
        try {
            kit?.stopAsync()
        } catch (_: Exception) {}
    }
}