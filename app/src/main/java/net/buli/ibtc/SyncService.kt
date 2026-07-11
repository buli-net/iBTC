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
    @Volatile private var isSynced = false
    private var lastProgress = 0
    private var lastMessage = "Đang khởi động..."
    private var kit: WalletAppKit? = null
    private lateinit var prefs: SharedPreferences

    private var blocksSoFar = 0
    private var totalBlocks = 0

    companion object {
        private var instance: SyncService? = null
        fun getInstance(): SyncService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        isSynced = prefs.getBoolean("is_synced", false)
        lastProgress = prefs.getInt("last_progress", 0)
        lastMessage = prefs.getString("last_message", "Đang khởi động...") ?: "Đang khởi động..."
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seedPhrase = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        if (kit != null && kit?.isRunning == true) {
            setProgressCallback(progressCallback)
            return START_NOT_STICKY
        }
        if (seedPhrase.isNullOrEmpty()) return START_NOT_STICKY
        startBitcoinSync(walletId)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Đồng bộ Bitcoin", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Hiển thị trạng thái đồng bộ blockchain"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("iBTC Wallet").setContentText(message).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(pendingIntent).setOngoing(true).build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun saveProgress(progress: Int, message: String) {
        lastProgress = progress
        lastMessage = message
        prefs.edit().putInt("last_progress", progress).putString("last_message", message).apply()
    }

    // Đây là code mẫu gốc của bitcoinj, không chế thêm
    private fun startBitcoinSync(walletId: String) {
        Thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                // Dừng kit cũ nếu có, đúng như sample WalletAppKit
                try { kit?.stopAsync()?.awaitTerminated() } catch (e: Exception) {}
                kit = null
                isSynced = false

                val kit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setAutoSave(true)
                    // Dùng checkpoint gốc của bitcoinj, không setCheckpoints(null)
                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            super.progress(pct, blocksSoFar, date)
                            val p = pct.toInt().coerceIn(0, 100)
                            val msg = if (p < 100) "Đồng bộ blockchain: $p%" else "Đã đồng bộ blockchain"
                            saveProgress(p, msg)
                            updateNotification(msg)
                            progressCallback?.invoke(p, msg)
                        }

                        override fun doneDownload() {
                            super.doneDownload()
                            isSynced = true
                            prefs.edit().putBoolean("is_synced", true).apply()
                            saveProgress(100, "Đã đồng bộ blockchain")
                            updateNotification(lastMessage)
                            progressCallback?.invoke(100, lastMessage)
                        }
                    })
                }

                kit.startAsync()
                kit.awaitRunning()

                this.kit = kit
                this.blocksSoFar = kit.chain().chainHead.height
                this.totalBlocks = kit.chain().chainHead.height

            } catch (e: Exception) {
                // Dùng toString() như sample gốc để không ra null
                val err = e.toString()
                saveProgress(lastProgress, "Lỗi sync: $err")
                updateNotification(lastMessage)
                progressCallback?.invoke(lastProgress, lastMessage)
            }
        }.start()
    }

    fun refreshProgress() { progressCallback?.invoke(lastProgress, lastMessage) }
    fun setProgressCallback(cb: ((Int, String) -> Unit)?) { progressCallback = cb; cb?.invoke(lastProgress, lastMessage) }
    fun getWallet(): Wallet? = try { kit?.wallet() } catch (e: Exception) { null }
    fun getPeerGroup() = try { kit?.peerGroup() } catch (e: Exception) { null }
    fun getWalletId() = currentWalletId
    fun isWalletSynced() = isSynced
    fun getBlocksSoFar() = blocksSoFar
    fun getTotalBlocks() = totalBlocks
    override fun onDestroy() { try { kit?.stopAsync()?.awaitTerminated() } catch (e: Exception) {}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
