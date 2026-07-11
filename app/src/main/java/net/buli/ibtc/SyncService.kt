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
    private var kit: WalletAppKit? = null
    private lateinit var prefs: SharedPreferences

    // Biến cho tiến độ chi tiết
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
    }

    private fun startBitcoinSync(walletId: String, seedPhrase: String) {
        Thread {
            try {
                isSynced = false
                prefs.edit().putBoolean("is_synced", false).apply()

                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))

                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                // ----- Xóa spvchain lỗi (kích thước < 1MB hoặc quá cũ) -----
                val spvFile = File(dir, "$walletId.spvchain")
                if (spvFile.exists()) {
                    val isTooSmall = spvFile.length() < 1024 * 1024
                    val isTooOld = System.currentTimeMillis() - spvFile.lastModified() > 3 * 24 * 3600 * 1000L
                    if (isTooSmall || isTooOld) {
                        try {
                            spvFile.delete()
                        } catch (_: Exception) {
                        }
                    }
                }

                try {
                    kit?.stopAsync()
                    kit?.awaitTerminated()
                } catch (_: Exception) {
                }
                kit = null

                val newKit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setAutoSave(true)
                    setAutoStop(true)
                    // FIX 97%: bỏ checkpoint cũ gây nhảy 97%
                    setCheckpoints(null)
                    val kitRef = this
                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            var p = pct.toInt()
                            if (p < 0) {
                                p = 0
                            }
                            if (p > 100) {
                                p = 100
                            }

                            this@SyncService.blocksSoFar = blocksSoFar
                            val chainHeight = kitRef.chain()?.chainHead?.height ?: 0
                            val peerGroup = kitRef.peerGroup()
                            val mostCommonHeight = peerGroup?.mostCommonChainHeight ?: chainHeight
                            totalBlocks = max(chainHeight, mostCommonHeight)

                            // FIX 97%: tính % thật theo block, không dùng pct theo ngày
                            val realPct = if (totalBlocks > 1000) {
                                (blocksSoFar * 100 / totalBlocks).coerceIn(0, 99)
                            } else {
                                p.coerceIn(0, 99)
                            }

                            val msg = "Đồng bộ: $blocksSoFar / $totalBlocks ($realPct%)"
                            saveProgress(realPct, msg)
                            updateNotification(lastMessage)
                            progressCallback?.invoke(lastProgress, lastMessage)
                        }

                        override fun doneDownload() {
                            Thread {
                                repeat(90) {
                                    try {
                                        val wallet = kitRef.wallet()
                                        val peerGroup = kitRef.peerGroup()
                                        val chainHead = kitRef.chain()?.chainHead?.height ?: 0
                                        val walletHeight = wallet?.lastBlockSeenHeight ?: 0
                                        if (chainHead > 0 && walletHeight >= (chainHead - 1)) {
                                            isSynced = true
                                            prefs.edit().putBoolean("is_synced", true).apply()
                                            saveProgress(100, "Đã đồng bộ blockchain")
                                            updateNotification(lastMessage)
                                            progressCallback?.invoke(lastProgress, lastMessage)
                                            return@Thread
                                        }
                                        Thread.sleep(1000)
                                    } catch (_: Exception) {
                                    }
                                }
                                try {
                                    kitRef.peerGroup().downloadBlockChain()
                                } catch (_: Exception) {
                                }
                                saveProgress(98, "Đang hoàn thiện nốt 2% cuối...")
                                progressCallback?.invoke(lastProgress, lastMessage)
                            }.start()
                        }
                    })
                    startAsync()
                    awaitRunning()

                    //
