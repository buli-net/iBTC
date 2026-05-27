package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.listeners.DownloadProgressTracker
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
                println("WalletAppKit ready")
            }
        }

        kit?.setBlockingStartup(false)

        kit?.setDownloadListener(
            object : DownloadProgressTracker() {

                override fun progress(
                    pct: Double,
                    blocksSoFar: Int,
                    date: java.util.Date?
                ) {
                    println("Sync: $pct%")
                }

                override fun doneDownload() {
                    println("Blockchain synced")
                }
            }
        )

        kit?.startAsync()
    }

    fun wallet() = kit?.wallet()

    fun peerGroup() = kit?.peerGroup()

    fun stop() {

        try {
            kit?.stopAsync()
        } catch (_: Exception) {
        }

        kit = null
    }
}
