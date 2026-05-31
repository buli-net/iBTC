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
import org.bitcoinj.wallet.DeterministicSeed
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

    // Freeze detection (chỉ dùng khi chưa có dữ liệu)
    @Volatile private var lastPeerHeight = 0
    @Volatile private var lastWalletHeight = 0
    @Volatile private var lastActivityTime = 0L

    // Recovery cooldown
    @Volatile private var lastRecoveryTime = 0L
    @Volatile private var isRecovering = false

    // Peer discovery deduplication
    private val discoverySet = mutableSetOf<String>()

    // UI throttling with buffer
    @Volatile private var lastUiUpdate = 0L
    @Volatile private var lastSentPercent = -1

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

    private fun getBestChainHeight(kitRef: WalletAppKit): Int {
        return kitRef.peerGroup()?.mostCommonChainHeight
            ?: kitRef.chain()?.chainHead?.height
            ?: 0
    }

    // Chỉ phát hiện frozen khi chưa có dữ liệu
    private fun isFrozen(peerHeight: Int, walletHeight: Int, hasData: Boolean): Boolean {
        if (hasData) return false
        val now = System.currentTimeMillis()
        val activity = (peerHeight > lastPeerHeight) || (walletHeight > lastWalletHeight)
        if (activity) {
            lastPeerHeight = peerHeight
            lastWalletHeight = walletHeight
            lastActivityTime = now
            return false
        }
        return (now - lastActivityTime) > 60000
    }

    private fun rotatePeers(kitRef: WalletAppKit) {
        try {
            val peerGroup = kitRef.peerGroup() ?: return
            if (peerGroup.connectedPeers.size < 2) {
                val discovery = DnsDiscovery(MainNetParams.get())
                val key = "dns"
                if (!discoverySet.contains(key)) {
                    peerGroup.addPeerDiscovery(discovery)
                    discoverySet.add(key)
                    saveProgress(lastProgress, "Added peer discovery (safe mode)")
                }
            }
        } catch (e: Exception) {
            saveProgress(lastProgress, "Peer rotate error: ${e.message}")
        }
    }

    private fun smartRecovery(kitRef: WalletAppKit, hasData: Boolean) {
        if (hasData) return // không recovery khi đã có dữ liệu
        val now = System.currentTimeMillis()
        if (now - lastRecoveryTime < 60000) return
        if (isRecovering) return
        isRecovering = true
        try {
            val peerGroup = kitRef.peerGroup() ?: return
            val wallet = kitRef.wallet() ?: return
            val peers = peerGroup.connectedPeers.size
            val peerHeight = getBestChainHeight(kitRef)
            val walletHeight = wallet.lastBlockSeenHeight
            val frozen = isFrozen(peerHeight, walletHeight, hasData)

            if (peers == 0 || frozen) {
                lastRecoveryTime = now
                if (peers == 0) {
                    val discovery = DnsDiscovery(MainNetParams.get())
                    val key = "dns_recovery_${System.currentTimeMillis()}"
                    if (!discoverySet.contains(key)) {
                        peerGroup.addPeerDiscovery(discovery)
                        discoverySet.add(key)
                        saveProgress(lastProgress, "Recovery: added new peer discovery")
                    }
                }
                if (frozen) {
                    saveProgress(lastProgress, "Sync frozen, waiting for network...")
                }
            }
        } catch (e: Exception) {
            saveProgress(lastProgress, "Auto recovery error: ${e.message}")
        } finally {
            isRecovering = false
        }
    }

    private fun calculateProgress(walletHeight: Int, chainHeight: Int): Int {
        if (chainHeight <= 0) return 0
        if (walletHeight >= chainHeight - 1) return 100
        val raw = (walletHeight.toDouble() / chainHeight.toDouble()) * 100.0
        return if (raw > 94) 94 else raw.toInt()
    }

    private fun shouldUpdateUi(percent: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 2500) return false
        if (percent == lastSentPercent) return false
        lastUiUpdate = now
        lastSentPercent = percent
        return true
    }

    private fun startEngine(kitRef: WalletAppKit) {
        if (isEngineRunning) return
        isEngineRunning = true
        engineThread = Thread {
            while (isEngineRunning) {
                try {
                    val wallet = kitRef.wallet()
                    if (wallet == null) {
                        Thread.sleep(2000)
                        continue
                    }
                    val peerGroup = kitRef.peerGroup()
                    val walletHeight = wallet.lastBlockSeenHeight
                    val chainHeight = getBestChainHeight(kitRef)
                    val peerHeight = peerGroup?.mostCommonChainHeight ?: 0
                    val hasBalance = wallet.getBalance().value > 0
                    val hasTransactions = wallet.getTransactionsByTime().isNotEmpty()
                    val hasData = hasBalance || hasTransactions

                    // Freeze & recovery (chỉ khi chưa có dữ liệu)
                    if (isFrozen(peerHeight, walletHeight, hasData)) {
                        smartRecovery(kitRef, hasData)
                    }

                    if ((peerGroup?.connectedPeers?.size ?: 0) < 2) {
                        rotatePeers(kitRef)
                    }

                    // Xác định trạng thái: có dữ liệu thì coi như SYNCED
                    val state = when {
                        hasData -> "SYNCED"
                        walletHeight >= chainHeight - 1 && walletHeight > 0 -> "SYNCED"
                        chainHeight == 0 -> "CONNECTING"
                        else -> "SYNCING"
                    }

                    val percent = if (state == "SYNCED") 100 else calculateProgress(walletHeight, chainHeight)

                    if (state == "SYNCED" && !isSynced) {
                        isSynced = true
                        prefs.edit().putBoolean("is_synced", true).apply()
                        if (shouldUpdateUi(100)) {
                            saveProgress(100, "Đã đồng bộ blockchain")
                        }
                        try {
                            wallet.saveToFile(File(filesDir, "wallet_$currentWalletId"))
                        } catch (_: Exception) {}
                    } else if (state != "SYNCED") {
                        val statusText = when (state) {
                            "CONNECTING" -> "Đang kết nối mạng..."
                            else -> "Đồng bộ blockchain: $percent%"
                        }
                        if (shouldUpdateUi(percent)) {
                            saveProgress(percent, statusText)
                        }
                    } else {
                        // Đã có dữ liệu nhưng chưa báo sync, báo 100%
                        if (!isSynced && shouldUpdateUi(100)) {
                            isSynced = true
                            prefs.edit().putBoolean("is_synced", true).apply()
                            saveProgress(100, "Đã đồng bộ blockchain")
                        }
                    }

                    val peerCount = peerGroup?.connectedPeers?.size ?: 0
                    val sleepTime = when {
                        peerCount == 0 -> 2000L
                        state == "SYNCED" -> 8000L
                        else -> 5000L
                    }
                    Thread.sleep(sleepTime)

                } catch (e: Exception) {
                    e.printStackTrace()
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
                }

                val words = seedPhrase.trim().split(Regex("\\s+"))
                val seed = DeterministicSeed(words, null, "", System.currentTimeMillis() / 1000)
                newKit.restoreWalletFromSeed(seed)

                newKit.startAsync()
                newKit.awaitRunning()

                kit = newKit

                discoverySet.clear()
                lastPeerHeight = 0
                lastWalletHeight = 0
                lastActivityTime = System.currentTimeMillis()
                lastRecoveryTime = 0L
                lastUiUpdate = 0L
                lastSentPercent = -1

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

    fun getBlocksSoFar(): Int = kit?.wallet()?.lastBlockSeenHeight ?: 0
    fun getTotalBlocks(): Int {
        val k = kit ?: return 0
        return getBestChainHeight(k)
    }

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