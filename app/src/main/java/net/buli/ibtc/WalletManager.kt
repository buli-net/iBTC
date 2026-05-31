package net.buli.ibtc

import android.content.Context
import android.content.Intent
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.json.JSONObject
import java.io.OutputStreamWriter
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
    private var lastPrice = prefs.getFloat("last_price", 0f).toDouble()

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
        try {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        } catch (_: Exception) {}
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
            .apply()
        if (active?.id == id) {
            prefs.edit().remove("active_wallet_id").apply()
            active = null
        }
    }

    fun getSeed(): String = cachedSeed ?: ""

    fun getAddress(): String {
        val id = active?.id ?: return ""
        return prefs.getString("${id}_address", "") ?: ""
    }

    private fun getWallet(): Wallet? = SyncService.getInstance()?.getWallet()
    private fun getPeerGroup() = SyncService.getInstance()?.getPeerGroup()

    fun getBalance(): Double {
        val wallet = getWallet() ?: return 0.0
        return wallet.getBalance().value / 1e8
    }

    fun getTransactions(): List<TransactionInfo> {
        val wallet = getWallet() ?: return emptyList()
        val list = mutableListOf<TransactionInfo>()
        for (tx in wallet.getTransactionsByTime()) {
            val value = tx.getValue(wallet)
            val amount = value.value / 1e8
            val type = if (amount > 0) "RECEIVE" else "SEND"
            list.add(TransactionInfo(tx.getHashAsString(), abs(amount), type, tx.getUpdateTime()))
        }
        val pending = wallet.getPendingTransactions()
        for (tx in pending) {
            val value = tx.getValue(wallet)
            val amount = value.value / 1e8
            val type = if (amount > 0) "RECEIVE" else "SEND"
            list.add(TransactionInfo(tx.getHashAsString(), abs(amount), "$type (pending)", tx.getUpdateTime()))
        }
        list.sortByDescending { it.time }
        return list
    }

    fun price(): Double {
        repeat(2) { attempt ->
            try {
                val url = URL("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val price = JSONObject(response).getString("price").toDouble()
                lastPrice = price
                prefs.edit().putFloat("last_price", price.toFloat()).commit()
                return price
            } catch (e: Exception) {
                if (attempt == 1) {
                    e.printStackTrace()
                } else {
                    Thread.sleep(1000)
                }
            }
        }
        return if (lastPrice > 0) lastPrice else 0.0
    }

    fun getFeeRates(): FeeRates = FeeRates(5, 10, 20)

    fun estimateFee(to: String, amountBTC: Double, feeRateSatVb: Int): Double {
        return (68 + 62 + 11) * feeRateSatVb / 1e8
    }

    fun isWalletSynced(): Boolean = SyncService.getInstance()?.isWalletSynced() ?: false

    fun isValidAddress(address: String): Boolean {
        return try {
            Address.fromString(params, address)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ====================== LẤY UTXO TỪ API ======================
    private fun fetchUtxosFromApi(address: String): List<UTXO>? {
        return try {
            val url = URL("https://blockchain.info/unspent?active=$address")
            val json = url.openStream().bufferedReader().readText()
            val obj = JSONObject(json)
            val unspentOutputs = obj.getJSONArray("unspent_outputs")
            val utxos = mutableListOf<UTXO>()
            for (i in 0 until unspentOutputs.length()) {
                val out = unspentOutputs.getJSONObject(i)
                val txHash = out.getString("tx_hash_big_endian")
                val txIndex = out.getInt("tx_output_n")
                val value = out.getLong("value")
                val utxo = UTXO(
                    Sha256Hash.wrap(txHash),
                    txIndex,
                    Coin.valueOf(value),
                    0,
                    false,
                    null,
                    null
                )
                utxos.add(utxo)
            }
            utxos
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ====================== TẠO VÀ KÝ GIAO DỊCH OFFLINE ======================
    private fun createAndSignTransaction(
        toAddress: String,
        amountSat: Long,
        feeRateSatVb: Int,
        utxos: List<UTXO>
    ): Transaction? {
        return try {
            val tx = Transaction(params)
            val address = Address.fromString(params, toAddress)
            tx.addOutput(Coin.valueOf(amountSat), address)

            var totalInput = 0L
            for (utxo in utxos) {
                totalInput += utxo.value.value
                val outPoint = TransactionOutPoint(params, utxo.index, utxo.hash)
                val scriptPubKey = ScriptBuilder.createOutputScript(Address.fromString(params, getAddress()))
                val input = TransactionInput(params, tx, scriptPubKey.program, outPoint)
                tx.addInput(input)
            }

            // Sửa lỗi kiểu: chuyển size sang Long
            val estimatedSize = tx.unsafeBitcoinSerialize().size.toLong() + utxos.size * 107L
            val fee = (estimatedSize / 1000.0 * feeRateSatVb).toLong().coerceAtLeast(1000)
            val change = totalInput - amountSat - fee
            if (change > DUST_THRESHOLD) {
                tx.addOutput(Coin.valueOf(change), Address.fromString(params, getAddress()))
            }

            val seedPhrase = cachedSeed ?: return null
            val key = getPrivateKeyForAddress(seedPhrase, 0)

            for (i in 0 until tx.inputs.size) {
                val input = tx.inputs[i]
                val redeemScript = ScriptBuilder.createOutputScript(Address.fromString(params, getAddress()))
                // Sử dụng đúng API: hashForSignatureWitness nhận ByteArray scriptPubKey
                val sighash = tx.hashForSignatureWitness(i, redeemScript.program, Transaction.SigHash.ALL, false)
                val sig = key.sign(sighash)
                val sigWithHashType = TransactionSignature(sig, Transaction.SigHash.ALL, false).encodeToBitcoin()
                val witness = TransactionWitness(2)
                witness.setPush(0, sigWithHashType)
                witness.setPush(1, key.pubKey)
                input.setWitness(witness)
                input.scriptSig = ScriptBuilder.createEmpty()
            }
            tx
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getPrivateKeyForAddress(seedPhrase: String, index: Int): ECKey {
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
        return ECKey.fromPrivate(key.privKey)
    }

    private fun broadcastTxViaApi(hex: String): Boolean {
        return try {
            val url = URL("https://mempool.space/api/tx")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write("tx=$hex")
                writer.flush()
            }
            val responseCode = conn.responseCode
            responseCode == 200
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ====================== GỬI BTC LINH HOẠT ======================
    fun send(to: String, amountBTC: Double, feeRateSatVb: Int): String {
        if (feeRateSatVb < 1 || feeRateSatVb > 500) {
            throw Exception("Fee rate không hợp lệ (1-500 sat/vB)")
        }

        val amountSat = (amountBTC * 1e8).toLong()
        if (amountSat <= DUST_THRESHOLD) {
            throw Exception("Số tiền quá nhỏ (dưới 546 satoshi)")
        }

        // Nếu SPV đã đồng bộ, dùng SPV
        if (isWalletSynced()) {
            val wallet = getWallet() ?: throw Exception("Wallet chưa sẵn sàng")
            val peerGroup = getPeerGroup() ?: throw Exception("PeerGroup null, chưa kết nối mạng")
            if (peerGroup.connectedPeers.isEmpty()) {
                throw Exception("Chưa kết nối peer nào, không thể broadcast")
            }
            val coin = Coin.valueOf(amountSat)
            val address = Address.fromString(params, to)
            val req = SendRequest.to(address, coin)
            req.feePerKb = Coin.valueOf(feeRateSatVb * 1000L)
            req.ensureMinRequiredFee = true
            req.signInputs = true
            req.shuffleOutputs = true
            req.changeAddress = wallet.currentReceiveAddress()

            try {
                wallet.completeTx(req)
            } catch (e: InsufficientMoneyException) {
                val missing = e.missing?.value ?: 0
                throw Exception("Không đủ BTC để gửi (thiếu ${missing / 1e8} BTC)")
            } catch (e: Exception) {
                throw Exception("Không tạo được transaction: ${e.message}")
            }

            try {
                wallet.commitTx(req.tx)
            } catch (_: Exception) {}

            try {
                val broadcastFuture = peerGroup.broadcastTransaction(req.tx)
                broadcastFuture.future().get()
            } catch (e: Exception) {
                throw Exception("Broadcast lỗi: ${e.message}")
            }
            return req.tx.getHashAsString()
        }

        // SPV chưa đồng bộ: dùng API
        val address = getAddress()
        if (address.isEmpty()) throw Exception("Địa chỉ ví không hợp lệ")

        val utxos = fetchUtxosFromApi(address)
        if (utxos == null || utxos.isEmpty()) {
            throw Exception("Không lấy được UTXO từ API (có thể không có đủ số dư)")
        }

        val totalBalance = utxos.fold(0L) { acc, utxo -> acc + utxo.value.value }
        if (totalBalance < amountSat) {
            throw Exception("Số dư không đủ (cần ${amountSat / 1e8} BTC, có ${totalBalance / 1e8} BTC)")
        }

        val tx = createAndSignTransaction(to, amountSat, feeRateSatVb, utxos)
        if (tx == null) throw Exception("Không thể tạo và ký giao dịch")

        val hex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
        val success = broadcastTxViaApi(hex)
        if (!success) throw Exception("Broadcast qua API thất bại")

        return tx.getHashAsString()
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

    fun init() {}
    fun stop() {}
    fun isLocked(): Boolean = locked

    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val id = active?.id ?: return false
            val enc = prefs.getString("${id}_seed", null) ?: return false
            val seed = CryptoUtil.decrypt(enc, oldPassword)
            val newEnc = CryptoUtil.encrypt(seed, newPassword)
            prefs.edit().putString("${id}_seed", newEnc).commit()
            cachedPassword = newPassword.toCharArray()
            cachedSeed = seed
            true
        } catch (e: Exception) { false }
    }

    fun rename(newName: String) {
        val id = active?.id ?: return
        prefs.edit().putString("${id}_name", newName).commit()
        active = WalletInfo(id, newName)
    }
}