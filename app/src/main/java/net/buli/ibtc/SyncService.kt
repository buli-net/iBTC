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
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.Context as BtcContext
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.SPVBlockStore
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
    private lateinit var prefs: SharedPreferences

    private var peerGroup: PeerGroup? = null
    private var blockChain: BlockChain? = null
    private var blockStore: SPVBlockStore? = null
    private var wallet: Wallet? = null

    companion object { private var instance: SyncService? = null; fun getInstance() = instance }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Đang khởi động..."), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    override fun onStartCommand(intent: Intent?, f: Int, s: Int): Int {
        val walletId = intent?.getStringExtra("wallet_id") ?: "default_wallet"
        currentWalletId = walletId
        startSchildbachSync(walletId)
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

    // Logic y hệt Schildbach wallet: https://github.com/bitcoin-wallet/bitcoin-wallet/blob/master/wallet/src/de/schildbach/wallet/service/BlockchainService.java
    private fun startSchildbachSync(walletId: String) {
        Thread {
            try {
                val params = MainNetParams.get()
                BtcContext.propagate(BtcContext(params))
                val dir = File(filesDir, "spv_wallets").apply { if (!exists()) mkdirs() }

                // 1. Load wallet như Schildbach
                val walletFile = File(dir, "$walletId.wallet")
                wallet = if (walletFile.exists()) Wallet.loadFromFile(walletFile) else Wallet.createDeterministic(params, org.bitcoinj.script.Script.ScriptType.P2WPKH)

                // 2. SPVBlockStore như Schildbach
                val chainFile = File(dir, "$walletId.spvchain")
                blockStore = SPVBlockStore(params, chainFile)
                blockChain = BlockChain(params, wallet, blockStore)

                // 3. PeerGroup như Schildbach - đây là chỗ fix 97%
                val pg = PeerGroup(params, blockChain)
                pg.addWallet(wallet)
                pg.setDownloadTxDependencies(0) // Schildbach: không tải tx phụ thuộc
                pg.setFastCatchupTimeSecs(wallet!!.earliestKeyCreationTime) // Schildbach: fast catchup theo key time
                pg.addPeerDiscovery(DnsDiscovery(params))
                pg.maxConnections = 10
                pg.setStallThreshold(120, 5)
                pg.setConnectTimeoutMillis(15000)

                pg.addDownloadProgressListener(object : DownloadProgressTracker() {
                    override fun progress(pct: Double, blocksSoFar: Int, date: java.util.Date?) {
                        val chainHead = blockChain?.chainHead?.height ?: 0
                        val mostCommon = pg.mostCommonChainHeight
                        val left = if (mostCommon > chainHead) mostCommon - chainHead else 0
                        // Schildbach không dùng pct thời gian, nó hiện blocks left
                        val p = if (mostCommon > 0) (chainHead * 100 / mostCommon).coerceIn(0, 99) else pct.toInt().coerceIn(0, 99)
                        val msg = if (left > 0) "Đang khai thác block #${mostCommon} - còn $left block" else "Đồng bộ blockchain: $p%"
                        saveProgress(p, msg); updateNotification(msg)
                    }
                    override fun doneDownload() {
                        isSynced = true
                        saveProgress(100, "Đã đồng bộ blockchain")
                        updateNotification(lastMessage)
                    }
                })

                peerGroup = pg
                pg.startAsync()
                pg.awaitRunning()
                pg.downloadBlockChain()

            } catch (e: Exception) {
                saveProgress(lastProgress, "Lỗi sync: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    fun setProgressCallback(cb: ((Int, String) -> Unit)?) { progressCallback = cb; cb?.invoke(lastProgress, lastMessage) }
    fun refreshProgress() { progressCallback?.invoke(lastProgress, lastMessage) }
    fun getWallet() = wallet
    fun getPeerGroup() = peerGroup
    fun getWalletId() = currentWalletId
    fun isWalletSynced() = isSynced
    fun getBlocksSoFar() = blockChain?.chainHead?.height ?: 0
    fun getTotalBlocks() = peerGroup?.mostCommonChainHeight ?: 0
    override fun onDestroy() { try { peerGroup?.stopAsync()?.awaitTerminated(); blockStore?.close() } catch (e: Exception) {}; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
