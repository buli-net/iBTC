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
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, buildNotification(lastMessage), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIFICATION_ID, buildNotification(lastMessage))
    }

    override fun onStartCommand(intent: Intent?, f: Int, s: Int): Int {
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        if (kit?.isRunning == true) return START_NOT_STICKY
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
                val dir = File(filesDir, "spv_wallets")
                if (!dir.exists()) dir.mkdirs()

                val kit = WalletAppKit(params, dir, walletId)

                kit.setDownloadListener(object : DownloadProgressTracker() {
                    override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                        super.progress(pct, blocksSoFar, date)
                        saveProgress(pct.toInt(), "Đồng bộ blockchain: ${pct.toInt()}%")
                        updateNotification(lastMessage)
                    }
                    override fun doneDownload() {
                        super.doneDownload()
                        isSynced = true
                        saveProgress(100, "Đã đồng bộ blockchain")
                        updateNotification(lastMessage)
                    }
                })

                kit.setBlockingStartup(false)
                kit.startAsync()
                kit.awaitRunning()

                this.kit = kit

            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    fun setProgressCallback(cb: ((Int, String) -> Unit)?) { progressCallback = cb; cb?.invoke(lastProgress, lastMessage) }
    fun refreshProgress() { progressCallback?.invoke(lastProgress, lastMessage) }
    fun getWallet(): Wallet? = try { kit?.wallet() } catch (e: Exception) { null }
    fun getPeerGroup() = try { kit?.peerGroup() } catch (e: Exception) { null }
    fun getWalletId() = currentWalletId
    fun isWalletSynced() = isSynced
    fun getBlocksSoFar() = try { kit?.chain()?.chainHead?.height ?: 0 } catch (e: Exception) { 0 }
    fun getTotalBlocks() = try { kit?.peerGroup()?.mostCommonChainHeight ?: 0 } catch (e: Exception) { 0 }
    override fun onDestroy() { try { kit?.stopAsync()?.awaitTerminated() } catch (e: Exception) {}; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
