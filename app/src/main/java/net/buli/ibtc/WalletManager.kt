// WalletAppKit migration build
package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.json.JSONArray
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
        return prefs.all.keys.mapNotNull { key ->
            if (key.endsWith("_seed")) {
                key.removeSuffix("_seed")
            } else {
                null
            }
        }.firstOrNull()
    }

    fun unlock(id: String, password: String): Boolean {

        if (prefs.getInt("${id}_attempts", 0) >= 5) {
            return false
        }

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

            prefs.edit()
                .putInt("${id}_attempts", 0)
                .apply()

            true

        } catch (e: Exception) {

            val attempts =
                prefs.getInt("${id}_attempts", 0) + 1

            prefs.edit()
                .putInt("${id}_attempts", attempts)
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

        val info =
            WalletInfo(id, walletName)

        val enc =
            CryptoUtil.encrypt(mnemonic, password)

        prefs.edit()
            .putString("${id}_name", walletName)
            .putString("${id}_seed", enc)
            .apply()

        cachedSeed = mnemonic
        cachedPassword = password.toCharArray()
        active = info

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
                    .replace(Regex("\\s+"), " ")

            val words = clean.split(" ")

            if (words.size < 12) {
                return null
            }

            DeterministicSeed(
                words,
                null,
                "",
                System.currentTimeMillis() / 1000
            )

            val id =
                UUID.randomUUID().toString()

            val walletName =
                if (name.isBlank()) {
                    "Imported Wallet"
                } else {
                    name
                }

            val info =
                WalletInfo(id, walletName)

            val enc =
                CryptoUtil.encrypt(clean, password)

            prefs.edit()
                .putString("${id}_name", walletName)
                .putString("${id}_seed", enc)
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
            .remove("${id}_attempts")
            .commit()
    }

    fun changePassword(
        id: String,
        oldPass: String,
        newPass: String
    ): Boolean {

        return try {

            val enc =
                prefs.getString("${id}_seed", "") ?: return false

            val seed =
                CryptoUtil.decrypt(enc, oldPass)

            val newEnc =
                CryptoUtil.encrypt(seed, newPass)

            prefs.edit()
                .putString("${id}_seed", newEnc)
                .apply()

            true

        } catch (e: Exception) {
            false
        }
    }

    fun rename(
        id: String,
        newName: String
    ): Boolean {

        return try {

            prefs.edit()
                .putString("${id}_name", newName)
                .apply()

            if (active?.id == id) {
                active = WalletInfo(id, newName)
            }

            true

        } catch (e: Exception) {
            false
        }
    }

    fun init() {}

    fun stop() {}

    fun onProgress(cb: (Int, String) -> Unit) {
        cb(100, "Ví sẵn sàng")
    }

    fun getSeed(): String {
        return cachedSeed ?: ""
    }

    fun getAddress(): String {
        return try {
            val wallet = WalletKitService.wallet()
            wallet?.currentReceiveAddress()?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun getBalance(): Double {
        return try {
            val wallet = WalletKitService.wallet()
            val sat = wallet?.balance?.value ?: 0L
            sat / 1e8
        } catch (_: Exception) {
            0.0
        }
    }

    fun getTransactions(): List<TransactionInfo> {
        return try {
            val wallet = WalletKitService.wallet() ?: return emptyList()

            wallet.transactionsByTime.map {
                val value = it.getValue(wallet).value / 1e8

                TransactionInfo(
                    txId = it.txId.toString(),
                    amount = kotlin.math.abs(value),
                    type = if (value >= 0) "Nhận" else "Gửi",
                    time = it.updateTime ?: java.util.Date()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun send(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): String {

        return "Mainnet send sẽ làm ở bước tiếp theo"
    }

    fun estimateFee(
        to: String,
        amountBTC: Double,
        feeRateSatVb: Int
    ): Double {

        return (feeRateSatVb * 250.0) / 100000000.0
    }

    fun price(): Double {

        try {

            val text =
                httpGet(
                    "https://api.coinbase.com/v2/prices/BTC-USD/spot"
                )

            val amount =
                JSONObject(text)
                    .getJSONObject("data")
                    .getString("amount")
                    .toDouble()

            lastPrice = amount

            prefs.edit()
                .putFloat(
                    "last_price",
                    amount.toFloat()
                )
                .apply()

            return amount

        } catch (_: Exception) {
        }

        return lastPrice
    }

    fun getFeeRates(): FeeRates {

        return try {

            val text =
                httpGet(
                    "https://mempool.space/api/v1/fees/recommended"
                )

            val json =
                JSONObject(text)

            FeeRates(
                slow = json.getInt("hourFee"),
                normal = json.getInt("halfHourFee"),
                fast = json.getInt("fastestFee")
            )

        } catch (e: Exception) {

            FeeRates(
                slow = 5,
                normal = 10,
                fast = 20
            )
        }
    }

    private fun httpGet(url: String): String {

        return try {

            val conn =
                URL(url).openConnection() as HttpURLConnection

            conn.requestMethod = "GET"

            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0"
            )

            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            conn.inputStream.bufferedReader().use {
                it.readText()
            }

        } catch (e: Exception) {
            ""
        }
    }
}