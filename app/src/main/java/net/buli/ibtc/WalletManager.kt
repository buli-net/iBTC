package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
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
            true
        } catch (e: Exception) {
            false
        }
    }

    fun lock() {
        locked = false
    }

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val walletName = if (name.isBlank()) "Ví Bitcoin" else name
        val address = getNativeSegwitAddress(mnemonic)
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
        return info
    }

    private fun deriveKey(seedPhrase: String, purpose: Int): DeterministicKey {
        val seed = DeterministicSeed(seedPhrase.split(" "), null, "", 0L)
        val seedBytes = seed.seedBytes!!
        var key = HDKeyDerivation.createMasterPrivateKey(seedBytes)
        val path = listOf(
            ChildNumber(purpose, true),
            ChildNumber(0, true),
            ChildNumber(0, true),
            ChildNumber.ZERO,
            ChildNumber.ZERO
        )
        for (p in path) {
            key = HDKeyDerivation.deriveChildKey(key, p)
        }
        return key
    }

    private fun getLegacyAddress(seedPhrase: String): String {
        return try {
            val key = deriveKey(seedPhrase, 44)
            LegacyAddress.fromKey(params, key).toString()
        } catch (_: Exception) { "" }
    }

    private fun getNestedSegwitAddress(seedPhrase: String): String {
        return try {
            val key = deriveKey(seedPhrase, 49)
            SegwitAddress.fromKey(params, key).toString()
        } catch (_: Exception) { "" }
    }

    private fun getNativeSegwitAddress(seedPhrase: String): String {
        return try {
            val key = deriveKey(seedPhrase, 84)
            SegwitAddress.fromKey(params, key).toString()
        } catch (_: Exception) { "" }
    }

    fun import(name: String, phrase: String, password: String): WalletInfo? {
        return try {
            val clean = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
            val words = clean.split(" ")
            if (words.size != 12 && words.size != 24) return null
            DeterministicSeed(words, null, "", 0L)
            val id = UUID.randomUUID().toString()
            val walletName = if (name.isBlank()) "Imported Wallet" else name
            val address = getNativeSegwitAddress(clean)
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
            val sats = funded - spent
            sats / 100000000.0
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
                        val script = out.optString("scriptpubkey_address", "")
                        if (script == address) {
                            received += out.optLong("value", 0L)
                        }
                    }
                }
                val vin = tx.optJSONArray("vin")
                if (vin != null) {
                    for (j in 0 until vin.length()) {
                        val input = vin.getJSONObject(j)
                        val prev = input.optJSONObject("prevout")
                        val script = prev?.optString("scriptpubkey_address", "") ?: ""
                        if (script == address) {
                            sent += prev?.optLong("value", 0L) ?: 0L
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

    // Lấy phí động từ mempool.space
    fun getFeeRates(): FeeRates {
        return try {
            val json = httpGet("https://mempool.space/api/v1/fees/recommended")
            if (json.isNotBlank()) {
                val obj = JSONObject(json)
                val fastest = obj.optInt("fastestFee", 20)
                val halfHour = obj.optInt("halfHourFee", 10)
                val hour = obj.optInt("hourFee", 5)
                FeeRates(slow = hour, normal = halfHour, fast = fastest)
            } else {
                FeeRates(5, 10, 20)
            }
        } catch (e: Exception) {
            FeeRates(5, 10, 20)
        }
    }

    // ================== SEND BTC ==================

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        return try {
            val utxos = getUtxos(getAddress())
            val needSat = (amountBTC * 1e8).toLong()
            val (selected, _) = selectUtxos(utxos, needSat, feeRateSatVb)
            if (selected.isEmpty()) return 0.0
            val inputSize = selected.size * 68
            val outputSize = 2 * 31
            val txSize = inputSize + outputSize + 10
            txSize * feeRateSatVb / 1e8
        } catch (e: Exception) {
            (68 + 62 + 10) * feeRateSatVb / 1e8
        }
    }

    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        val seedPhrase = cachedSeed ?: throw Exception("Ví chưa được mở khóa")
        val myAddressStr = getAddress()
        if (myAddressStr.isBlank()) throw Exception("Không tìm thấy địa chỉ ví")
        val needSat = (amountBTC * 1e8).toLong()
        if (needSat <= 0) throw Exception("Số tiền không hợp lệ")

        val utxos = getUtxos(myAddressStr)
        val (selectedUtxos, totalInputSat) = selectUtxos(utxos, needSat, feeRateSatVb)
        if (selectedUtxos.isEmpty()) throw Exception("Không đủ số dư")

        val tx = Transaction(params)
        val destAddress = Address.fromString(params, to)
        tx.addOutput(Coin.valueOf(needSat), destAddress)

        var txSize = tx.bitcoinSerialize().size
        var feeSat = txSize * feeRateSatVb
        var changeSat = totalInputSat - needSat - feeSat
        if (changeSat < 0) {
            txSize += 50
            feeSat = txSize * feeRateSatVb
            changeSat = totalInputSat - needSat - feeSat
            if (changeSat < 0) throw Exception("Không đủ trả phí")
        }
        if (changeSat > 546) {
            val changeAddress = Address.fromString(params, myAddressStr)
            tx.addOutput(Coin.valueOf(changeSat), changeAddress)
        }

        val key = getPrivateKeyForAddress(seedPhrase, myAddressStr)

        val scripts = mutableListOf<Script>()
        for (utxo in selectedUtxos) {
            val outPoint = Sha256Hash.wrap(utxo.txid)
            val scriptPubKey = ScriptBuilder.createOutputScript(destAddress)
            tx.addInput(outPoint, utxo.vout.toLong(), scriptPubKey)
            scripts.add(scriptPubKey)
        }

        for (i in 0 until tx.inputs.size) {
            val input = tx.inputs[i]
            val scriptPubKey = scripts[i]
            val sighash = tx.hashForSignature(i, scriptPubKey.program, Transaction.SigHash.ALL, false)
            val sig = key.sign(sighash)
            val txSig = TransactionSignature(sig, Transaction.SigHash.ALL, false)
            val witness = TransactionWitness(2)
            witness.setPush(0, txSig.encodeToBitcoin())
            witness.setPush(1, key.pubKey)
            input.setWitness(witness)
        }

        val txHex = Utils.HEX.encode(tx.bitcoinSerialize())
        return broadcastTx(txHex)
    }

    // ------------------ Hỗ trợ ------------------
    private data class Utxo(val txid: String, val vout: Int, val valueSat: Long, val scriptPubKey: String)

    private fun getUtxos(address: String): List<Utxo> {
        val json = httpGet("https://blockstream.info/api/address/$address/utxo")
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        val list = mutableListOf<Utxo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(Utxo(
                obj.getString("txid"),
                obj.getInt("vout"),
                obj.getLong("value"),
                obj.optString("scriptpubkey", "")
            ))
        }
        return list
    }

    private fun selectUtxos(utxos: List<Utxo>, needSat: Long, feeRate: Int): Pair<List<Utxo>, Long> {
        val sorted = utxos.sortedByDescending { it.valueSat }
        var total = 0L
        val selected = mutableListOf<Utxo>()
        for (utxo in sorted) {
            selected.add(utxo)
            total += utxo.valueSat
            val approxFee = (selected.size * 68 + 2 * 31 + 10) * feeRate
            if (total >= needSat + approxFee) return Pair(selected, total)
        }
        return Pair(emptyList(), 0L)
    }

    private fun getPrivateKeyForAddress(seedPhrase: String, address: String): DeterministicKey {
        val seed = DeterministicSeed(seedPhrase.split(" "), null, "", 0L)
        val seedBytes = seed.seedBytes ?: throw Exception("Invalid seed")
        var key = HDKeyDerivation.createMasterPrivateKey(seedBytes)
        val path = listOf(
            ChildNumber(84, true),
            ChildNumber(0, true),
            ChildNumber(0, true),
            ChildNumber(0, false),
            ChildNumber(0, false)
        )
        for (p in path) key = HDKeyDerivation.deriveChildKey(key, p)
        return key
    }

    private fun broadcastTx(txHex: String): String {
        val url = "https://blockstream.info/api/tx"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        DataOutputStream(conn.outputStream).use { os ->
            os.writeBytes("tx=$txHex")
        }
        val responseCode = conn.responseCode
        if (responseCode == 200) {
            return conn.inputStream.bufferedReader().readText().trim()
        } else {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("Broadcast failed: $error")
        }
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
            locked = false
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
        } catch (_: Exception) { "" }
    }

    fun init() {}
    fun stop() {}
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