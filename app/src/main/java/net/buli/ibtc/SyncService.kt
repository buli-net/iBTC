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
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    private val CHANNEL_ID = "bitcoin_sync_channel"
    private val NOTIFICATION_ID = 1
    private var progressCallback: ((Int, String) -> Unit)? = null
    private var currentWalletId: String = ""
    @Volatile private var isSynced = false
    private var lastProgress = 0
    private var lastMessage = "Đang khởi động..."
    @Volatile private var kit: WalletAppKit? = null
    private lateinit var prefs: SharedPreferences

    @Volatile private var isEngineRunning = false
    private var engineThread: Thread? = null

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
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
        }
        
        updateNotification(lastMessage)
        progressCallback?.invoke(lastProgress, lastMessage)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seedPhrase = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        
        if (kit != null && kit?.isRunning == true && kit?.wallet() != null) {
            setProgressCallback(progressCallback)
            return START_NOT_STICKY
        }
        
        if (seedPhrase.isNullOrEmpty()) return START_NOT_STICKY
        startBitcoinSync(walletId, seedPhrase)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Đồng bộ Bitcoin", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Hiển thị trạng thái đồng bộ blockchain"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iBTC Wallet")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun saveProgress(progress: Int, message: String) {
        val validProgress = progress.coerceIn(0, 100)
        lastProgress = validProgress
        lastMessage = message
        prefs.edit()
            .putInt("last_progress", validProgress)
            .putString("last_message", message)
            .putBoolean("is_synced", validProgress >= 99)
            .apply()
        
        if (validProgress >= 99 && !isSynced) {
            isSynced = true
        }
        
        progressCallback?.invoke(validProgress, message)
        updateNotification(message)
    }

    /**
     * Tính toán tiến độ dựa trên số block đã tải
     */
    private fun updateProgressFromWallet() {
        val wallet = kit?.wallet() ?: return
        val chain = kit?.chain()
        val peerGroup = kit?.peerGroup()
        
        val walletHeight = wallet.lastBlockSeenHeight
        val chainHeight = chain?.chainHead?.height ?: peerGroup?.mostCommonChainHeight ?: 0
        
        if (chainHeight > 0 && walletHeight > 0) {
            val percent = ((walletHeight.toDouble() / chainHeight) * 100).toInt().coerceIn(0, 98)
            if (percent != lastProgress) {
                saveProgress(percent, "Đồng bộ blockchain: ${percent}%")
            }
        }
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            try {
                isSynced = false
                saveProgress(0, "Khởi tạo ví...")
                prefs.edit().putBoolean("is_synced", false).apply()
                
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))
                
                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()
                
                kit?.let { old ->
                    try {
                        old.stopAsync()
                        old.awaitTerminated()
                    } catch (_: Exception) {}
                }
                kit = null
                isEngineRunning = false
                engineThread?.interrupt()
                
                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setAutoStop(false)
                    setUserAgent("iBTC", "1.0")
                }
                
                val words = seedPhrase.trim().split(Regex("\\s+"))
                if (words.size !in listOf(12, 15, 18, 21, 24)) {
                    saveProgress(0, "Seed phrase không hợp lệ (cần 12-24 từ)")
                    return@Thread
                }
                
                val seed = DeterministicSeed(words, null, "", System.currentTimeMillis() / 1000)
                newKit.restoreWalletFromSeed(seed)
                
                saveProgress(0, "Đang kết nối mạng Bitcoin...")
                newKit.startAsync()
                newKit.awaitRunning()
                
                kit = newKit
                startEngine(newKit)
                
                saveProgress(lastProgress, lastMessage)
                
            } catch (e: Exception) {
                e.printStackTrace()
                saveProgress(lastProgress, "Lỗi: ${e.message?.take(100)}")
                kit = null
            }
        }.start()
    }

    private fun startEngine(kitRef: WalletAppKit) {
        if (isEngineRunning) return
        isEngineRunning = true
        
        engineThread = Thread {
            var lastWalletHeight = 0
            var stuckCount = 0
            
            while (isEngineRunning) {
                try {
                    val wallet = kitRef.wallet()
                    if (wallet == null) {
                        Thread.sleep(2000)
                        continue
                    }
                    
                    val chain = kitRef.chain()
                    val peerGroup = kitRef.peerGroup()
                    val walletHeight = wallet.lastBlockSeenHeight
                    val chainHeight = chain?.chainHead?.height ?: peerGroup?.mostCommonChainHeight ?: 0
                    
                    // Kiểm tra tình trạng kẹt
                    if (chainHeight > 0 && walletHeight == lastWalletHeight) {
                        stuckCount++
                        if (stuckCount > 10) {
                            saveProgress(lastProgress, "Đang xử lý giao dịch...")
                        }
                    } else {
                        stuckCount = 0
                        lastWalletHeight = walletHeight
                    }
                    
                    // Cập nhật tiến độ
                    if (chainHeight > 0) {
                        val percent = ((walletHeight.toDouble() / chainHeight) * 100).toInt().coerceIn(0, 98)
                        if (percent != lastProgress && percent > 0) {
                            saveProgress(percent, "Đồng bộ blockchain: ${percent}%")
                        }
                    }
                    
                    // Kiểm tra hoàn thành
                    if (!isSynced && walletHeight >= chainHeight - 1 && chainHeight > 0) {
                        saveProgress(100, "Đã đồng bộ blockchain")
                        isSynced = true
                    }
                    
                    Thread.sleep(5000)
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                    Thread.sleep(5000)
                }
            }
        }.apply { start() }
    }

    fun refreshProgress() {
        updateProgressFromWallet()
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
    fun getBlocksSoFar(): Int = kit?.wallet()?.lastBlockSeenHeight ?: 0
    fun getTotalBlocks(): Int = try { 
        val chain = kit?.chain()
        val peerGroup = kit?.peerGroup()
        chain?.chainHead?.height ?: peerGroup?.mostCommonChainHeight ?: 0
    } catch (_: Exception) { 0 }

    override fun onDestroy() {
        super.onDestroy()
        isEngineRunning = false
        engineThread?.interrupt()
        try {
            kit?.stopAsync()
            kit?.awaitTerminated(5, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        kit = null
        instance = null
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}