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

    companion object { private var instance: SyncService? = null; fun getInstance() = instance }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        lastProgress = prefs.getInt("last_progress", 0)
        lastMessage = prefs.getString("last_message", "Đang khởi động...") ?: "Đang khởi động..."
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, buildNotification(lastMessage), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
    }

    override fun onStartCommand(intent: Intent?, f: Int, s: Int): Int {
        val seed = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        if (kit != null && kit?.isRunning == true) { setProgressCallback(progressCallback); return START_NOT_STICKY }
        if (seed.isNullOrEmpty()) return START_NOT_STICKY
        startBitcoinSync(walletId)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Đồng bộ Bitcoin", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    private fun buildNotification(m: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("iBTC Wallet").setContentText(m).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(pi).setOngoing(true).build()
    }
    private fun updateNotification(m: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(m)) }
    private fun saveProgress(p: Int, m: String) { lastProgress = p; lastMessage = m; prefs.edit().putInt("last_progress", p).putString("last_message", m).apply(); progressCallback?.invoke(p, m) }

    private fun startBitcoinSync(walletId: String) {
        Thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))
                val dir = File(filesDir, "spv_wallets").apply { if (!exists()) mkdirs() }

                try { kit?.stopAsync()?.awaitTerminated() } catch (e: Exception) {}
                kit = null
                isSynced = false

                val kit = WalletAppKit(params, dir, walletId).apply {
                    setBlockingStartup(false)
                    setAutoSave(true)
                    setCheckpoints(null) // sync từ block 1 để đọc hết lịch sử ví

                    setDownloadListener(object : DownloadProgressTracker() {
                        override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                            super.progress(pct, blocksSoFar, date)
                            // % thật, không fake
                            val chainHead = chain()?.chainHead?.height ?: 0
                            val mostCommon = peerGroup()?.mostCommonChainHeight ?: 0
                            val p = if (mostCommon > 0) {
                                // tính thật theo height, không coerce về 99
                                (chainHead * 100L / mostCommon).toInt()
                            } else {
                                pct.toInt()
                            }
                            val msg = "Đồng bộ: $chainHead / $mostCommon ($p%)"
                            saveProgress(p, msg)
                            updateNotification(msg)
                        }

                        override fun doneDownload() {
                            super.doneDownload()
                            // chỉ khi bitcoinj báo xong thật mới set 100
                            isSynced = true
                            prefs.edit().putBoolean("is_synced", true).apply()
                            val chainHead = chain()?.chainHead?.height ?: 0
                            val mostCommon = peerGroup()?.mostCommonChainHeight ?: chainHead
                            saveProgress(100, "Đã đồng bộ: $chainHead / $mostCommon (100%)")
                            updateNotification(lastMessage)
                        }
                    })
                }

                kit.startAsync()
                kit.awaitRunning()

                try {
                    val pg = kit.peerGroup()
                    pg?.setFastCatchupTimeSecs(0) // sync full, không cắt ngày
                    pg?.setDownloadTxDependencies(0)
                    pg?.setMaxConnections(12)
                    pg?.setStallThreshold(300, 10)
                } catch (e: Exception) {}

                this.kit = kit

            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.toString()}")
            }
        }.start()
    }

    fun refreshProgress() { progressCallback?.invoke(lastProgress, lastMessage) }
    fun setProgressCallback(cb: ((Int, String) -> Unit)?) { progressCallback = cb; cb?.invoke(lastProgress, lastMessage) }
    fun getWallet(): Wallet? = try { kit?.wallet() } catch (e: Exception) { null }
    fun getPeerGroup() = try { kit?.peerGroup() } catch (e: Exception) { null }
    fun getWalletId() = currentWalletId
    fun isWalletSynced() = isSynced
    fun getBlocksSoFar() = try { kit?.chain()?.chainHead?.height ?: 0 } catch (e: Exception) { 0 }
    fun getTotalBlocks() = try { kit?.peerGroup()?.mostCommonChainHeight ?: 0 } catch (e: Exception) { 0 }
    override fun onDestroy() { try { kit?.stopAsync()?.awaitTerminated() } catch (e: Exception) {}; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
