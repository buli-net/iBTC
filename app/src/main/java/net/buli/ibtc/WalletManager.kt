package net.buli.ibtc

import android.content.Context
import android.content.Intent
import org.bitcoinj.core.*
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import java.security.SecureRandom
import java.util.*
import kotlin.math.abs

data class WalletInfo(
val id: String,
val name: String
)

data class TransactionInfo(
val txId: String,
val amount: Double,
val type: String,
val time: Date
)

data class FeeRates(
val slow: Int,
val normal: Int,
val fast: Int
)

class WalletManager(
private val ctx: Context
) {

private val params = MainNetParams.get()

private var active: WalletInfo? = null

private var locked = true

private var cachedSeed: String? = null
private var cachedPassword: CharArray? = null

private val prefs =
    ctx.getSharedPreferences(
        "wallets",
        Context.MODE_PRIVATE
    )

private val DUST_THRESHOLD = 546L

private var lastPrice = 65000.0

private var syncCallback:
        ((Int, String) -> Unit)? = null

init {
    restoreActiveWallet()
}

fun onProgress(
    cb: (Int, String) -> Unit
) {
    syncCallback = cb
    SyncService.getInstance()
        ?.setProgressCallback(cb)
}

fun hasWallets(): Boolean {

    return prefs.all.keys.any {
        it.endsWith("_seed")
    }
}

fun getActive(): WalletInfo? {

    if (active == null) {
        restoreActiveWallet()
    }

    return active
}

fun getActiveId(): String? {
    return active?.id
}

fun unlock(
    id: String,
    password: String
): Boolean {

    return try {

        val enc =
            prefs.getString(
                "${id}_seed",
                ""
            ) ?: return false

        val seed =
            CryptoUtil.decrypt(
                enc,
                password
            )

        val name =
            prefs.getString(
                "${id}_name",
                "Wallet"
            ) ?: "Wallet"

        cachedSeed = seed
        cachedPassword = password.toCharArray()

        active = WalletInfo(id, name)

        prefs.edit()
            .putString(
                "active_wallet_id",
                id
            )
            .commit()

        locked = false

        val intent =
            Intent(ctx, SyncService::class.java)
                .apply {

                    putExtra(
                        "wallet_id",
                        id
                    )

                    putExtra(
                        "seed_phrase",
                        seed
                    )
                }

        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        SyncService.getInstance()
            ?.setProgressCallback(syncCallback)

        true

    } catch (e: Exception) {
        false
    }
}

fun lock() {

    locked = true

    cachedSeed = null
    cachedPassword = null

    ctx.stopService(
        Intent(
            ctx,
            SyncService::class.java
        )
    )
}

fun create(
    name: String,
    password: String
): WalletInfo {

    val id =
        UUID.randomUUID().toString()

    val seed =
        DeterministicSeed(
            SecureRandom(),
            128,
            ""
        )

    val mnemonic =
        seed.mnemonicCode!!
            .joinToString(" ")

    val walletName =
        if (name.isBlank())
            "Ví Bitcoin"
        else
            name

    val address =
        getAddressAtIndex(
            mnemonic,
            0
        )

    val enc =
        CryptoUtil.encrypt(
            mnemonic,
            password
        )

    prefs.edit()
        .putString("${id}_name", walletName)
        .putString("${id}_seed", enc)
        .putString("${id}_address", address)
        .commit()

    val info =
        WalletInfo(id, walletName)

    cachedSeed = mnemonic
    cachedPassword = password.toCharArray()

    active = info

    prefs.edit()
        .putString(
            "active_wallet_id",
            id
        )
        .commit()

    locked = false

    val intent =
        Intent(ctx, SyncService::class.java)
            .apply {

                putExtra(
                    "wallet_id",
                    id
                )

                putExtra(
                    "seed_phrase",
                    mnemonic
                )
            }

    if (android.os.Build.VERSION.SDK_INT >=
        android.os.Build.VERSION_CODES.O
    ) {
        ctx.startForegroundService(intent)
    } else {
        ctx.startService(intent)
    }

    return info
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
                .replace(
                    Regex("\\s+"),
                    " "
                )

        val words = clean.split(" ")

        if (words.size != 12 &&
            words.size != 24
        ) {
            return null
        }

        DeterministicSeed(
            words,
            null,
            "",
            0L
        )

        val id =
            UUID.randomUUID().toString()

        val walletName =
            if (name.isBlank())
                "Imported Wallet"
            else
                name

        val address =
            getAddressAtIndex(
                clean,
                0
            )

        val enc =
            CryptoUtil.encrypt(
                clean,
                password
            )

        prefs.edit()
            .putString("${id}_name", walletName)
            .putString("${id}_seed", enc)
            .putString("${id}_address", address)
            .commit()

        val info =
            WalletInfo(id, walletName)

        cachedSeed = clean
        cachedPassword = password.toCharArray()

        active = info

        prefs.edit()
            .putString(
                "active_wallet_id",
                id
            )
            .commit()

        locked = false

        val intent =
            Intent(ctx, SyncService::class.java)
                .apply {

                    putExtra(
                        "wallet_id",
                        id
                    )

                    putExtra(
                        "seed_phrase",
                        clean
                    )
                }

        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {
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
        .remove("${id}_address")
        .commit()
}

fun getSeed(): String {
    return cachedSeed ?: ""
}

fun getAddress(): String {

    val id =
        active?.id ?: return ""

    return prefs.getString(
        "${id}_address",
        ""
    ) ?: ""
}

private fun getWallet(): Wallet? {
    return SyncService.getInstance()
        ?.getWallet()
}

fun getBalance(): Double {

    val wallet =
        getWallet() ?: return 0.0

    return wallet.getBalance()
        .toBigDecimal()
        .toDouble()
}

fun getTransactions():
        List<TransactionInfo> {

    val wallet =
        getWallet() ?: return emptyList()

    val list =
        mutableListOf<TransactionInfo>()

    for (tx in wallet.getTransactionsByTime()) {

        val value =
            tx.getValue(wallet)

        val amount =
            value.toBigDecimal()
                .toDouble()

        val type =
            if (amount >= 0)
                "RECEIVE"
            else
                "SEND"

        list.add(
            TransactionInfo(
                tx.hashAsString,
                abs(amount),
                type,
                Date(tx.updateTime.time)
            )
        )
    }

    list.sortByDescending {
        it.time
    }

    return list
}

fun price(): Double {
    return lastPrice
}

fun getFeeRates(): FeeRates {

    return FeeRates(
        5,
        10,
        20
    )
}

fun isWalletSynced(): Boolean {

    return SyncService.getInstance()
        ?.isWalletSynced()
        ?: false
}

fun isValidAddress(
    address: String
): Boolean {

    return try {

        Address.fromString(
            params,
            address
        )

        true

    } catch (e: Exception) {
        false
    }
}

fun estimateFee(
    to: String,
    amountBTC: Double,
    feeRateSatVb: Int
): Double {

    val wallet =
        getWallet()
            ?: return 0.00001

    val utxos =
        wallet.getUTXOs()

    if (utxos.isEmpty()) {
        return 0.00001
    }

    val txSize =
        (utxos.size * 68) +
                (2 * 31) +
                11

    return (txSize * feeRateSatVb)
        .toDouble() / 1e8
}

fun send(
    to: String,
    amountBTC: Double,
    feeRateSatVb: Int
): String {

    if (!isWalletSynced()) {
        throw Exception(
            "Blockchain chưa sync xong"
        )
    }

    val wallet =
        getWallet()
            ?: throw Exception(
                "Wallet chưa sẵn sàng"
            )

    val peerGroup =
        SyncService.getInstance()
            ?.getPeerGroup()
            ?: throw Exception(
                "PeerGroup null"
            )

    if (peerGroup.connectedPeers.isEmpty()) {
        throw Exception(
            "Chưa kết nối peer"
        )
    }

    val amountSat =
        (amountBTC * 100000000L).toLong()

    if (amountSat <= DUST_THRESHOLD) {
        throw Exception(
            "Amount quá nhỏ"
        )
    }

    val coin =
        Coin.valueOf(amountSat)

    val spendable =
        wallet.getBalance(
            Wallet.BalanceType
                .AVAILABLE_SPENDABLE
        )

    if (spendable.isLessThan(coin)) {
        throw Exception(
            "Không đủ số dư"
        )
    }

    val address =
        Address.fromString(
            params,
            to
        )

    val req =
        SendRequest.to(
            address,
            coin
        )

    req.feePerKb =
        Coin.valueOf(
            feeRateSatVb * 1000L
        )

    req.ensureMinRequiredFee = true

    req.signInputs = true

    req.shuffleOutputs = true

    req.changeAddress =
        wallet.currentReceiveAddress()

    try {

        wallet.completeTx(req)

    } catch (e: Exception) {

        throw Exception(
            "Không tạo được transaction: ${e.message}"
        )
    }

    try {

        wallet.commitTx(req.tx)

    } catch (e: Exception) {

        throw Exception(
            "Commit tx lỗi: ${e.message}"
        )
    }

    try {

        peerGroup
            .broadcastTransaction(req.tx)
            .future()
            .get()

    } catch (e: Exception) {

        throw Exception(
            "Broadcast lỗi: ${e.message}"
        )
    }

    return req.tx.txId.toString()
}

private fun getAddressAtIndex(
    seedPhrase: String,
    index: Int
): String {

    val seed =
        DeterministicSeed(
            seedPhrase.split(" "),
            null,
            "",
            0L
        )

    val seedBytes =
        seed.seedBytes!!

    var key =
        HDKeyDerivation
            .createMasterPrivateKey(seedBytes)

    val path = listOf(
        ChildNumber(84, true),
        ChildNumber(0, true),
        ChildNumber(0, true),
        ChildNumber(0, false),
        ChildNumber(index, false)
    )

    for (p in path) {
        key =
            HDKeyDerivation
                .deriveChildKey(key, p)
    }

    return SegwitAddress
        .fromKey(params, key)
        .toString()
}

private fun restoreActiveWallet() {

    try {

        var id =
            prefs.getString(
                "active_wallet_id",
                null
            )

        if (id == null) {

            val seedKey =
                prefs.all.keys.firstOrNull {
                    it.endsWith("_seed")
                }

            if (seedKey != null) {

                id =
                    seedKey.removeSuffix("_seed")

                prefs.edit()
                    .putString(
                        "active_wallet_id",
                        id
                    )
                    .commit()
            }
        }

        if (id == null) {
            return
        }

        val name =
            prefs.getString(
                "${id}_name",
                "Wallet"
            ) ?: "Wallet"

        active =
            WalletInfo(id, name)

    } catch (_: Exception) {
    }
}

fun init() {}

fun stop() {
    lock()
}

fun isLocked(): Boolean {
    return locked
}

fun changePassword(
    oldPassword: String,
    newPassword: String
): Boolean {

    return try {

        val id =
            active?.id ?: return false

        val enc =
            prefs.getString(
                "${id}_seed",
                null
            ) ?: return false

        val seed =
            CryptoUtil.decrypt(
                enc,
                oldPassword
            )

        val newEnc =
            CryptoUtil.encrypt(
                seed,
                newPassword
            )

        prefs.edit()
            .putString(
                "${id}_seed",
                newEnc
            )
            .commit()

        cachedPassword =
            newPassword.toCharArray()

        true

    } catch (e: Exception) {
        false
    }
}

fun rename(newName: String) {

    val id =
        active?.id ?: return

    prefs.edit()
        .putString(
            "${id}_name",
            newName
        )
        .commit()

    active =
        WalletInfo(id, newName)
}

}