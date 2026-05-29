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
import org.bitcoinj.core.Context as BtcContext
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    private val CHANNEL_ID = "bitcoin_sync_channel"
    private val NOTIFICATION_ID = 1
    private var progressCallback: ((Int, String) -> Unit)? = null
    private var currentWalletId: String = ""
    @Volatile private var isSynced = false
    @Volatile private var syncing = false
    private var lastProgress = 0
    private var lastMessage = "Đang khởi động..."
    private var kit: WalletAppKit? = null

    companion object {
        private var instance: SyncService? = null
        fun getInstance(): SyncService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        synchronized(this) {
            if (syncing) return
            syncing = true
            isSynced = false
        }
        Thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                val walletFile = File(dir, "$walletId.wallet")

                // Tạo wallet từ seed nếu chưa có
                if (!walletFile.exists()) {
                    val words = seedPhrase.trim().lowercase().split(" ")
                    if (words.size == 12 || words.size == 24) {
                        val seed = DeterministicSeed(words, null, "", 0L)
                        val wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH)
                        wallet.saveToFile(walletFile)
                    }
                }

                // Dừng kit cũ nếu có
                try {
                    kit?.stopAsync()
                    kit?.awaitTerminated()
                } catch (_: Exception) {}

                // Tạo kit mới
                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            var p = pct.toInt()
                            if (p < 0) p = 0
                            if (p > 100) p = 100
                            lastProgress = p
                            lastMessage = if (p < 100) "Đồng bộ blockchain: $p%" else "Đã đồng bộ blockchain (xử lý...)"
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }

                        override fun doneDownload() {
                            // Đợi một chút để peer group ổn định
                            Thread.sleep(3000)
                            isSynced = true
                            lastProgress = 100
                            lastMessage = "Đã đồng bộ blockchain"
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }
                    })
                    startAsync()
                    // Đợi đến khi running (có thể block nhưng trong thread riêng)
                    try {
                        awaitRunning()
                    } catch (e: Exception) {
                        // Nếu fail, thử lại với awaitRunning có timeout
                        awaitRunning(30, TimeUnit.SECONDS)
                    }
                    wallet().autosaveToFile(File(dir, "$walletId.wallet"), 1, TimeUnit.SECONDS, null)
                }
                kit = newKit

            } catch (e: Exception) {
                syncing = false
                lastMessage = "Lỗi sync: ${e.message}"
                updateNotification(lastMessage)
                progressCallback?.invoke(lastProgress, lastMessage)
                // Nếu lỗi, cho phép thử lại lần sau
                Thread.sleep(5000)
                syncing = false // cho phép start lại
            }
        }.start()
    }

    fun setProgressCallback(callback: ((Int, String) -> Unit)?) {
        progressCallback = callback
        val displayProgress = if (lastProgress > 100) 100 else lastProgress
        val displayMessage = when {
            isSynced -> "Đã đồng bộ blockchain"
            lastProgress >= 100 -> "Đã đồng bộ blockchain"
            else -> lastMessage
        }
        callback?.invoke(displayProgress, displayMessage)
    }

    fun getWallet(): Wallet? = try { kit?.wallet() } catch (_: Exception) { null }
    fun getPeerGroup() = try { kit?.peerGroup() } catch (_: Exception) { null }
    fun getWalletId(): String = currentWalletId
    fun isWalletSynced(): Boolean = isSynced

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            kit?.stopAsync()
            kit?.awaitTerminated()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}