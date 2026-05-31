package net.buli.ibtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.bitcoinj.core.Context as BtcContext
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.Wallet
import java.io.File

class SyncService : Service() {

    private val CHANNEL_ID = "bitcoin_sync_channel"
    private val NOTIFICATION_ID = 1
    private var progressCallback: ((Int, String) -> Unit)? = null
    private var currentWalletId: String = ""
    private var isSynced = false
    private var lastProgress = 0
    private var lastMessage = "Đang khởi động..."
    private var kit: WalletAppKit? = null
    private lateinit var prefs: SharedPreferences
    private var isStarting = false

    companion object {
        private var instance: SyncService? = null
        fun getInstance(): SyncService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("sync_state", Context.MODE_PRIVATE)

        lastProgress = prefs.getInt("last_progress", 0)
        lastMessage = prefs.getString("last_message", "Đang khởi động...") ?: "Đang khởi động..."
        isSynced = prefs.getBoolean("is_synced", false)

        createNotificationChannel()
        startForegroundService()
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seedPhrase = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id")

        if (seedPhrase == null || walletId == null) {
            return START_NOT_STICKY
        }

        currentWalletId = walletId

        if (isStarting) {
            return START_NOT_STICKY
        }

        if (kit != null && kit?.isRunning == true && kit?.wallet() != null) {
            setProgressCallback(progressCallback)
            return START_NOT_STICKY
        }

        startBitcoinSync(walletId, seedPhrase)
        return START_NOT_STICKY
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

    private fun saveProgress(progress: Int, message: String) {
        lastProgress = progress
        lastMessage = message
        prefs.edit()
            .putInt("last_progress", progress)
            .putString("last_message", message)
            .apply()
    }

    private fun saveSyncedState(synced: Boolean) {
        isSynced = synced
        prefs.edit().putBoolean("is_synced", synced).apply()
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        if (isStarting) return
        isStarting = true

        saveSyncedState(false)

        Thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                // KHÔNG tạo wallet thủ công - để WalletAppKit tự quản lý

                try {
                    kit?.stopAsync()
                    kit?.awaitTerminated()
                } catch (_: Exception) {}

                kit = null

                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            var p = pct.toInt()
                            if (p < 0) p = 0
                            if (p > 100) p = 100
                            val msg = if (p < 100) "Đồng bộ blockchain: $p%" else "Đã đồng bộ blockchain"
                            saveProgress(p, msg)
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }

                        override fun doneDownload() {
                            val wallet = kit?.wallet()
                            val peerGroup = kit?.peerGroup()
                            val reallySynced = wallet != null && 
                                              wallet.lastBlockSeenHeight > 0 &&
                                              peerGroup != null && 
                                              peerGroup.connectedPeers.isNotEmpty()
                            saveSyncedState(reallySynced)
                            saveProgress(100, if (reallySynced) "Đã đồng bộ blockchain" else "Chờ kết nối peer...")
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }
                    })
                    startAsync()
                    awaitRunning()
                }
                kit = newKit
                progressCallback?.invoke(lastProgress, lastMessage)

            } catch (e: Exception) {
                e.printStackTrace()
                saveProgress(lastProgress, "Lỗi sync: ${e.message}")
                updateNotification(lastMessage)
                progressCallback?.invoke(lastProgress, lastMessage)
                kit = null
            } finally {
                isStarting = false
            }
        }.start()
    }

    fun refreshProgress() {
        progressCallback?.invoke(lastProgress, lastMessage)
    }

    fun setProgressCallback(callback: ((Int, String) -> Unit)?) {
        progressCallback = callback
        callback?.invoke(lastProgress, lastMessage)
    }

    fun getWallet(): Wallet? = try { kit?.wallet() } catch (_: Exception) { null }
    fun getPeerGroup() = try { kit?.peerGroup() } catch (_: Exception) { null }
    fun getWalletId(): String = currentWalletId
    fun isWalletSynced(): Boolean = isSynced

    override fun onDestroy() {
        try {
            kit?.stopAsync()
            kit?.awaitTerminated()
        } catch (_: Exception) {}
        kit = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}