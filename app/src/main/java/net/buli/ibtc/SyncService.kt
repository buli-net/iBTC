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
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.security.SecureRandom
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

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
    private var syncMonitorTimer: Timer? = null

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
        updateNotification(lastMessage)
        progressCallback?.invoke(lastProgress, lastMessage)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seedPhrase = intent?.getStringExtra("seed_phrase") ?: return START_STICKY
        val walletId = intent.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId

        if (kit != null && kit?.isRunning == true && kit?.wallet() != null) {
            setProgressCallback(progressCallback)
            return START_STICKY
        }
        startBitcoinSync(walletId, seedPhrase)
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

    private fun saveProgress(progress: Int, message: String) {
        lastProgress = progress
        lastMessage = message
        prefs.edit().putInt("last_progress", progress).putString("last_message", message).apply()
    }

    // FIX: Kiểm tra sync thực sự hoàn tất
    private fun isReallyFullySynced(kit: WalletAppKit): Boolean {
        val peerGroup = kit.peerGroup() ?: return false
        val wallet = kit.wallet() ?: return false
        val chain = kit.chain() ?: return false
        
        // Kiểm tra đúng cách: peerGroup không còn downloading và wallet đã catch-up
        return !peerGroup.isDownloading && 
               wallet.lastBlockSeenHeight >= peerGroup.bestChainHeight
    }

    // FIX: Giám sát sync hoàn tất thực sự
    private fun startSyncMonitor(kit: WalletAppKit) {
        syncMonitorTimer?.cancel()
        syncMonitorTimer = Timer()
        syncMonitorTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val currentKit = kit
                    if (currentKit != null && isReallyFullySynced(currentKit)) {
                        if (!isSynced) {
                            isSynced = true
                            saveProgress(100, "Đã đồng bộ blockchain và ví")
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                            syncMonitorTimer?.cancel()
                            syncMonitorTimer = null
                        }
                    } else if (currentKit != null && !isSynced) {
                        // Vẫn đang sync, cập nhật message trạng thái
                        val peerGroup = currentKit.peerGroup()
                        val wallet = currentKit.wallet()
                        if (peerGroup != null && wallet != null && !peerGroup.isDownloading) {
                            // Đã download xong nhưng đang xử lý transactions
                            if (lastProgress < 100 && lastProgress >= 95) {
                                saveProgress(lastProgress, "Đang xử lý giao dịch cuối...")
                                updateNotification(lastMessage)
                                progressCallback?.invoke(lastProgress, lastMessage)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Bỏ qua lỗi monitor
                }
            }
        }, 1000, 1000) // Check mỗi giây
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                val walletFile = File(dir, "$walletId.wallet")
                if (!walletFile.exists() && seedPhrase.isNotEmpty()) {
                    val words = seedPhrase.trim().lowercase().split(" ")
                    if (words.size == 12 || words.size == 24) {
                        val seed = DeterministicSeed(words, null, "", System.currentTimeMillis())
                        val wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH)
                        wallet.saveToFile(walletFile)
                    }
                }

                // Dừng kit cũ nếu có
                try {
                    kit?.stopAsync()
                    kit?.awaitTerminated()
                } catch (_: Exception) {}

                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: Date?) {
                            var p = pct.toInt()
                            if (p < 0) p = 0
                            if (p > 100) p = 100
                            
                            // FIX: Tránh đứng 95-99% do progress không tuyến tính
                            // Ép tiến độ cuối stage mượt hơn
                            if (p >= 95 && p < 100) {
                                // Tạo progress giả từ blocksSoFar để tránh đứng yên
                                p = 95 + (blocksSoFar % 5)
                            }
                            
                            // FIX: KHÔNG set 99% thủ công nữa
                            val msg = when {
                                p < 100 -> "Đồng bộ blockchain: $p%"
                                else -> "Đã đồng bộ blockchain"
                            }
                            
                            saveProgress(p, msg)
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }

                        // FIX: KHÔNG dùng doneDownload() làm mốc kết thúc
                        override fun doneDownload() {
                            // KHÔNG kết luận sync xong ở đây
                            // Chỉ cập nhật message là đã download xong blockchain
                            // Nhưng wallet vẫn đang xử lý transactions
                            saveProgress(lastProgress, "Đã tải blockchain, đang xử lý giao dịch...")
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                            
                            // Monitor sẽ phát hiện khi thực sự hoàn tất
                        }
                    })
                    startAsync()
                }
                kit = newKit
                progressCallback?.invoke(lastProgress, lastMessage)
                
                // Bắt đầu monitor để phát hiện sync thực sự hoàn tất
                startSyncMonitor(newKit)

            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.message}")
                updateNotification(lastMessage)
                progressCallback?.invoke(lastProgress, lastMessage)
                kit = null
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
        super.onDestroy()
        instance = null
        syncMonitorTimer?.cancel()
        syncMonitorTimer = null
        // Không stopAsync để giữ trạng thái cho lần sau
    }

    override fun onBind(intent: Intent?): IBinder? = null
}