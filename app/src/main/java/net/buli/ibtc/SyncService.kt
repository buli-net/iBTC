package net.buli.ibtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File

class SyncService : Service() {

    private lateinit var kit: WalletAppKit
    private val CHANNEL_ID = "bitcoin_sync_channel"
    private val NOTIFICATION_ID = 1
    private var progressCallback: ((Int, String) -> Unit)? = null
    private var currentWalletId: String = ""
    private var isSynced = false
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
        startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seedPhrase = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        if (seedPhrase != null) {
            startBitcoinSync(walletId, seedPhrase)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Đồng bộ Bitcoin",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị trạng thái đồng bộ blockchain"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iBTC Wallet")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            val params = MainNetParams.get()
            val dir = File(filesDir, "spv_wallets")
            if (!dir.exists()) dir.mkdirs()

            val walletFile = File(dir, walletId)

            if (!walletFile.exists()) {
                val words = seedPhrase.trim().lowercase().split(" ")
                if (words.size == 12 || words.size == 24) {
                    val seed = DeterministicSeed(words, null, "", 0L)
                    val wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH)
                    wallet.saveToFile(walletFile)
                }
            }

            kit = WalletAppKit(params, dir, walletId)

            kit.setBlockingStartup(false)
            kit.setDownloadListener(object : DownloadProgressTracker() {
                override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                    val percent = pct.toInt()
                    lastProgress = percent
                    lastMessage = "Đồng bộ blockchain: $percent%"
                    updateNotification(lastMessage)
                    progressCallback?.invoke(lastProgress, lastMessage)
                }

                override fun doneDownload() {
                    isSynced = true
                    lastProgress = 100
                    lastMessage = "Đã đồng bộ blockchain"
                    updateNotification(lastMessage)
                    progressCallback?.invoke(lastProgress, lastMessage)
                }
            })

            kit.startAsync()
        }.start()
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun setProgressCallback(callback: ((Int, String) -> Unit)?) {
        progressCallback = callback
        callback?.invoke(lastProgress, lastMessage)
    }

    fun getWallet(): Wallet? = kit?.wallet()
    fun getPeerGroup() = kit?.peerGroup()
    fun getWalletId(): String = currentWalletId
    fun isWalletSynced(): Boolean = isSynced

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        kit?.stopAsync()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}