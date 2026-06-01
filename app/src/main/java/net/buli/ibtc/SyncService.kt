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
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.net.discovery.PeerDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.DaemonThreadFactory
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.net.InetSocketAddress
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

    // Cờ để kiểm soát engine
    @Volatile private var isEngineRunning = false
    private var engineThread: Thread? = null

    // Biến cho progress tracker
    private var lastPercent = -1
    private var lastUpdateTime = 0L

    companion object {
        private var instance: SyncService? = null
        fun getInstance(): SyncService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        
        // Khôi phục trạng thái trước đó
        isSynced = prefs.getBoolean("is_synced", false)
        lastProgress = prefs.getInt("last_progress", 0)
        lastMessage = prefs.getString("last_message", "Đang khởi động...") ?: "Đang khởi động..."
        
        // Tạo notification channel
        createNotificationChannel()
        
        // Start foreground service
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
        
        // Nếu đã có wallet đang chạy, chỉ cần update callback
        if (kit != null && kit?.isRunning == true && kit?.wallet() != null) {
            setProgressCallback(progressCallback)
            return START_NOT_STICKY
        }
        
        if (seedPhrase.isNullOrEmpty()) return START_NOT_STICKY
        
        // Bắt đầu sync
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
     * Progress tracker theo dõi tiến độ đồng bộ realtime
     */
    inner class BitcoinDownloadProgressTracker : org.bitcoinj.utils.DownloadProgressTracker() {
        override fun progress(pct: Double, blocksSoFar: Int, date: Date, speedKbps: Double) {
            val percent = (pct * 100).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            
            // Giới hạn tần suất cập nhật UI (mỗi 1 giây)
            if (percent != lastPercent || now - lastUpdateTime > 1000) {
                lastPercent = percent
                lastUpdateTime = now
                
                val status = when {
                    percent >= 100 -> "Đã đồng bộ blockchain"
                    percent >= 99 -> "Hoàn thiện đồng bộ..."
                    else -> "Đồng bộ blockchain: ${percent}%"
                }
                saveProgress(percent, status)
            }
        }
        
        override fun doneDownload() {
            saveProgress(100, "Đã đồng bộ blockchain")
            isSynced = true
        }
        
        override fun startDownload(blocks: Int) {
            saveProgress(0, "Bắt đầu đồng bộ blockchain (${blocks} blocks)...")
        }
    }

    /**
     * Peer discovery chỉ lấy IPv4, bỏ qua IPv6 để tránh lỗi kết nối
     */
    class IPv4OnlyDiscovery(params: org.bitcoinj.core.NetworkParameters) : PeerDiscovery {
        private val dnsDiscovery = DnsDiscovery(params)
        
        override fun getPeers(services: Long, timeoutMillis: Long, maxConnections: Int): Array<PeerAddress> {
            return try {
                val allPeers = dnsDiscovery.getPeers(services, timeoutMillis, maxConnections)
                // Lọc chỉ giữ lại IPv4 (không chứa dấu ":")
                allPeers.filter { peerAddr ->
                    !peerAddr.addr.hostAddress.contains(":")
                }.toTypedArray()
            } catch (e: Exception) {
                emptyArray()
            }
        }
        
        override fun shutdown() {
            dnsDiscovery.shutdown()
        }
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            try {
                isSynced = false
                saveProgress(0, "Khởi tạo ví...")
                prefs.edit().putBoolean("is_synced", false).apply()
                
                // Khởi tạo BitcoinJ context
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))
                
                // Tạo thư mục lưu wallet
                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()
                
                // Dừng kit cũ nếu có
                kit?.let { old ->
                    try {
                        old.stopAsync()
                        old.awaitTerminated()
                    } catch (_: Exception) {}
                }
                kit = null
                isEngineRunning = false
                engineThread?.interrupt()
                
                // Tạo kit mới với cấu hình SPV
                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)  // QUAN TRỌNG: Cho phép callback hoạt động
                    setDownloadListener(BitcoinDownloadProgressTracker())
                    setAutoStop(false)  // Không tự động stop
                    setUserAgent("iBTC", "1.0")
                    setConnectTimeoutMillis(10000)  // 10s timeout
                }
                
                // Thay thế peer discovery bằng IPv4-only
                // newKit.setDiscovery(IPv4OnlyDiscovery(params))
                
                // Khôi phục wallet từ seed phrase
                val words = seedPhrase.trim().split(Regex("\\s+"))
                if (words.size !in listOf(12, 15, 18, 21, 24)) {
                    saveProgress(0, "Seed phrase không hợp lệ (cần 12-24 từ)")
                    return@Thread
                }
                
                val seed = DeterministicSeed(words, null, "", System.currentTimeMillis() / 1000)
                newKit.restoreWalletFromSeed(seed)
                
                // Start wallet
                saveProgress(0, "Đang kết nối mạng Bitcoin...")
                newKit.startAsync()
                newKit.awaitRunning()
                
                kit = newKit
                
                // Chạy engine giám sát
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
            var lastChainHeight = 0
            var sameHeightCount = 0
            
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
                    
                    // Kiểm tra xem có bị kẹt không
                    if (chainHeight > 0 && walletHeight == lastChainHeight && chainHeight == lastChainHeight) {
                        sameHeightCount++
                        if (sameHeightCount > 6) {  // Kẹt quá 30s
                            saveProgress(lastProgress, "Đang chờ mạng...")
                            sameHeightCount = 0
                        }
                    } else {
                        sameHeightCount = 0
                        lastChainHeight = chainHeight
                    }
                    
                    // Kiểm tra kết nối peer
                    val connectedPeers = peerGroup?.connectedPeers?.size ?: 0
                    if (connectedPeers == 0) {
                        saveProgress(lastProgress, "Đang tìm peer...")
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
    fun getTotalBlocks(): Int = try { kit?.chain()?.chainHead?.height ?: 0 } catch (_: Exception) { 0 }

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