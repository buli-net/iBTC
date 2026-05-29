package net.buli.ibtc

import android.content.Context
import android.util.Log
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.TransactionSignature
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
    private val GAP_LIMIT = 50   // scan 50 địa chỉ đầu tiên

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
        locked = true
        cachedSeed = null
        cachedPassword = null
    }

    fun create(name: String, password: String): WalletInfo {
        val id = UUID.randomUUID().toString()
        val seed = DeterministicSeed(SecureRandom(), 128, "")
        val mnemonic = seed.mnemonicCode!!.joinToString(" ")
        val walletName = if (name.isBlank()) "Ví Bitcoin" else name
        val address = getAddressAtIndex(0) // lấy address index 0 để lưu làm mặc định
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
        return info
    }

    // Lấy address theo derivation index (BIP84: m/84'/0'/0'/0/index)
    private fun getAddressAtIndex(index: Int): String {
        val seed = DeterministicSeed(cachedSeed!!.split(" "), null, "", 0L)
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

    // Lấy private key theo index
    private fun getPrivateKeyAtIndex(index: Int): DeterministicKey {
        val seed = DeterministicSeed(cachedSeed!!.split(" "), null, "", 0L)
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
        return key
    }

    // Build map address -> index bằng cách scan các index từ 0..GAP_LIMIT
    private fun buildAddressIndexMap(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (i in 0..GAP_LIMIT) {
            val addr = getAddressAtIndex(i)
            map[addr] = i
        }
        return map
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
        for (p in path) key = HDKeyDerivation.deriveChildKey(key, p)
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
            val address = getAddressAtIndex(0) // lưu address index 0
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

    fun getFeeRates(): FeeRates {
        return try {
            val json = httpGet("https://mempool.space/api/v1/fees/recommended")
            if (json.isNotBlank()) {
                val obj = JSONObject(json)
                val fastest = obj.optInt("fastestFee", 20)
                val halfHour = obj.optInt("halfHourFee", 10)
                val hour = obj.optInt("hourFee", 5)
                FeeRates(
                    slow = maxOf(hour, 1),
                    normal = maxOf(halfHour, 1),
                    fast = maxOf(fastest, 1)
                )
            } else {
                FeeRates(5, 10, 20)
            }
        } catch (e: Exception) {
            FeeRates(5, 10, 20)
        }
    }

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        // fallback an toàn: 1 input, 2 outputs
        return (68 + 62 + 11) * feeRateSatVb / 1e8
    }

    // ================== GỬI BTC - FIX ĐÚNG ==================
    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        if (feeRateSatVb < 1 || feeRateSatVb > 500) {
            throw Exception("Fee rate không hợp lệ (1-500 sat/vB)")
        }
        val seedPhrase = cachedSeed ?: throw Exception("Ví chưa được mở khóa")
        val needSat = (amountBTC * 1e8).toLong()
        if (needSat <= 0) throw Exception("Số tiền không hợp lệ")
        if (needSat < DUST_THRESHOLD) throw Exception("Số tiền quá nhỏ (dưới 546 satoshi)")

        // 1. Lấy tất cả UTXO của toàn bộ ví (scan nhiều address)
        val allUtxos = getAllUtxos()
        if (allUtxos.isEmpty()) throw Exception("Ví không có UTXO nào")

        // 2. Chọn UTXO đủ để gửi
        val sortedUtxos = allUtxos.sortedByDescending { it.valueSat }
        var total = 0L
        val selected = mutableListOf<UtxoWithIndex>()
        for (utxo in sortedUtxos) {
            selected.add(utxo)
            total += utxo.valueSat
            val approxFee = (selected.size * 68 + 2 * 31 + 10) * feeRateSatVb
            if (total >= needSat + approxFee) break
        }
        if (total < needSat) throw Exception("Không đủ số dư (cần ${needSat/1e8} BTC, có ${total/1e8} BTC)")

        // 3. Tạo transaction
        val tx = Transaction(params)
        val destAddress = Address.fromString(params, to)
        tx.addOutput(Coin.valueOf(needSat), destAddress)

        // 4. Tính fee và change
        var txSize = tx.bitcoinSerialize().size + selected.size * 68 + 10
        var feeSat = (txSize * feeRateSatVb).toLong()
        var changeSat = total - needSat - feeSat
        if (changeSat > 0 && changeSat < DUST_THRESHOLD) {
            feeSat += changeSat
            changeSat = 0
            txSize = tx.bitcoinSerialize().size + selected.size * 68 + 10
            feeSat = (txSize * feeRateSatVb).toLong()
            changeSat = total - needSat - feeSat
            if (changeSat > 0 && changeSat < DUST_THRESHOLD) {
                feeSat += changeSat
                changeSat = 0
            }
        }
        if (changeSat >= DUST_THRESHOLD) {
            val changeAddress = getAddressAtIndex(0) // trả change về address index 0
            tx.addOutput(Coin.valueOf(changeSat), Address.fromString(params, changeAddress))
        } else if (changeSat < 0) {
            throw Exception("Số dư không đủ trả phí, thiếu ${-changeSat} sat")
        }

        // 5. Thêm inputs và ký đúng từng UTXO với key đúng index
        for (utxo in selected) {
            val outPoint = Sha256Hash.wrap(utxo.txid)
            val txOutPoint = TransactionOutPoint(params, utxo.vout.toLong(), outPoint)
            val input = TransactionInput(params, tx, ByteArray(0), txOutPoint)
            tx.addInput(input)
        }

        // Ký từng input
        for (i in tx.inputs.indices) {
            val utxo = selected[i]
            val key = getPrivateKeyAtIndex(utxo.index)
            val myScript = ScriptBuilder.createOutputScript(Address.fromString(params, utxo.address))
            val sighash = tx.hashForSignature(i, myScript.program, Transaction.SigHash.ALL, false)
            val sig = key.sign(sighash)
            val txSig = TransactionSignature(sig, Transaction.SigHash.ALL, false)
            val witness = TransactionWitness(2)
            witness.setPush(0, txSig.encodeToBitcoin())
            witness.setPush(1, key.pubKey)
            tx.inputs[i].setWitness(witness)
        }

        // 6. Verify sau khi ký
        tx.verify()
        val txHex = Utils.HEX.encode(tx.bitcoinSerialize())
        Log.d("WalletManager", "TX size: ${tx.bitcoinSerialize().size} bytes")
        return broadcastTx(txHex)
    }

    fun isValidAddress(address: String): Boolean {
        return try {
            Address.fromString(params, address)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------ Lấy UTXO từ tất cả các address đã scan ------------------
    private data class UtxoWithIndex(
        val txid: String,
        val vout: Int,
        val valueSat: Long,
        val address: String,
        val index: Int
    )

    private fun getAllUtxos(): List<UtxoWithIndex> {
        val addressIndexMap = buildAddressIndexMap()
        val all = mutableListOf<UtxoWithIndex>()
        for ((address, idx) in addressIndexMap) {
            val utxos = getUtxosForAddress(address)
            for (u in utxos) {
                all.add(UtxoWithIndex(u.txid, u.vout, u.valueSat, address, idx))
            }
        }
        return all
    }

    private data class SimpleUtxo(val txid: String, val vout: Int, val valueSat: Long)

    private fun getUtxosForAddress(address: String): List<SimpleUtxo> {
        val json = httpGet("https://blockstream.info/api/address/$address/utxo")
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        val list = mutableListOf<SimpleUtxo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val status = obj.optJSONObject("status")
            val confirmed = status?.optBoolean("confirmed", false) ?: false
            if (!confirmed) continue
            list.add(SimpleUtxo(
                obj.getString("txid"),
                obj.getInt("vout"),
                obj.getLong("value")
            ))
        }
        return list
    }

    private fun broadcastTx(txHex: String): String {
        val url = "https://blockstream.info/api/tx"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            DataOutputStream(conn.outputStream).use { os ->
                os.writeBytes(txHex)
            }
            val responseCode = conn.responseCode
            val response = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }
            if (responseCode in 200..299) {
                return response.trim()
            } else {
                throw Exception(response)
            }
        } catch (e: Exception) {
            throw Exception("Broadcast failed: ${e.message}")
        } finally {
            conn?.disconnect()
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