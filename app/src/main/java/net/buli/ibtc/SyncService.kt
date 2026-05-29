package net.buli.ibtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.util.concurrent.TimeUnit

class SyncService : Service() {

private var kit: WalletAppKit? = null

private val CHANNEL_ID = "bitcoin_sync_channel"
private val NOTIFICATION_ID = 1

private var progressCallback: ((Int, String) -> Unit)? = null

private var currentWalletId: String = ""

@Volatile
private var isSynced = false

@Volatile
private var syncing = false

private var lastProgress = 0
private var lastMessage = "Đang khởi động..."

companion object {
    private var instance: SyncService? = null
    fun getInstance(): SyncService? = instance
}

override fun onCreate() {
    super.onCreate()

    instance = this

    createNotificationChannel()

    startForeground(
        NOTIFICATION_ID,
        buildNotification(lastMessage)
    )
}

override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
): Int {

    val seedPhrase = intent?.getStringExtra("seed_phrase")
    val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"

    currentWalletId = walletId

    if (seedPhrase != null) {

        if (syncing) {
            setProgressCallback(progressCallback)
            return START_STICKY
        }

        startBitcoinSync(walletId, seedPhrase)
    }

    return START_STICKY
}

private fun createNotificationChannel() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bitcoin Sync",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(channel)
    }
}

private fun buildNotification(text: String): Notification {

    val intent = Intent(this, MainActivity::class.java)

    val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("iBTC Wallet")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentIntent(pendingIntent)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()
}

private fun updateNotification(text: String) {

    val manager =
        getSystemService(NotificationManager::class.java)

    manager.notify(
        NOTIFICATION_ID,
        buildNotification(text)
    )
}

private fun startBitcoinSync(
    walletId: String,
    seedPhrase: String
) {

    syncing = true
    isSynced = false

    Thread {

        try {

            val params = MainNetParams.get()

            Context.propagate(
                Context(params)
            )

            val dir = File(filesDir, "spv_wallets")

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val walletFile =
                File(dir, "$walletId.wallet")

            if (!walletFile.exists()) {

                val words =
                    seedPhrase.trim()
                        .lowercase()
                        .split(" ")

                val seed = DeterministicSeed(
                    words,
                    null,
                    "",
                    0L
                )

                val wallet = Wallet.fromSeed(
                    params,
                    seed,
                    Script.ScriptType.P2WPKH
                )

                wallet.saveToFile(walletFile)
            }

            try {
                kit?.stopAsync()
                kit?.awaitTerminated()
            } catch (_: Exception) {
            }

            kit = object : WalletAppKit(
                params,
                dir,
                walletId
            ) {

                override fun onSetupCompleted() {

                    wallet().autosaveToFile(
                        File(dir, "$walletId.wallet"),
                        5,
                        TimeUnit.SECONDS,
                        null
                    )
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

                        var p = pct.toInt()

                        if (p < 0) p = 0
                        if (p > 99) p = 99

                        lastProgress = p

                        lastMessage =
                            "Đồng bộ blockchain: $p%"

                        updateNotification(lastMessage)

                        progressCallback?.invoke(
                            lastProgress,
                            lastMessage
                        )
                    }

                    override fun doneDownload() {

                        isSynced = true
                        syncing = false

                        lastProgress = 100
                        lastMessage =
                            "Đồng bộ hoàn tất"

                        updateNotification(lastMessage)

                        progressCallback?.invoke(
                            100,
                            lastMessage
                        )
                    }
                }
            )

            kit?.startAsync()
            kit?.awaitRunning()

        } catch (e: Exception) {

            syncing = false

            lastMessage =
                "Lỗi sync: ${e.message}"

            updateNotification(lastMessage)

            progressCallback?.invoke(
                lastProgress,
                lastMessage
            )
        }

    }.start()
}

fun setProgressCallback(
    callback: ((Int, String) -> Unit)?
) {

    progressCallback = callback

    callback?.invoke(
        lastProgress,
        lastMessage
    )
}

fun getWallet(): Wallet? {

    return try {
        kit?.wallet()
    } catch (_: Exception) {
        null
    }
}

fun getPeerGroup(): PeerGroup? {

    return try {
        kit?.peerGroup()
    } catch (_: Exception) {
        null
    }
}

fun isWalletSynced(): Boolean {
    return isSynced
}

override fun onDestroy() {

    super.onDestroy()

    instance = null

    try {

        kit?.stopAsync()
        kit?.awaitTerminated()

    } catch (_: Exception) {
    }
}

override fun onBind(intent: Intent?): IBinder? = null

}