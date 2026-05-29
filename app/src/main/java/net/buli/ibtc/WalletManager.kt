package net.buli.ibtc

import android.content.Context
import android.content.Intent
import android.util.Log
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*
import kotlin.math.abs

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
    private var lastPrice = prefs.getFloat("last_price", 65000f).toDouble()

    private val DUST_THRESHOLD = 546L
    private var syncCallback: ((Int, String) -> Unit)? = null

    init {
        restoreActiveWallet()
    }

    fun onProgress(cb: (Int, String) -> Unit) {
        syncCallback = cb
    }

    fun hasWallets(): Boolean {
        return prefs.all.keys.any {
            it.endsWith("_seed") || it.endsWith("_name") || it.endsWith("_address")
        }
    }

    fun getActive(): WalletInfo? {
        if (active == null) restoreActiveWallet()
        return active
    }

    fun getActiveId(): String? = active?.id

    fun unlock(id: String, password: String): Boolean {
        return try {
            val enc = prefs.getString("${id}_seed", "") ?: return false
            val seed = CryptoUtil.decrypt(enc, password)
            val name = prefs.getString("${id}_name", "Wallet") ?: "Wallet"
            cachedSeed = seed
            cachedPassword = password.toCharArray()
            active = WalletInfo(id, name)
            prefs.edit().putString("active_wallet_id", id).commit()
            locked = false

            val intent = Intent(ctx, SyncService::class.java).apply {
                putExtra("wallet_id", id)
                putExtra("seed_phrase", seed)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }

            SyncService.getInstance()?.setProgressCallback(syncCallback)

            true
        } catch (e: Exception) {
            false
        }
    }

    fun lock() {
        locked = true
        cachedSeed = null
        cachedPassword = null
        ctx.stopService(Intent(ctx, SyncService::class.java))
    }

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val walletName = if (name.isBlank()) "Ví Bitcoin" else name
        val address = getAddressAtIndex(mnemonic, 0)
        val enc = CryptoUtil.encrypt(mnemonic, password)
        prefs.edit()
            .putString("${id}_name", walletName)
            .putString("${id}_seed", enc)
            .putString("${id}_address", address)
            .commit()
        val info = WalletInfo(id, walletName)
        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        active = info
        prefs.edit().putString("active_wallet_id", id).commit()
        locked = false

        val intent = Intent(ctx, SyncService::class.java).apply {
            putExtra("wallet_id", id)
            putExtra("seed_phrase", mnemonic)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

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
            val address = getAddressAtIndex(clean, 0)
            val enc = CryptoUtil.encrypt(clean, password)
            prefs.edit()
                .putString("${id}_name", walletName)
                .putString("${id}_seed", enc)
                .putString("${id}_address", address)
                .commit()
            val info = WalletInfo(id, walletName)
            cachedSeed = clean
            cachedPassword = password.toCharArray()
            active = info
            prefs.edit().putString("active_wallet_id", id).commit()
            locked = false

            val intent = Intent(ctx, SyncService::class.java).apply {
                putExtra("wallet_id", id)
                putExtra("seed_phrase", clean)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }

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
        return prefs.getString("${id}_address", "") ?: ""
    }

    fun getBalance(): Double {
        return try {
            val address = getAddress()
            if (address.isBlank()) return 0.0
            val json = httpGet("https://blockstream.info/api/address/$address")
            if (json.isBlank()) return 0.0
            val obj = JSONObject(json)
            val chainStats = obj.getJSONObject("chain_stats")
            val funded = chainStats.getLong("funded_txo_sum")
            val spent = chainStats.getLong("spent_txo_sum")
            (funded - spent) / 100000000.0
        } catch (e: Exception) { 0.0 }
    }

    fun getTransactions(): List<TransactionInfo> {
        return try {
            val address = getAddress()
            if (address.isBlank()) return emptyList()
            val json = httpGet("https://blockstream.info/api/address/$address/txs")
            if (json.isBlank()) return emptyList()
            val arr = JSONArray(json)
            val list = mutableListOf<TransactionInfo>()
            for (i in 0 until minOf(arr.length(), 20)) {
                val tx = arr.getJSONObject(i)
                val txId = tx.optString("txid", "")
                val status = tx.optJSONObject("status")
                val blockTime = status?.optLong("block_time", System.currentTimeMillis() / 1000) ?: (System.currentTimeMillis() / 1000)
                var received = 0L
                var sent = 0L
                val vout = tx.optJSONArray("vout")
                if (vout != null) {
                    for (j in 0 until vout.length()) {
                        val out = vout.getJSONObject(j)
                        if (out.optString("scriptpubkey_address") == address) {
                            received += out.optLong("value", 0L)
                        }
                    }
                }
                val vin = tx.optJSONArray("vin")
                if (vin != null) {
                    for (j in 0 until vin.length()) {
                        val prev = vin.getJSONObject(j).optJSONObject("prevout")
                        if (prev?.optString("scriptpubkey_address") == address) {
                            sent += prev.optLong("value", 0L)
                        }
                    }
                }
                val net = received - sent
                val btcAmount = abs(net.toDouble()) / 100000000.0
                val type = if (net >= 0) "RECEIVE" else "SEND"
                list.add(TransactionInfo(txId, btcAmount, type, Date(blockTime * 1000)))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    fun price(): Double {
        return try {
            val json = httpGet("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")
            if (json.isBlank()) return lastPrice
            val rate = JSONObject(json).getString("price").toDouble()
            lastPrice = rate
            prefs.edit().putFloat("last_price", rate.toFloat()).commit()
            rate
        } catch (_: Exception) { lastPrice }
    }

    fun getFeeRates(): FeeRates {
        return try {
            val json = httpGet("https://mempool.space/api/v1/fees/recommended")
            if (json.isNotBlank()) {
                val obj = JSONObject(json)
                FeeRates(
                    slow = maxOf(obj.optInt("hourFee", 5), 1),
                    normal = maxOf(obj.optInt("halfHourFee", 10), 1),
                    fast = maxOf(obj.optInt("fastestFee", 20), 1)
                )
            } else {
                FeeRates(5, 10, 20)
            }
        } catch (e: Exception) {
            FeeRates(5, 10, 20)
        }
    }

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        return (68 + 62 + 11) * feeRateSatVb / 1e8
    }

    fun isWalletSynced(): Boolean {
        return SyncService.getInstance()?.isWalletSynced() ?: false
    }

    fun isValidAddress(address: String): Boolean {
        return try {
            Address.fromString(params, address)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getWallet(): Wallet? {
        return SyncService.getInstance()?.getWallet()
    }

    // ================== SEND ==================
    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        if (feeRateSatVb < 1 || feeRateSatVb > 500) {
            throw Exception("Fee rate không hợp lệ (1-500 sat/vB)")
        }
        val wallet = getWallet() ?: throw Exception("Wallet chưa sẵn sàng, vui lòng đợi đồng bộ")

        val coin = Coin.valueOf((amountBTC * 1e8).toLong())
        if (coin.isLessThan(Coin.valueOf(DUST_THRESHOLD))) {
            throw Exception("Số tiền quá nhỏ (dưới 546 satoshi)")
        }

        val address = Address.fromString(params, to)
        val req = SendRequest.to(address, coin)

        req.feePerKb = Coin.valueOf(feeRateSatVb * 1000L)
        req.changeAddress = wallet.currentReceiveAddress()
        req.ensureMinRequiredFee = true

        val result = wallet.sendCoins(req)
            ?: throw Exception("Send failed, không tạo được transaction")

        val peerGroup = SyncService.getInstance()?.getPeerGroup()
        if (peerGroup != null && peerGroup.connectedPeers.isNotEmpty()) {
            peerGroup.broadcastTransaction(result.tx).future()
        } else {
            val txHex = Utils.HEX.encode(result.tx.bitcoinSerialize())
            broadcastViaApi(txHex)
        }

        return result.tx.hashAsString
    }

    private fun broadcastViaApi(txHex: String): String {
        val url = "https://blockstream.info/api/tx"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            DataOutputStream(conn.outputStream).use { os -> os.writeBytes(txHex) }
            val responseCode = conn.responseCode
            val response = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }
            if (responseCode in 200..299) return response.trim()
            else throw Exception(response)
        } catch (e: Exception) {
            throw Exception("Broadcast failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun getAddressAtIndex(seedPhrase: String, index: Int): String {
        val seed = DeterministicSeed(seedPhrase.split(" "), null, "", 0L)
        val seedBytes = seed.seedBytes!!
        var key = HDKeyDerivation.createMasterPrivateKey(seedBytes)
        val path = listOf(
            ChildNumber(84, true),
            ChildNumber(0, true),
            ChildNumber(0, true),
            ChildNumber(0, false),
            ChildNumber(index, false)
        )
        for (p in path) key = HDKeyDerivation.deriveChildKey(key, p)
        return SegwitAddress.fromKey(params, key).toString()
    }

    private fun restoreActiveWallet() {
        try {
            var id = prefs.getString("active_wallet_id", null)
            if (id == null) {
                val seedKey = prefs.all.keys.firstOrNull { it.endsWith("_seed") }
                if (seedKey != null) {
                    id = seedKey.removeSuffix("_seed")
                    prefs.edit().putString("active_wallet_id", id).commit()
                }
            }
            if (id == null) return
            val name = prefs.getString("${id}_name", "Wallet") ?: "Wallet"
            active = WalletInfo(id, name)
            locked = true
        } catch (_: Exception) {}
    }

    private fun httpGet(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }
    }

    fun init() {}
    fun stop() { lock() }
    fun isLocked(): Boolean = locked

    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val id = active?.id ?: return false
            val enc = prefs.getString("${id}_seed", null) ?: return false
            val seed = CryptoUtil.decrypt(enc, oldPassword)
            val newEnc = CryptoUtil.encrypt(seed, newPassword)
            prefs.edit().putString("${id}_seed", newEnc).commit()
            cachedPassword = newPassword.toCharArray()
            true
        } catch (e: Exception) { false }
    }

    fun rename(newName: String) {
        val id = active?.id ?: return
        prefs.edit().putString("${id}_name", newName).commit()
        active = WalletInfo(id, newName)
    }
}