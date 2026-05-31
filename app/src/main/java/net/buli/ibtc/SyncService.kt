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
import kotlin.math.max

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

    private var blocksSoFar = 0
    private var totalBlocks = 0

    // Watchdog & sync loop control
    @Volatile private var isWatchdogRunning = false
    @Volatile private var isSyncLoopRunning = true
    @Volatile private var syncCompleted = false   // tránh tạo nhiều syncLoopThread
    private var watchdogThread: Thread? = null
    private var syncLoopThread: Thread? = null
    private var lastWalletHeight = 0
    private var lastChainHeight = 0   // theo dõi chain height để phát hiện stuck
    private var lastUpdateTime = 0L

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
        val seedPhrase = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId

        if (kit != null && kit?.isRunning == true && kit?.wallet() != null) {
            setProgressCallback(progressCallback)
            return START_NOT_STICKY
        }

        if (seedPhrase.isNullOrEmpty()) {
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
        progressCallback?.invoke(progress, message)
        updateNotification(message)
    }

    private fun isReallySynced(wallet: Wallet, kitRef: WalletAppKit): Boolean {
        val walletHeight = wallet.lastBlockSeenHeight
        val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
        return walletHeight >= chainHeight - 1 && walletHeight > 0
    }

    // Watchdog cải tiến: phát hiện stuck dựa trên chain height và wallet height
    private fun startWatchdog(kitRef: WalletAppKit) {
        if (isWatchdogRunning) return
        isWatchdogRunning = true

        watchdogThread = Thread {
            try {
                val wallet = kitRef.wallet() ?: return@Thread
                while (isWatchdogRunning) {
                    Thread.sleep(15000)

                    val peerGroup = kitRef.peerGroup()
                    val walletHeight = wallet.lastBlockSeenHeight
                    val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
                    val peers = peerGroup?.connectedPeers?.size ?: 0
                    val timeNow = System.currentTimeMillis()

                    val chainStuck = (chainHeight == lastChainHeight) && chainHeight > 0
                    val walletStuck = (walletHeight == lastWalletHeight) && walletHeight > 0
                    val stuck = chainStuck || walletStuck

                    val stuckTime = (timeNow - lastUpdateTime) > 30000
                    val inDangerZone = lastProgress in 95..99

                    if (inDangerZone && (peers == 0 || stuck || stuckTime)) {
                        saveProgress(lastProgress, "Mất kết nối peer hoặc đồng bộ đứng, đang khôi phục...")
                        reconnectPeers(kitRef)
                        lastUpdateTime = timeNow
                    }

                    if (inDangerZone && peers < 2) {
                        saveProgress(lastProgress, "Số lượng peer thấp (<2), khởi động lại peer...")
                        reconnectPeers(kitRef)
                    }

                    lastWalletHeight = walletHeight
                    lastChainHeight = chainHeight
                    lastUpdateTime = timeNow
                }
            } catch (_: Exception) { }
        }.apply { start() }
    }

    // Reconnect mạnh: clear discovery cache, restart, ping peers
    private fun reconnectPeers(kitRef: WalletAppKit) {
        try {
            val peerGroup = kitRef.peerGroup() ?: return
            // 1. stop hoàn toàn
            peerGroup.stopAsync()
            Thread.sleep(2000)

            // 2. clear peer discovery cache nếu có
            try {
                peerGroup.peerDiscovery?.shutdown()
            } catch (_: Exception) {}

            // 3. restart sạch
            peerGroup.startAsync()
            Thread.sleep(2000)

            // 4. force ping lại các peer (cố gắng)
            try {
                peerGroup.peers.forEach { peer ->
                    try {
                        peer.ping().get()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            saveProgress(lastProgress, "Rebuild peer network OK")
        } catch (e: Exception) {
            saveProgress(lastProgress, "Reconnect failed: ${e.message}")
        }
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            try {
                isSynced = false
                isSyncLoopRunning = true
                syncCompleted = false
                prefs.edit().putBoolean("is_synced", false).apply()

                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                // Dừng kit cũ an toàn
                kit?.let { oldKit ->
                    try {
                        oldKit.stopAsync()
                        oldKit.awaitTerminated()
                    } catch (_: Exception) { }
                }
                kit = null

                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    val kitRef = this

                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            var p = pct.toInt()
                            if (p < 0) p = 0
                            if (p > 100) p = 100
                            val msg = if (p < 95) "Đồng bộ blockchain: $p%" else "Đang hoàn tất đồng bộ..."
                            saveProgress(p, msg)

                            this@SyncService.blocksSoFar = blocksSoFar
                            val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
                            val peerGroup = kitRef.peerGroup()
                            val mostCommonHeight = peerGroup?.mostCommonChainHeight ?: chainHeight
                            totalBlocks = max(chainHeight, mostCommonHeight)
                            if (totalBlocks == 0 && blocksSoFar > 0) {
                                totalBlocks = (blocksSoFar.toDouble() / (p / 100.0)).toInt()
                            }
                        }

                        override fun doneDownload() {
                            saveProgress(95, "Đã tải xong block, đang bắt kịp blockchain...")

                            val wallet = kitRef.wallet()
                            if (wallet == null) {
                                saveProgress(95, "Lỗi ví, thử lại sau...")
                                return
                            }

                            // Chỉ tạo sync loop nếu chưa có
                            if (syncCompleted) return
                            if (syncLoopThread?.isAlive == true) return

                            syncLoopThread = Thread {
                                var lastPercent = 95
                                while (isSyncLoopRunning && !syncCompleted) {
                                    if (isReallySynced(wallet, kitRef)) {
                                        isSynced = true
                                        syncCompleted = true
                                        prefs.edit().putBoolean("is_synced", true).apply()
                                        saveProgress(100, "Đã đồng bộ blockchain")
                                        break
                                    } else {
                                        val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
                                        val walletHeight = wallet.lastBlockSeenHeight
                                        // Logic percent mới: không dùng tuyến tính ở 95-99
                                        val percent = when {
                                            isReallySynced(wallet, kitRef) -> 100
                                            walletHeight > chainHeight - 50 -> 97  // gần đến đích
                                            else -> lastProgress.coerceIn(0, 94)   // giữ nguyên progress từ download listener
                                        }
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            saveProgress(percent, "Đồng bộ: $percent% (đang bắt kịp blockchain)")
                                        }
                                    }
                                    Thread.sleep(2000)
                                }
                            }.apply { start() }
                        }
                    })
                    startAsync()
                    awaitRunning()
                }
                kit = newKit

                // Khởi động watchdog
                startWatchdog(newKit)

                saveProgress(lastProgress, lastMessage)
            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.message}")
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

    fun getBlocksSoFar(): Int = blocksSoFar
    fun getTotalBlocks(): Int = totalBlocks

    override fun onDestroy() {
        super.onDestroy()
        isSyncLoopRunning = false
        isWatchdogRunning = false
        syncCompleted = true
        syncLoopThread?.interrupt()
        watchdogThread?.interrupt()
        try {
            kit?.stopAsync()
            kit?.awaitTerminated()
        } catch (_: Exception) {}
        kit = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}