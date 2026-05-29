package net.buli.ibtc

import android.content.Context
import android.util.Log
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.DeterministicSeed
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

    init {
        restoreActiveWallet()
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
            WalletKitService.start(ctx, id, seed)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun lock() {
        locked = true
        cachedSeed = null
        cachedPassword = null
        WalletKitService.stop()
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
        WalletKitService.start(ctx, id, mnemonic)
        return info
    }

    fun import(name: String, phrase: String, password: String): WalletInfo? {
        return try {
            val clean = phrase.trim().lowercase().replace(Regex("\s+"), " ")
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
            WalletKitService.start(ctx, id, clean)
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
        return (141 * feeRateSatVb) / 1e8
    }

    // ================== SEND API-BASED (FIX) ==================
    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        val seedPhrase = cachedSeed ?: throw Exception("Vui lòng unlock ví")
        if (feeRateSatVb < 1 || feeRateSatVb > 500) {
            throw Exception("Fee rate không hợp lệ (1-500 sat/vB)")
        }
        val fromAddress = getAddress()
        val amountSat = (amountBTC * 1e8).toLong()
        if (amountSat < DUST_THRESHOLD) throw Exception("Số tiền quá nhỏ (dưới 546 sat)")

        // 1. Lấy UTXO từ Blockstream
        val utxoJson = httpGet("https://blockstream.info/api/address/$fromAddress/utxo")
        if (utxoJson.isBlank()) throw Exception("Không lấy được UTXO")
        val utxos = JSONArray(utxoJson)
        if (utxos.length() == 0) throw Exception("Ví không có UTXO")

        var totalIn = 0L
        val inputs = mutableListOf<Triple<String, Int, Long>>()
        for (i in 0 until utxos.length()) {
            val u = utxos.getJSONObject(i)
            val value = u.getLong("value")
            totalIn += value
            inputs.add(Triple(u.getString("txid"), u.getInt("vout"), value))
            if (totalIn >= amountSat + 20000) break
        }

        // 2. Tính phí
        val estSize = 10 + inputs.size * 68 + 34 * 2
        val fee = estSize * feeRateSatVb
        val change = totalIn - amountSat - fee

        if (change < 0) throw Exception("Insufficient money, thiếu ${-change} sats cho phí")
        
        // 3. Tạo transaction
        val tx = Transaction(params)
        val toAddr = Address.fromString(params, to)
        tx.addOutput(Coin.valueOf(amountSat), toAddr)
        
        var hasChange = false
        if (change >= DUST_THRESHOLD) {
            tx.addOutput(Coin.valueOf(change), Address.fromString(params, fromAddress))
            hasChange = true
        }

        // Add inputs
        for ((txid, vout, _) in inputs) {
            tx.addInput(Sha256Hash.wrap(txid), vout, ScriptBuilder.createEmpty())
        }

        // 4. Ký
        val seed = DeterministicSeed(seedPhrase.split(" "), null, "", 0L)
        var key = HDKeyDerivation.createMasterPrivateKey(seed.seedBytes!!)
        val path = listOf(
            ChildNumber(84, true),
            ChildNumber(0, true),
            ChildNumber(0, true),
            ChildNumber(0, false),
            ChildNumber(0, false)
        )
        for (p in path) key = HDKeyDerivation.deriveChildKey(key, p)
        val ecKey = ECKey.fromPrivate(key.privKeyBytes!!)

        val fromScript = ScriptBuilder.createOutputScript(Address.fromString(params, fromAddress))

        for (i in inputs.indices) {
            val value = Coin.valueOf(inputs[i].third)
            val sig = tx.calculateWitnessSignature(i, ecKey, fromScript.program, value, Transaction.SigHash.ALL, false)
            val witness = TransactionWitness(2)
            witness.setPush(0, sig.encodeToBitcoin())
            witness.setPush(1, ecKey.pubKey)
            tx.getInput(i).setWitness(witness)
        }

        // 5. Broadcast
        val txHex = Utils.HEX.encode(tx.bitcoinSerialize())
        return broadcastViaApi(txHex)
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

    fun isValidAddress(address: String): Boolean {
        return try {
            Address.fromString(params, address)
            true
        } catch (e: Exception) {
            false
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
    fun stop() { WalletKitService.stop() }
    fun isLocked(): Boolean = locked
    fun onProgress(cb: (Int, String) -> Unit) { cb(100, "Ví sẵn sàng") }

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