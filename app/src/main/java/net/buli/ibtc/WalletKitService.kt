package net.buli.ibtc

import android.content.Context
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File

object WalletKitService {

    private var kit: WalletAppKit? = null

    fun start(context: Context, walletId: String, seedPhrase: String? = null) {
        if (kit != null) return

        val params = MainNetParams.get()
        val dir = File(context.filesDir, "spv_wallets")
        if (!dir.exists()) dir.mkdirs()

        val walletFile = File(dir, walletId)

        // Nếu có seed và file wallet chưa tồn tại, tạo wallet từ seed và lưu
        if (seedPhrase != null && !walletFile.exists()) {
            val words = seedPhrase.trim().lowercase().split(" ")
            if (words.size == 12 || words.size == 24) {
                val seed = DeterministicSeed(words, null, "", 0L)
                val wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH)
                wallet.saveToFile(walletFile)
            }
        }

        kit = object : WalletAppKit(params, dir, walletId) {
            override fun onSetupCompleted() {
                println("WalletAppKit READY")
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