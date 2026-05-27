package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

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

class WalletManager(private val ctx: Context) {

    private val params = MainNetParams.get()

    private var active: WalletInfo? = null
    private var cachedSeed: String? = null
    private var cachedPassword: CharArray? = null

    private val prefs =
        ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)

    private var lastPrice =
        prefs.getFloat("last_price", 65000f).toDouble()

    fun hasWallets(): Boolean {
        return prefs.all.keys.any {
            it.endsWith("_seed")
        }
    }

    fun getActive(): WalletInfo? {
        return active
    }

    fun getActiveId(): String? {
        return active?.id
    }

    fun unlock(id: String, password: String): Boolean {

        return try {

            val enc =
                prefs.getString("${id}_seed", "") ?: return false

            val seed =
                CryptoUtil.decrypt(enc, password)

            val name =
                prefs.getString("${id}_name", "Wallet") ?: "Wallet"

            cachedSeed = seed
            cachedPassword = password.toCharArray()

            active = WalletInfo(id, name)

            true

        } catch (e: Exception) {

            false
        }
    }

    fun lock() {

        cachedPassword?.fill('0')

        cachedPassword = null
        cachedSeed = null
        active = null
    }

    fun create(
        name: String,
        password: String
    ): WalletInfo {

        val id = UUID.randomUUID().toString()

        val seed =
            DeterministicSeed(
                SecureRandom(),
                128,
                ""
            )

        val mnemonic =
            seed.mnemonicCode!!.joinToString(" ")

        val walletName =
            if (name.isBlank()) {
                "Ví Bitcoin"
            } else {
                name
            }

        val address =
            getTrustWalletAddress(mnemonic)

        val info =
            WalletInfo(id, walletName)

        val enc =
            CryptoUtil.encrypt(mnemonic, password)

        prefs.edit()
            .putString("${id}_name", walletName)
            .putString("${id}_seed", enc)
            .putString("${id}_address", address)
            .apply()

        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        active = info

        return info
    }

    private fun getTrustWalletAddress(seedPhrase: String): String {

        return try {

            val seed =
                DeterministicSeed(
                    seedPhrase,
                    null,
                    "",
                    0L
                )

            val seedBytes =
                seed.seedBytes ?: return ""

            val masterKey =
                HDKeyDerivation.createMasterPrivateKey(seedBytes)

            val purposeKey =
                HDKeyDerivation.deriveChildKey(
                    masterKey,
                    ChildNumber(44, true)
                )

            val coinKey =
                HDKeyDerivation.deriveChildKey(
                    purposeKey,
                    ChildNumber(0, true)
                )

            val accountKey =
                HDKeyDerivation.deriveChildKey(
                    coinKey,
                    ChildNumber(0, true)
                )

            val changeKey =
                HDKeyDerivation.deriveChildKey(
                    accountKey,
                    ChildNumber.ZERO
                )

            val addressKey: DeterministicKey =
                HDKeyDerivation.deriveChildKey(
                    changeKey,
                    ChildNumber.ZERO
                )

            LegacyAddress
                .fromKey(params, addressKey)
                .toString()

        } catch (e: Exception) {
            ""
        }
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
                    .replace(Regex("\\s+"), " ")

            val words = clean.split(" ")

            if (words.size != 12 &&
                words.size != 24
            ) {
                return null
            }

            MnemonicCode.INSTANCE.check(words)

            val id =
                UUID.randomUUID().toString()

            val walletName =
                if (name.isBlank()) {
                    "Imported Wallet"
                } else {
                    name
                }

            val address =
                getTrustWalletAddress(clean)

            val info =
                WalletInfo(id, walletName)

            val enc =
                CryptoUtil.encrypt(clean, password)

            prefs.edit()
                .putString("${id}_name", walletName)
                .putString("${id}_seed", enc)
                .putString("${id}_address", address)
                .apply()

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

        prefs.edit()
            .remove("${id}_name")
            .remove("${id}_seed")
            .remove("${id}_address")
            .apply()
    }

    fun getSeed(): String {
        return cachedSeed ?: ""
    }

    fun getAddress(): String {

        val id = active?.id ?: return ""

        return prefs.getString(
            "${id}_address",
            ""
        ) ?: ""
    }

    fun getBalance(): Double {
        return 0.0
    }

    fun getTransactions(): List<TransactionInfo> {
        return emptyList()
    }

    fun estimateFee(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): Double {

        return (feeRateSatVb * 250.0) / 100000000.0
    }

    fun send(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): String {

        return "Send mainnet sẽ làm tiếp"
    }

    fun price(): Double {
        return lastPrice
    }

    fun getFeeRates(): FeeRates {

        return FeeRates(
            slow = 5,
            normal = 10,
            fast = 20
        )
    }

    fun init() {}

    fun stop() {}

    fun onProgress(
        cb: (Int, String) -> Unit
    ) {
        cb(100, "Ví sẵn sàng")
    }

    fun changePassword(
        oldPassword: String,
        newPassword: String
    ): Boolean {

        return try {

            true

        } catch (e: Exception) {

            false
        }
    }

    fun rename(
        newName: String
    ) {

        val id = active?.id ?: return

        prefs.edit()
            .putString("${id}_name", newName)
            .apply()

        active =
            WalletInfo(id, newName)
    }
}