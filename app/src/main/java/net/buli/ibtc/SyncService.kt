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

    // Freeze detection (thread-safe)
    @Volatile private var lastStableHeight = 0
    @Volatile private var lastStableTime = 0L

    // Recovery cooldown
    @Volatile private var lastRecoveryTime = 0L
    @Volatile private var isRecovering = false

    // Peer discovery deduplication
    private val discoverySet = mutableSetOf<String>()

    // UI throttling
    @Volatile private var lastUiUpdate = 0L

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

    // =============== FREEZE DETECTION (THREAD-SAFE) ===============
    private fun isFrozen(height: Int): Boolean {
        val now = System.currentTimeMillis()
        if (height > lastStableHeight) {
            lastStableHeight = height
            lastStableTime = now
            return false
        }
        return (now - lastStableTime) > 60000
    }

    // =============== PEER ROTATION (NO RESTART, NO DUPLICATE) ===============
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

    // =============== SMART RECOVERY (WITH CONCURRENCY LOCK) ===============
    private fun smartRecovery(kitRef: WalletAppKit) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryTime < 60000) return
        if (isRecovering) return
        isRecovering = true
        try {
            val peerGroup = kitRef.peerGroup() ?: return
            val wallet = kitRef.wallet() ?: return
            val peers = peerGroup.connectedPeers.size
            val walletHeight = wallet.lastBlockSeenHeight
            val frozen = isFrozen(walletHeight)

            if (peers == 0 || frozen) {
                lastRecoveryTime = now
                peerGroup.stopAsync()
                Thread.sleep(3000)
                peerGroup.addPeerDiscovery(DnsDiscovery(MainNetParams.get()))
                peerGroup.startAsync()
                saveProgress(lastProgress, "Auto recovery: network restored")
            }
        } catch (e: Exception) {
            saveProgress(lastProgress, "Auto recovery error: ${e.message}")
        } finally {
            isRecovering = false
        }
    }

    // =============== PROGRESS CALCULATION (NO 95-99 FAKE) ===============
    private fun calculateProgress(walletHeight: Int, chainHeight: Int): Int {
        if (chainHeight <= 0) return 0
        if (walletHeight >= chainHeight - 1) return 100
        val raw = (walletHeight.toDouble() / chainHeight.toDouble()) * 100.0
        // Không bao giờ vượt quá 94 cho đến khi thực sự synced
        return if (raw > 94) 94 else raw.toInt()
    }

    // =============== UI THROTTLE ===============
    private fun shouldUpdateUi(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 2500) return false
        lastUiUpdate = now
        return true
    }

    // =============== ENGINE LOOP (ADAPTIVE SLEEP + THROTTLED UI) ===============
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
                    val chainHeight = kitRef.chain()?.chainHead?.height ?: 0

                    // Freeze detection & recovery
                    if (isFrozen(walletHeight)) {
                        smartRecovery(kitRef)
                    }

                    // Peer health (only add if needed)
                    if ((peerGroup?.connectedPeers?.size ?: 0) < 2) {
                        rotatePeers(kitRef)
                    }

                    // Compute state and progress
                    val state = when {
                        walletHeight >= chainHeight - 1 -> "SYNCED"
                        chainHeight == 0 -> "CONNECTING"
                        else -> "SYNCING"
                    }

                    val percent = calculateProgress(walletHeight, chainHeight)

                    if (state == "SYNCED" && !isSynced) {
                        isSynced = true
                        prefs.edit().putBoolean("is_synced", true).apply()
                    }

                    val statusText = when (state) {
                        "SYNCED" -> "Đã đồng bộ blockchain"
                        "CONNECTING" -> "Đang kết nối mạng..."
                        else -> "Đồng bộ blockchain: $percent%"
                    }

                    // Update UI only when needed (throttle)
                    if (shouldUpdateUi()) {
                        saveProgress(percent, statusText)
                    }

                    // Adaptive sleep
                    val peerCount = peerGroup?.connectedPeers?.size ?: 0
                    val sleepTime = when {
                        peerCount == 0 -> 2000L
                        state == "SYNCED" -> 8000L
                        else -> 5000L
                    }
                    Thread.sleep(sleepTime)

                } catch (e: Exception) {
                    saveProgress(lastProgress, "Engine error: ${e.message}")
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
                    startAsync()
                    awaitRunning()
                }
                kit = newKit

                // Khởi tạo discovery set (dns flag)
                discoverySet.clear()
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