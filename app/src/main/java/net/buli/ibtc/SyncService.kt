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
import com.google.common.util.concurrent.Service as GuavaService
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.script.Script
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.util.Date

class SyncService : Service() {

    private val CHANNEL_ID = "bitcoin_sync_channel"
    private val NOTIFICATION_ID = 1
    private var progressCallback: ((Int, String) -> Unit)? = null
    private var currentWalletId: String = ""
    @Volatile private var isSynced = false
    private var lastProgress = 0
    private var lastMessage = "Đang khởi động..."
    private var walletAppKit: WalletAppKit? = null
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
        val seed = intent?.getStringExtra("seed_phrase")
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        if (walletAppKit?.isRunning == true) return START_NOT_STICKY
        startWalletAppKit(walletId, seed)
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

    // Y nguyên WalletApplication.java 0.16.3 bạn gửi
    private fun startWalletAppKit(walletId: String, seedPhrase: String?) {
        Thread {
            try {
                // this.applicationName = walletId
                // this.params = MainNetParams.get()
                // this.preferredOutputScriptType = Script.ScriptType.P2WPKH
                // this.walletFileName = applicationName.replaceAll(...) + "-" + params.getPaymentProtocolId()
                val applicationName = "iBTC"
                val params: NetworkParameters = MainNetParams.get()
                val preferredOutputScriptType = Script.ScriptType.P2WPKH
                val walletFileName = walletId + "-" + params.paymentProtocolId

                // File appDataDirectory = AppDataDirectory.get(applicationName).toFile();
                val appDataDirectory = File(filesDir, "spv_wallets").apply { if (!exists()) mkdirs() }

                // setupWalletKit(null) - y nguyên
                val seed: DeterministicSeed? = if (!seedPhrase.isNullOrBlank()) DeterministicSeed(seedPhrase, null, "", 0L) else null

                Threading.USER_THREAD = Threading.SAME_THREAD

                walletAppKit = object : WalletAppKit(params, preferredOutputScriptType, null, appDataDirectory, walletFileName) {
                    override fun onSetupCompleted() {
                        // Platform.runLater(controller::onBitcoinSetup); - gốc chỉ init UI, không set synced
                        saveProgress(lastProgress, "Đã khởi tạo ví, đang kết nối...")
                    }
                }

                // if (params == RegTestParams.get()) walletAppKit.connectToLocalHost();
                if (params === RegTestParams.get()) {
                    walletAppKit!!.connectToLocalHost()
                }

                // y nguyên: setDownloadListener(controller.progressBarUpdater()).setBlockingStartup(false).setUserAgent(applicationName, "1.0");
                val downloadListener = object : DownloadProgressTracker() {
                    override fun progress(pct: Double, blocksSoFar: Int, date: Date?) {
                        super.progress(pct, blocksSoFar, date)
                        val p = pct.toInt().coerceIn(0, 99)
                        saveProgress(p, "Đồng bộ blockchain: $p%")
                        updateNotification(lastMessage)
                    }
                    override fun doneDownload() {
                        super.doneDownload()
                        isSynced = true
                        saveProgress(100, "Đã đồng bộ blockchain")
                        updateNotification(lastMessage)
                    }
                }

                walletAppKit!!.setDownloadListener(downloadListener)
                    .setBlockingStartup(false)
                    .setUserAgent(applicationName, "1.0")

                // if (seed != null) walletAppKit.restoreWalletFromSeed(seed);
                if (seed != null) walletAppKit!!.restoreWalletFromSeed(seed)

                // if (walletAppKit.isChainFileLocked()) { informationalAlert...; return; }
                if (walletAppKit!!.isChainFileLocked) {
                    saveProgress(lastProgress, "Already running")
                    return@Thread
                }

                // walletAppKit.addListener(... failed -> crashAlert ...)
                walletAppKit!!.addListener(object : GuavaService.Listener() {
                    override fun failed(from: GuavaService.State, failure: Throwable) {
                        saveProgress(lastProgress, "Lỗi sync: ${failure.message}")
                    }
                }, Threading.SAME_THREAD)

                // walletAppKit.startAsync();
                walletAppKit!!.startAsync()

            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    // y nguyên stop()
    override fun onDestroy() {
        try { walletAppKit?.stopAsync(); walletAppKit?.awaitTerminated() } catch (_: Exception) {}
        super.onDestroy()
    }

    fun setProgressCallback(cb: ((Int, String) -> Unit)?) { progressCallback = cb; cb?.invoke(lastProgress, lastMessage) }
    fun refreshProgress() { progressCallback?.invoke(lastProgress, lastMessage) }
    fun getWallet(): Wallet? = try { walletAppKit?.wallet() } catch (_: Exception) { null }
    fun getPeerGroup() = try { walletAppKit?.peerGroup() } catch (_: Exception) { null }
    fun getWalletId() = currentWalletId
    fun isWalletSynced() = isSynced
    override fun onBind(i: Intent?): IBinder? = null
}
