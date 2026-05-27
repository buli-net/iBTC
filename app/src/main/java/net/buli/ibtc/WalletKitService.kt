package net.buli.ibtc

import android.content.Context
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import java.io.File

object WalletKitService {

    private var kit: WalletAppKit? = null

    fun start(context: Context, walletId: String) {
        if (kit != null) return

        val params = MainNetParams.get()

        kit = object : WalletAppKit(
            params,
            File(context.filesDir, "spv_wallets"),
            walletId
        ) {
            override fun onSetupCompleted() {

            }
        }

        kit?.setBlockingStartup(false)
        kit?.startAsync()
    }

    fun stop() {
        try {
            kit?.stopAsync()
        } catch (_: Exception) {
        }
        kit = null
    }

    fun isRunning(): Boolean {
        return kit != null
    }
}