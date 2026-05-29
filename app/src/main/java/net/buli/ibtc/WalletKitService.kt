package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import java.io.File

object WalletKitService {

    private var kit: WalletAppKit? = null

    fun start(context: Context, walletId: String, seedPhrase: String? = null) {
        if (kit != null) return

        val params = MainNetParams.get()
        val file = File(context.filesDir, "spv_wallets")

        kit = object : WalletAppKit(params, file, walletId) {
            override fun onSetupCompleted() {
                println("WalletAppKit ready")
            }
        }

        // Nếu có seed, khôi phục wallet (override file cũ nếu cần)
        if (seedPhrase != null && !file.exists()) {
            val seed = DeterministicSeed(seedPhrase.split(" "), null, "", 0L)
            kit?.wallet()?.addKeyChain(seed)
        }

        kit?.setBlockingStartup(false)
        kit?.setDownloadListener(object : DownloadProgressTracker() {
            override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                println("Sync: $pct%")
            }
            override fun doneDownload() {
                println("Blockchain synced")
            }
        })
        kit?.startAsync()
    }

    fun wallet() = kit?.wallet()
    fun peerGroup() = kit?.peerGroup()
    fun stop() {
        kit?.stopAsync()
        kit = null
    }
}