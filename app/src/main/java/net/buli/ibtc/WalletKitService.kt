package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import java.io.File

object WalletKitService {

    private var kit: WalletAppKit? = null

    fun start(context: Context, walletId: String, seedPhrase: String? = null) {
        if (kit != null) return

        val params = MainNetParams.get()
        val dir = File(context.filesDir, "spv_wallets")
        if (!dir.exists()) dir.mkdirs()

        kit = object : WalletAppKit(params, dir, walletId) {
            override fun onSetupCompleted() {
                println("WalletAppKit READY")
                // Import seed đúng cách nếu có và wallet chưa có seed
                if (seedPhrase != null && wallet().keyChainSeed.isEncrypted() && wallet().keyChainSeed.mnemonicCode == null) {
                    val words = seedPhrase.trim().lowercase().split(" ")
                    if (words.size == 12 || words.size == 24) {
                        val seed = DeterministicSeed(words, null, "", 0L)
                        wallet().importSeed(seed)
                    }
                }
            }
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