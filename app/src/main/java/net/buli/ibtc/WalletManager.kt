package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

// Lưu thông tin ví (KHÔNG lưu seed)
data class WalletInfo(val id: String, val name: String)
// Lưu giao dịch
data class TransactionInfo(val txId: String, val amount: Double, val type: String, val time: Date)
// Phí mạng
data class FeeRates(val slow: Int, val normal: Int, val fast: Int)

class WalletManager(private val ctx: Context) {
    private val params = MainNetParams.get() // Bitcoin mainnet
    private var kit: WalletAppKit? = null
    private var active: WalletInfo? = null
    private var cachedSeed: String? = null // chỉ giữ khi mở khóa
    private val prefs = ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)
    private var lastPrice = prefs.getFloat("last_price", 67500f).toDouble()

    // Có ví chưa
    fun hasWallets(): Boolean {
        return prefs.all.keys.any { key -> key.endsWith("_seed") }
    }

    fun getActive(): WalletInfo? = active

    fun getActiveId(): String? {
        return prefs.all.keys.mapNotNull { key ->
            if (key.endsWith("_seed")) key.removeSuffix("_seed") else null
        }.firstOrNull()
    }

    // Mở khóa ví - giới hạn 5 lần sai
    fun unlock(id: String, password: String): Boolean {
        if (prefs.getInt("${id}_attempts", 0) >= 5) return false
        return try {
            val enc = prefs.getString("${id}_seed", "") ?: return false
            val seed = CryptoUtil.decrypt(enc, password)
            val name = prefs.getString("${id}_name", "") ?: ""
            cachedSeed = seed
            active = WalletInfo(id, name)
            prefs.edit().putInt("${id}_attempts", 0).apply()
            true
        } catch (e: Exception) {
            val attempts = prefs.getInt("${id}_attempts", 0) + 1
            prefs.edit().putInt("${id}_attempts", attempts).apply()
            false
        }
    }

    // Khóa ví - xóa seed khỏi RAM
    fun lock() {
        cachedSeed = null
        active = null
        stop()
    }

    // ĐỔI MẬT KHẨU
    fun changePassword(id: String, oldPass: String, newPass: String): Boolean {
        return try {
            val enc = prefs.getString("${id}_seed", "") ?: return false
            val seed = CryptoUtil.decrypt(enc, oldPass)
            val newEnc = CryptoUtil.encrypt(seed, newPass)
            prefs.edit().putString("${id}_seed", newEnc).apply()
            if (active?.id == id) cachedSeed = seed
            true
        } catch (e: Exception) {
            false
        }
    }

    // ĐỔI TÊN - không reset pass
    fun rename(id: String, newName: String): Boolean {
        return try {
            prefs.edit().putString("${id}_name", newName).apply()
            if (active?.id == id) active = active?.copy(name = newName)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Tạo ví mới - dùng UUID tránh trùng
    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val info = WalletInfo(id, if (name.isBlank()) "Ví $id" else name)
        val enc = CryptoUtil.encrypt(mnemonic, password)
        prefs.edit().putString("${id}_name", info.name).putString("${id}_seed", enc).apply()
        cachedSeed = mnemonic
        active = info
        return info
    }

    // Import ví - chuẩn hóa seed
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
            active = info
            info
        } catch (e: Exception) {
            null
        }
    }

    // XÓA VÍ
    fun delete(id: String) {
        lock()
        prefs.edit().remove("${id}_name").remove("${id}_seed").remove("${id}_attempts").commit()
        try {
            File(ctx.filesDir, id).deleteRecursively()
        } catch (_: Exception) {}
    }

    // Khởi tạo sync
    fun init() {
        val info = active ?: return
        val seedStr = cachedSeed ?: return
        if (kit != null) return
        val seed = DeterministicSeed(seedStr.split(" "), null, "", 0L)
        kit = WalletAppKit(params, File(ctx.filesDir, info.id), "ibtc").apply {
            setBlockingStartup(false)
            restoreWalletFromSeed(seed)
            setDownloadListener(object : DownloadProgressTracker() {})
            startAsync()
            awaitRunning()
        }
    }

    fun stop() {
        try {
            kit?.stopAsync()?.awaitTerminated()
        } catch (_: Exception) {}
        kit = null
    }

    fun onProgress(cb: (Int, String) -> Unit) {
        kit?.setDownloadListener(object : DownloadProgressTracker() {
            override fun progress(pct: Double, blocksSoFar: Int, date: Date?) {
                val percent = pct.toInt()
                val text = if (percent < 100) "Đang sync ${percent}%" else "Đã sync"
                cb(percent, text)
            }
            override fun doneDownload() {
                cb(100, "Đã sync")
            }
        })
    }

    fun getBalance(): Double {
        return kit?.wallet()?.balance?.value?.toDouble()?.div(1e8) ?: 0.0
    }

    fun getAddress(): String {
        return kit?.wallet()?.currentReceiveAddress()?.toString() ?: ""
    }

    fun getSeed(): String {
        return cachedSeed ?: ""
    }

    fun getTransactions(): List<TransactionInfo> {
        val wallet = kit?.wallet() ?: return emptyList()
        return wallet.getTransactionsByTime().map { tx ->
            val value = tx.getValue(wallet).value.toDouble() / 1e8
            val fee = tx.fee?.value?.toDouble()?.div(1e8) ?: 0.0
            val amount = if (value < 0) kotlin.math.abs(value) - fee else value
            TransactionInfo(tx.txId.toString(), amount, if (value > 0) "Nhận" else "Gửi", tx.updateTime)
        }.reversed()
    }

    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        return try {
            val wallet = kit!!.wallet()
            val request = SendRequest.to(Address.fromString(params, to), Coin.parseCoin(amountBTC.toString()))
            request.feePerKb = Coin.valueOf(feeRateSatVb.toLong() * 1000)
            wallet.completeTx(request)
            wallet.commitTx(request.tx)
            kit!!.peerGroup().broadcastTransaction(request.tx).future().get()
            request.tx.txId.toString()
        } catch (e: Exception) {
            "Lỗi: ${e.message}"
        }
    }

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        return try {
            val wallet = kit?.wallet() ?: return feeRateSatVb * 250.0 / 1e8
            val request = SendRequest.to(Address.fromString(params, to), Coin.parseCoin(amountBTC.toString()))
            request.feePerKb = Coin.valueOf(feeRateSatVb.toLong() * 1000)
            wallet.completeTx(request)
            request.tx.fee?.value?.toDouble()?.div(1e8) ?: feeRateSatVb * 250.0 / 1e8
        } catch (e: Exception) {
            feeRateSatVb * 250.0 / 1e8
        }
    }

    private fun httpGet(url: String): String {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
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
            val price = JSONObject(text).getString("price").toDoubleOrNull()
            if (price != null) return updatePrice(price)
        } catch (_: Exception) {}
        try {
            val text = httpGet("https://blockchain.info/ticker")
            val price = JSONObject(text).getJSONObject("USD").getDouble("last")
            return updatePrice(price)
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