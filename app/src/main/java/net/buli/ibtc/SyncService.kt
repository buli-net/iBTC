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
import org.bitcoinj.net.discovery.DnsDiscovery
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

    // Engine state
    @Volatile private var isEngineRunning = false
    private var engineThread: Thread? = null
    private var lastBlockHeight = 0
    private var lastProgressTime = 0L
    private var lastRecoveryTime = 0L

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

    // Kiểm tra freeze: nếu block height không đổi trong hơn 60 giây
    private fun isFrozen(currentHeight: Int): Boolean {
        val now = System.currentTimeMillis()
        val stuck = currentHeight == lastBlockHeight
        val timeout = (now - lastProgressTime) > 60000
        lastBlockHeight = currentHeight
        lastProgressTime = now
        return stuck && timeout
    }

    // Xoay vòng peer: nếu số lượng peer < 2, reset peer discovery
    private fun rotatePeers(kitRef: WalletAppKit) {
        try {
            val peerGroup = kitRef.peerGroup() ?: return
            if (peerGroup.connectedPeers.size < 2) {
                peerGroup.stopAsync()
                Thread.sleep(2000)
                peerGroup.addPeerDiscovery(DnsDiscovery(MainNetParams.get()))
                peerGroup.startAsync()
                saveProgress(lastProgress, "Đã xoay peer, số lượng peer mới: ${peerGroup.connectedPeers.size}")
            }
        } catch (e: Exception) {
            saveProgress(lastProgress, "Lỗi rotate peer: ${e.message}")
        }
    }

    // Smart recovery: chỉ khởi động lại peer nếu thực sự cần (cooldown 60s)
    private fun smartRecovery(kitRef: WalletAppKit) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryTime < 60000) return // tránh recovery liên tục

        val peerGroup = kitRef.peerGroup() ?: return
        val peers = peerGroup.connectedPeers.size
        val wallet = kitRef.wallet() ?: return
        val walletHeight = wallet.lastBlockSeenHeight
        val frozen = isFrozen(walletHeight)

        if (peers == 0 || frozen) {
            lastRecoveryTime = now
            try {
                peerGroup.stopAsync()
                Thread.sleep(3000)
                peerGroup.addPeerDiscovery(DnsDiscovery(MainNetParams.get()))
                peerGroup.startAsync()
                saveProgress(lastProgress, "Auto recovery: đã khôi phục mạng peer")
            } catch (e: Exception) {
                saveProgress(lastProgress, "Auto recovery lỗi: ${e.message}")
            }
        }
    }

    // Engine chính: một vòng lặp duy nhất quản lý tất cả
    private fun startEngine(kitRef: WalletAppKit) {
        if (isEngineRunning) return
        isEngineRunning = true
        engineThread = Thread {
            while (isEngineRunning) {
                try {
                    val wallet = kitRef.wallet() ?: run {
                        Thread.sleep(2000)
                        continue
                    }
                    val peerGroup = kitRef.peerGroup()
                    val walletHeight = wallet.lastBlockSeenHeight

                    // 1. Detect freeze
                    if (isFrozen(walletHeight)) {
                        smartRecovery(kitRef)
                    }

                    // 2. Peer health check
                    if ((peerGroup?.connectedPeers?.size ?: 0) < 2) {
                        rotatePeers(kitRef)
                    }

                    // 3. Tính toán trạng thái và progress chân thực
                    val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
                    val state = when {
                        walletHeight >= chainHeight - 1 -> "SYNCED"
                        chainHeight == 0 -> "CONNECTING"
                        else -> "SYNCING"
                    }

                    val percent = if (state == "SYNCED") {
                        if (!isSynced) {
                            isSynced = true
                            prefs.edit().putBoolean("is_synced", true).apply()
                        }
                        100
                    } else {
                        (walletHeight.toDouble() / max(1, chainHeight) * 100).toInt().coerceIn(1, 99)
                    }

                    val statusText = when (state) {
                        "SYNCED" -> "Đã đồng bộ blockchain"
                        "CONNECTING" -> "Đang kết nối mạng..."
                        else -> "Đồng bộ blockchain: $percent%"
                    }
                    saveProgress(percent, statusText)

                    Thread.sleep(1000)
                } catch (e: Exception) {
                    saveProgress(lastProgress, "Engine lỗi: ${e.message}")
                    Thread.sleep(5000)
                }
            }
        }.apply { start() }
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            try {
                isSynced = false
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
                isEngineRunning = false
                engineThread?.interrupt()

                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    // Không cần download listener phức tạp, engine sẽ tính toán
                    startAsync()
                    awaitRunning()
                }
                kit = newKit

                // Khởi động engine sau khi kit đã chạy
                startEngine(newKit)

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

    // Các hàm cũ giữ để tương thích (có thể không dùng nữa nhưng vẫn có)
    fun getBlocksSoFar(): Int = kit?.wallet()?.lastBlockSeenHeight ?: 0
    fun getTotalBlocks(): Int = kit?.chain()?.chainHead?.height ?: 0

    override fun onDestroy() {
        super.onDestroy()
        isEngineRunning = false
        engineThread?.interrupt()
        try {
            kit?.stopAsync()
            kit?.awaitTerminated()
        } catch (_: Exception) {}
        kit = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}