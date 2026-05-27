package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicHierarchy
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
        ctx.getSharedPreferences(
            "wallets",
            Context.MODE_PRIVATE
        )

    private var lastPrice =
        prefs.getFloat(
            "last_price",
            65000f
        ).toDouble()

    fun hasWallets(): Boolean {

        return prefs.all.keys.any {
            it.endsWith("_seed")
        }
    }

    fun getActive(): WalletInfo? {
        return active
    }

    fun getActiveId(): String? {

        return prefs.all.keys.mapNotNull { key ->

            if (key.endsWith("_seed")) {
                key.removeSuffix("_seed")
            } else {
                null
            }
        }.firstOrNull()
    }

    fun unlock(
        id: String,
        password: String
    ): Boolean {

        if (
            prefs.getInt("${id}_attempts", 0) >= 5
        ) {
            return false
        }

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
            cachedPassword =
                password.toCharArray()

            active =
                WalletInfo(id, name)

            prefs.edit()
                .putInt("${id}_attempts", 0)
                .apply()

            true

        } catch (e: Exception) {

            val attempts =
                prefs.getInt(
                    "${id}_attempts",
                    0
                ) + 1

            prefs.edit()
                .putInt(
                    "${id}_attempts",
                    attempts
                )
                .apply()

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
            if (name.isBlank()) {
                "Ví Bitcoin"
            } else {
                name
            }

        val address =
            deriveTrustAddress(mnemonic)

        val info =
            WalletInfo(id, walletName)

        val enc =
            CryptoUtil.encrypt(
                mnemonic,
                password
            )

        prefs.edit()
            .putString(
                "${id}_name",
                walletName
            )
            .putString(
                "${id}_seed",
                enc
            )
            .putString(
                "${id}_address",
                address
            )
            .apply()

        cachedSeed = mnemonic
        cachedPassword =
            password.toCharArray()

        active = info

        return info
    }

    private fun deriveTrustAddress(
        seedPhrase: String
    ): String {

        return try {

            val seed =
                DeterministicSeed(
                    seedPhrase.split(" "),
                    null,
                    "",
                    0L
                )

            val seedBytes =
                seed.seedBytes ?: return ""

            val masterKey =
                HDKeyDerivation
                    .createMasterPrivateKey(
                        seedBytes
                    )

            val hierarchy =
                DeterministicHierarchy(
                    masterKey
                )

            val path = listOf(
                ChildNumber(44, true),
                ChildNumber(0, true),
                ChildNumber(0, true),
                ChildNumber.ZERO,
                ChildNumber.ZERO
            )

            val key =
                hierarchy.get(
                    path,
                    true,
                    true
                )

            LegacyAddress
                .fromKey(params, key)
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
                    .replace(
                        Regex("\\s+"),
                        " "
                    )

            val words =
                clean.split(" ")

            if (words.size < 12) {
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
                if (name.isBlank()) {
                    "Imported Wallet"
                } else {
                    name
                }

            val address =
                deriveTrustAddress(clean)

            val enc =
                CryptoUtil.encrypt(
                    clean,
                    password
                )

            prefs.edit()
                .putString(
                    "${id}_name",
                    walletName
                )
                .putString(
                    "${id}_seed",
                    enc
                )
                .putString(
                    "${id}_address",
                    address
                )
                .apply()

            val info =
                WalletInfo(
                    id,
                    walletName
                )

            cachedSeed = clean
            cachedPassword =
                password.toCharArray()

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
            .remove("${id}_attempts")
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

    fun getBalance(): Double {
        return 0.0
    }

    fun getTransactions():
            List<TransactionInfo> {

        return emptyList()
    }

    fun estimateFee(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): Double {

        return (
            feeRateSatVb * 250.0
        ) / 100000000.0
    }

    fun send(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): String {

        return "Send mainnet sẽ làm tiếp"
    }

    fun price(): Double {

        return try {

            val json =
                httpGet(
                    "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"
                )

            if (json.isBlank()) {
                return lastPrice
            }

            val obj =
                JSONObject(json)

            val rate =
                obj.getString("price")
                    .toDouble()

            lastPrice = rate

            prefs.edit()
                .putFloat(
                    "last_price",
                    rate.toFloat()
                )
                .apply()

            rate

        } catch (e: Exception) {

            lastPrice
        }
    }

    fun getFeeRates(): FeeRates {

        return FeeRates(
            slow = 5,
            normal = 10,
            fast = 20
        )
    }

    private fun httpGet(
        url: String
    ): String {

        return try {

            val conn =
                URL(url)
                    .openConnection()
                        as HttpURLConnection

            conn.requestMethod = "GET"

            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0"
            )

            conn.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        } catch (e: Exception) {

            ""
        }
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
    newPassword: String,
    cb: ((Boolean) -> Unit)? = null
): Boolean {

    return try {

        val id =
            active?.id ?: return false

        val seed =
            cachedSeed ?: return false

        val enc =
            CryptoUtil.encrypt(
                seed,
                newPassword
            )

        prefs.edit()
            .putString(
                "${id}_seed",
                enc
            )
            .apply()

        cachedPassword =
            newPassword.toCharArray()

        cb?.invoke(true)

        true

    } catch (e: Exception) {

        cb?.invoke(false)

        false
    }
}

fun rename(
    newName: String,
    cb: (() -> Unit)? = null
) {

    val id =
        active?.id ?: return

    prefs.edit()
        .putString(
            "${id}_name",
            newName
        )
        .apply()

    active =
        WalletInfo(
            id,
            newName
        )

    cb?.invoke()
}
   
}