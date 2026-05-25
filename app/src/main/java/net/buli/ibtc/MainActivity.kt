package net.buli.ibtc

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var walletManager: WalletManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastInteractionTime = System.currentTimeMillis()
    private val AUTO_LOCK_MS = 120_000L
    private val POOL_FONT = 13f

    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var balanceText: TextView
    private lateinit var priceText: TextView
    private lateinit var addressText: TextView
    private lateinit var syncText: TextView
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var blockText: TextView
    private lateinit var blockProgressBar: ProgressBar
    private lateinit var txListView: ListView
    private lateinit var walletNameText: TextView
    private lateinit var statsContainer: LinearLayout
    private val statBars = mutableMapOf<String, ProgressBar>()
    private val statTexts = mutableMapOf<String, TextView>()
    private var isSyncing = false
    private var autoSyncStarted = false
    private var pendingAddressInput: EditText? = null
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { addr ->
            pendingAddressInput?.setText(addr)
            toast("ÄĂ£ quĂ©t: ${addr.take(10)}...")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        walletManager = WalletManager(this)
        setupRootLayout()
        setContentView(scrollView)
        startAutoLockChecker()
        if (walletManager.hasWallets()) showUnlockDialog() else showWelcome()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        lastInteractionTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        lastInteractionTime = System.currentTimeMillis()
        if (walletManager.getActive()!= null) refreshWallet()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            walletManager.lock()
            walletManager.stop()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setupRootLayout() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(rootLayout)
        }
    }

    private fun startAutoLockChecker() {
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                val timeout = prefs.getInt("auto_lock_minutes", 5)
                if (timeout > 0 && walletManager.isUnlocked() && System.currentTimeMillis() - lastActive > timeout * 60 * 1000) {
                    walletManager.lock()
                    runOnUiThread { showUnlockDialog() }
                }
                handler.postDelayed(this, 30000)
            }
        }
        handler.postDelayed(runnable, 30000)
    }

    private fun startAutoPriceSync() {
        if (autoSyncStarted) return
        autoSyncStarted = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (walletManager.getActive()!= null &&!isSyncing) {
                    refreshWallet()
                }
                handler.postDelayed(this, 45000)
            }
        }, 45000)
    }

    private fun fetchBlockUpdate() {
        Thread {
            try {
                val json = URL("https://mempool.space/api/v1/blocks").openStream().bufferedReader().readText()
                val height = Regex(""""height":(\d+)""").find(json)?.groupValues?.get(1)?.toInt()?: 0
                val lastTime = Regex(""""timestamp":(\d+)""").find(json)?.groupValues?.get(1)?.toLong()?: 0L
                val nextHeight = height + 1
                val elapsed = (System.currentTimeMillis()/1000 - lastTime).coerceAtLeast(0)
                val percent = ((elapsed * 100) / 600).toInt()
                val remain = 600 - elapsed
                runOnUiThread {
                    blockProgressBar.progress = percent.coerceAtMost(100)
                    if (remain >= 0) {
                        val mins = remain / 60
                        val secs = remain % 60
                        blockText.text = "Äang khai thĂ¡c block #$nextHeight â€” $percent% (~${mins}m${String.format("%02d", secs)}s)"
                    } else {
                        val over = -remain
                        val mins = over / 60
                        val secs = over % 60
                        blockText.text = "Block #$nextHeight Ä‘Ă£ quĂ¡ háº¡n +${mins}m${String.format("%02d", secs)}s ($percent%)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    blockText.text = "Lá»—i pool - tá»± thá»­ láº¡i"
                    blockProgressBar.progress = 0
                }
            }
        }.start()
    }

    
    private fun fetchBtcPriceUsd(callback: (Double) -> Unit) {
        Thread {
            try {
                val json = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd").readText()
                val price = Regex(""""usd":([\d.]+)""").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 60000.0
                runOnUiThread { callback(price) }
            } catch (_: Exception) {
                runOnUiThread { callback(60000.0) }
            }
        }.start()
    }

private fun fetchBtcStats() {
        Thread {
            try {
                val height = URL("https://mempool.space/api/blocks/tip/height").readText().trim().toInt()
                val halvings = height / 210000
                val reward = 50.0 / Math.pow(2.0, halvings.toDouble())
                val nextHalving = (halvings + 1) * 210000
                val blocksToHalving = nextHalving - height
                val totalSats = URL("https://blockchain.info/q/totalbc").readText().trim().toLong()
                val totalMined = totalSats / 100000000.0
                val diffJson = URL("https://mempool.space/api/v1/difficulty-adjustment").readText()
                val diffProgress = Regex(""""progressPercent":([\d.]+)""").find(diffJson)?.groupValues?.get(1)?.toFloat()?: 0f
                val mempoolJson = URL("https://mempool.space/api/mempool").readText()
                val mempoolCount = Regex(""""count":(\d+)""").find(mempoolJson)?.groupValues?.get(1)?.toInt()?: 0
                val feesJson = URL("https://mempool.space/api/v1/fees/recommended").readText()
                val feeFast = Regex(""""fastestFee":(\d+)""").find(feesJson)?.groupValues?.get(1)?.toInt()?: 0
                val hashJson = URL("https://mempool.space/api/v1/mining/hashrate/1w").readText()
                val currentHash = Regex(""""currentHashrate":([\d.]+)""").find(hashJson)?.groupValues?.get(1)?.toDouble()?: 0.0
                runOnUiThread {
                    val minedPct = ((totalMined / 21000000.0) * 100).toInt()
                    statBars["mined"]?.progress = minedPct
                    statTexts["mined"]?.text = "ÄĂ£ khai thĂ¡c: ${String.format("%.2f", totalMined)} / 21M BTC ($minedPct%)"
                    val halvingPct = ((1 - blocksToHalving / 210000.0) * 100).toInt()
                    statBars["halving"]?.progress = halvingPct
                    statTexts["halving"]?.text = "Halving #${halvings+1}: cĂ²n $blocksToHalving blocks (~${blocksToHalving/144} ngĂ y)"
                    val rewardPct = ((reward / 50.0) * 100).toInt()
                    statBars["reward"]?.progress = rewardPct
                    statTexts["reward"]?.text = "ThÆ°á»Ÿng block: $reward BTC (ban Ä‘áº§u 50 BTC)"
                    statBars["diff"]?.progress = diffProgress.toInt()
                    statTexts["diff"]?.text = "Difficulty adj: ${String.format("%.1f", diffProgress)}%"
                    val mempoolPct = (mempoolCount / 300000.0 * 100).toInt().coerceAtMost(100)
                    statBars["mempool"]?.progress = mempoolPct
                    statTexts["mempool"]?.text = "Mempool: $mempoolCount tx chá»"
                    val hashEh = currentHash / 1e18
                    statBars["hash"]?.progress = 70
                    statTexts["hash"]?.text = "Hashrate: ${String.format("%.0f", hashEh)} EH/s"
                    statBars["fee"]?.progress = feeFast.coerceAtMost(100)
                    statTexts["fee"]?.text = "PhĂ­ nhanh: $feeFast sat/vB"
                    val blocksToday = height % 144
                    statBars["today"]?.progress = (blocksToday * 100 / 144)
                    statTexts["today"]?.text = "Block hĂ´m nay: $blocksToday / 144"
                    statBars["supply"]?.progress = minedPct
                    statTexts["supply"]?.text = "Cung lÆ°u thĂ´ng: ${String.format("%.2f", totalMined/1000000)}M BTC"
                    statBars["height"]?.progress = height % 100
                    statTexts["height"]?.text = "Block height: #$height"
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun startBlockProgress() {
        blockText.text = "Äang káº¿t ná»‘i mempool..."
        handler.post(object : Runnable {
            override fun run() {
                fetchBlockUpdate()
                handler.postDelayed(this, 2000)
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                fetchBtcStats()
                handler.postDelayed(this, 30000)
            }
        })
    }

    private fun addStat(key: String, label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = POOL_FONT
            setTextColor(Color.GRAY)
            setPadding(0,8,0,2)
            typeface = Typeface.DEFAULT
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            scaleY = 0.6f
        }
        statsContainer.addView(tv)
        statsContainer.addView(pb)
        statTexts[key] = tv
        statBars[key] = pb
    }

    private fun showWelcome() {
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isDark) Color.WHITE else Color.BLACK
        val logo = TextView(this).apply {
            text = "â‚¿"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F7931A"))
            setPadding(0, 80, 0, 20)
        }
        val title = TextView(this).apply {
            text = "iBTC Wallet v4.7"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(titleColor)
        }
        val subtitle = TextView(this).apply {
            text = "Bitcoin wallet an toĂ n, mĂ£ nguá»“n má»Ÿ"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 60)
        }
        val createBtn = Button(this).apply {
            text = "Táº¡o vĂ­ má»›i"
            textSize = 16f
            setPadding(0, 30, 0, 30)
        }
        val importBtn = Button(this).apply {
            text = "Import vĂ­ cĂ³ sáºµn"
            textSize = 16f
        }
        val space = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 40)
        }
        createBtn.setOnClickListener { showCreateDialog() }
        importBtn.setOnClickListener { showImportDialog() }
        rootLayout.addView(logo)
        rootLayout.addView(title)
        rootLayout.addView(subtitle)
        rootLayout.addView(createBtn)
        rootLayout.addView(space)
        rootLayout.addView(importBtn)
    }

    private fun showCreateDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val nameInput = EditText(this).apply {
            hint = "TĂªn vĂ­ (tĂ¹y chá»n)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passInput = EditText(this).apply {
            hint = "Máº­t kháº©u (tá»‘i thiá»ƒu 8 kĂ½ tá»±)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val pass2Input = EditText(this).apply {
            hint = "Nháº­p láº¡i máº­t kháº©u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val warning = TextView(this).apply {
            text = "â ï¸ LÆ°u máº­t kháº©u cáº©n tháº­n. Máº¥t = máº¥t vĂ­."
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(0, 20, 0, 0)
        }
        layout.addView(nameInput)
        layout.addView(passInput)
        layout.addView(pass2Input)
        layout.addView(warning)
        AlertDialog.Builder(this)
            .setTitle("Táº¡o vĂ­ Bitcoin má»›i")
            .setView(layout)
            .setPositiveButton("Táº¡o") { _, _ ->
                val name = nameInput.text.toString().trim()
                val p1 = passInput.text.toString()
                val p2 = pass2Input.text.toString()
                if (p1.length < 8) {
                    toast("Máº­t kháº©u pháº£i â‰¥8 kĂ½ tá»±")
                    return@setPositiveButton
                }
                if (p1!= p2) {
                    toast("Máº­t kháº©u khĂ´ng khá»›p")
                    return@setPositiveButton
                }
                try {
                    walletManager.create(name, p1)
                    Thread { walletManager.init() }.start()
                    toast("Táº¡o vĂ­ thĂ nh cĂ´ng")
                    showMainWallet()
                } catch (e: Exception) {
                    toast("Lá»—i: ${e.message}")
                }
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }

    private fun showImportDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val nameInput = EditText(this).apply { hint = "TĂªn vĂ­" }
        val seedInput = EditText(this).apply {
            hint = "12 hoáº·c 24 tá»« seed, cĂ¡ch nhau báº±ng space"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val passInput = EditText(this).apply {
            hint = "Äáº·t máº­t kháº©u má»›i â‰¥8 kĂ½ tá»±"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        layout.addView(nameInput)
        layout.addView(seedInput)
        layout.addView(passInput)
        AlertDialog.Builder(this)
            .setTitle("Import vĂ­")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val name = nameInput.text.toString().trim()
                val seed = seedInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (pass.length < 8) {
                    toast("Máº­t kháº©u quĂ¡ ngáº¯n")
                    return@setPositiveButton
                }
                val info = walletManager.import(name, seed, pass)
                if (info == null) {
                    toast("Seed khĂ´ng há»£p lá»‡ (cáº§n 12-24 tá»«)")
                } else {
                    Thread { walletManager.init() }.start()
                    toast("Import thĂ nh cĂ´ng")
                    showMainWallet()
                }
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }

    private fun showUnlockDialog() {
        val id = walletManager.getActiveId()
        if (id == null) {
            showWelcome()
            return
        }
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isDark) Color.WHITE else Color.BLACK
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40)
        }
        val title = TextView(this).apply {
            text = "đŸ”’ VĂ­ Ä‘Ă£ khĂ³a"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
            setTextColor(titleColor)
        }
        val passInput = EditText(this).apply {
            hint = "Nháº­p máº­t kháº©u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val unlockBtn = Button(this).apply {
            text = "Má»Ÿ khĂ³a"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        }
        unlockBtn.setOnClickListener {
            val pass = passInput.text.toString()
            if (walletManager.unlock(id, pass)) {
                Thread { walletManager.init() }.start()
                showMainWallet()
            } else {
                toast("Sai máº­t kháº©u (khĂ³a sau 5 láº§n)")
                passInput.text.clear()
            }
        }
        layout.addView(title)
        layout.addView(passInput)
        layout.addView(unlockBtn)
        rootLayout.addView(layout)
    }

    private fun showMainWallet() {
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val mainColor = if (isDark) Color.WHITE else Color.BLACK
        val subColor = if (isDark) Color.LTGRAY else Color.DKGRAY
        
        walletNameText = TextView(this).apply {
            text = walletManager.getActive()?.name?: "VĂ­"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mainColor)
        }
        balanceText = TextView(this).apply {
            text = "0.00000000 BTC"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mainColor)
            setPadding(0, 10, 0, 0)
        }
        priceText = TextView(this).apply {
            text = "â‰ˆ $0.00"
            textSize = 16f
            setTextColor(subColor)
        }
        syncText = TextView(this).apply {
            text = "ChÆ°a Ä‘á»“ng bá»™"
            textSize = 13f
            setTextColor(subColor)
        }
        syncProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        addressText = TextView(this).apply {
            textSize = 12f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(subColor)
            setPadding(0, 10, 0, 10)
        }
        blockText = TextView(this).apply {
            text = "Äang káº¿t ná»‘i mempool..."
            textSize = POOL_FONT
            setTextColor(subColor)
            setPadding(0,8,0,2)
            typeface = Typeface.DEFAULT
        }
        blockProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            scaleY = 0.7f
        }

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val btnReceive = Button(this).apply {
            text = "â¬‡ Nháº­n"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val btnSend = Button(this).apply {
            text = "â¬† Gá»­i"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val btnRefresh = Button(this).apply {
            text = "âŸ³ LĂ m má»›i"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val btnSettings = Button(this).apply {
            text = "â™ CĂ i Ä‘áº·t"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        btnRow1.addView(btnReceive)
        btnRow1.addView(btnSend)
        btnRow2.addView(btnRefresh)
        btnRow2.addView(btnSettings)

        statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 5, 0, 0)
        }
        val statsTitle = TextView(this).apply {
            text = "đŸ“ Thá»‘ng kĂª Bitcoin"
            textSize = POOL_FONT
            typeface = Typeface.DEFAULT
            setPadding(0, 20, 0, 5)
            setTextColor(mainColor)
        }
        val txTitle = TextView(this).apply {
            text = "Lá»‹ch sá»­ giao dá»‹ch"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 30, 0, 10)
            setTextColor(mainColor)
        }
        txListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        }

        rootLayout.addView(walletNameText)
        rootLayout.addView(balanceText)
        rootLayout.addView(priceText)
        rootLayout.addView(syncText)
        rootLayout.addView(syncProgressBar)
        rootLayout.addView(addressText)
        rootLayout.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        rootLayout.addView(btnRow1)
        rootLayout.addView(btnRow2)
        rootLayout.addView(statsTitle)
        rootLayout.addView(blockText)
        rootLayout.addView(blockProgressBar)
        rootLayout.addView(statsContainer)
        rootLayout.addView(txTitle)
        rootLayout.addView(txListView)

        addStat("mined", "ÄĂ£ khai thĂ¡c")
        addStat("halving", "Halving")
        addStat("reward", "Pháº§n thÆ°á»Ÿng")
        addStat("diff", "Difficulty")
        addStat("mempool", "Mempool")
        addStat("hash", "Hashrate")
        addStat("fee", "PhĂ­")
        addStat("today", "HĂ´m nay")
        addStat("supply", "Cung")
        addStat("height", "Height")

        btnReceive.setOnClickListener { showReceiveDialog() }
        btnSend.setOnClickListener { showSendDialog() }
        btnRefresh.setOnClickListener {
            refreshWallet()
            fetchBlockUpdate()
            fetchBtcStats()
            toast("Äang lĂ m má»›i táº¥t cáº£...")
        }
        btnSettings.setOnClickListener { showSettings() }

        walletManager.onProgress { pct, txt ->
            runOnUiThread {
                syncText.text = txt
                syncProgressBar.progress = pct
            }
        }
        refreshWallet()
        startAutoPriceSync()
        startBlockProgress()
    }

    private fun refreshWallet() {
        if (isSyncing) return
        isSyncing = true
        runOnUiThread {
            syncText.text = "Äang káº¿t ná»‘i API..."
            syncProgressBar.progress = 10
        }
        Thread {
            try {
                runOnUiThread { syncProgressBar.progress = 30 }
                val bal = walletManager.getBalance()
                runOnUiThread {
                    syncText.text = "Äang táº£i giĂ¡ BTC..."
                    syncProgressBar.progress = 60
                }
                val price = walletManager.price()
                runOnUiThread {
                    syncText.text = "Äang cáº­p nháº­t Ä‘á»‹a chá»‰..."
                    syncProgressBar.progress = 85
                }
                val addr = walletManager.getAddress()
                val txs = walletManager.getTransactions()
                runOnUiThread {
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    priceText.text = String.format(Locale.US, "â‰ˆ $%,.2f (BTC $%,.2f)", bal * price, price)
                    addressText.text = "Äá»‹a chá»‰: $addr"
                    syncText.text = "ÄĂ£ Ä‘á»“ng bá»™ â€¢ " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    syncProgressBar.progress = 100
                    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, txs.map { "" }) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent)
                            val tx = txs[position]
                            val text1 = view.findViewById<TextView>(android.R.id.text1)
                            val text2 = view.findViewById<TextView>(android.R.id.text2)
                            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            text1.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                            text1.text = "${if (tx.type == "Nháº­n") "â¬‡" else "â¬†"} ${tx.type} ${String.format(Locale.US, "%.8f", tx.amount)}"
                            text2.text = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(tx.time) + " â€¢ " + tx.txId.take(12)
                            text2.setTextColor(Color.GRAY)
                            text2.textSize = 11f
                            return view
                        }
                    }
                    txListView.adapter = adapter
                    isSyncing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    syncText.text = "Lá»—i Ä‘á»“ng bá»™"
                    syncProgressBar.progress = 0
                    isSyncing = false
                }
            }
        }.start()
    }

    private fun showReceiveDialog() {
        val address = walletManager.getAddress()
        if (address.isEmpty()) {
            toast("VĂ­ chÆ°a sáºµn sĂ ng")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40)
        }
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(512, 512).apply { bottomMargin = 20 }
        }
        Thread {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(address, BarcodeFormat.QR_CODE, 512, 512)
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                for (x in 0 until 512) {
                    for (y in 0 until 512) {
                        bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                runOnUiThread { imageView.setImageBitmap(bmp) }
            } catch (e: Exception) {
                runOnUiThread { toast("Lá»—i táº¡o QR: ${e.message}") }
            }
        }.start()
        val addressView = TextView(this).apply {
            text = address
            textSize = 13f
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, 10, 0, 20)
        }
        val copyBtn = Button(this).apply { text = "Copy Ä‘á»‹a chá»‰" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("btc_address", address))
            toast("ÄĂ£ copy - sáº½ tá»± xĂ³a sau 30 giĂ¢y")
            handler.postDelayed({ try { cm.clearPrimaryClip() } catch (_: Exception) {} }, 30000)
        }
        layout.addView(imageView)
        layout.addView(addressView)
        layout.addView(copyBtn)
        AlertDialog.Builder(this)
            .setTitle("Nháº­n Bitcoin")
            .setView(layout)
            .setPositiveButton("ÄĂ³ng", null)
            .show()
    }


    private fun showSendDialog() {
        if (isSyncing) {
            toast("Äang sync, vui lĂ²ng Ä‘á»£i")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val toInput = EditText(this).apply { hint = "Äá»‹a chá»‰ BTC (bc1... hoáº·c 1... hoáº·c 3...)" }
        pendingAddressInput = toInput
        
        val scanBtn = Button(this).apply {
            text = "đŸ“· QuĂ©t QR nhÆ° Trust"
            setOnClickListener {
                try {
                    qrScanLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                        setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                        setPrompt("QuĂ©t Ä‘á»‹a chá»‰ BTC")
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                    })
                } catch (e: Exception) {
                    toast("Cáº§n thĂªm thÆ° viá»‡n ZXing")
                }
            }
        }
        
        val amountInput = EditText(this).apply {
            hint = "Sá»‘ lÆ°á»£ng BTC"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        val feeRates = try { walletManager.getFeeRates() } catch (_: Exception) { FeeRates(5, 10, 20) }
        val feeGroup = RadioGroup(this)
        val rSlow = RadioButton(this).apply { id = 1; text = "Cháº­m ~60' (${feeRates.slow} sat/vB)" }
        val rNormal = RadioButton(this).apply { id = 2; text = "ThÆ°á»ng ~30' (${feeRates.normal} sat/vB)"; isChecked = true }
        val rFast = RadioButton(this).apply { id = 3; text = "Nhanh ~10' (${feeRates.fast} sat/vB)" }
        val rCustom = RadioButton(this).apply { id = 4; text = "TĂ¹y chá»‰nh" }
        val customFeeInput = EditText(this).apply {
            hint = "1-100 sat/vB"
            inputType = InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
            setText("10")
        }
        feeGroup.addView(rSlow); feeGroup.addView(rNormal); feeGroup.addView(rFast); feeGroup.addView(rCustom)
        
        val feeEstimateTv = TextView(this).apply { text = "Æ¯á»›c tĂ­nh phĂ­: -"; setPadding(0,20,0,0) }
        val totalEstimateTv = TextView(this).apply { text = "Tá»•ng (gá»­i + phĂ­): -" }
        val balance = walletManager.getBalance()
        val balanceTv = TextView(this).apply { text = "Sá»‘ dÆ°: ${"%.8f".format(balance)} BTC"; setTextColor(0xFF888888.toInt()) }
        
        layout.addView(toInput)
        layout.addView(scanBtn)
        layout.addView(amountInput)
        layout.addView(balanceTv)
        layout.addView(TextView(this).apply { text = "Chá»n phĂ­ máº¡ng:"; setPadding(0,20,0,0) })
        layout.addView(feeGroup)
        layout.addView(customFeeInput)
        layout.addView(feeEstimateTv)
        layout.addView(totalEstimateTv)
        
        var priceUsd = 60000.0
        fetchBtcPriceUsd { p -> priceUsd = p }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Gá»­i BTC")
            .setView(layout)
            .setPositiveButton("Tiáº¿p tá»¥c", null)
            .setNegativeButton("Há»§y", null)
            .create()
        
        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            
            fun updateEstimates() {
                val to = toInput.text.toString().trim()
                val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                val feeRate = when (feeGroup.checkedRadioButtonId) {
                    1 -> feeRates.slow
                    3 -> feeRates.fast
                    4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1,100) ?: 10
                    else -> feeRates.normal
                }
                if (to.length >= 26 && amt > 0) {
                    try {
                        val estFee = walletManager.estimateFee(to, amt, feeRate)
                        val total = amt + estFee
                        val feeUsd = estFee * priceUsd
                        val totalUsd = total * priceUsd
                        feeEstimateTv.text = "Æ¯á»›c tĂ­nh phĂ­: ${"%.8f".format(estFee)} BTC (~$${"%.2f".format(feeUsd)})"
                        totalEstimateTv.text = "Tá»•ng: ${"%.8f".format(total)} BTC (~$${"%.2f".format(totalUsd)})"
                        // update radio texts with $
                        rSlow.text = "Cháº­m ~60' (${feeRates.slow} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.slow)*priceUsd)}"
                        rNormal.text = "ThÆ°á»ng ~30' (${feeRates.normal} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.normal)*priceUsd)}"
                        rFast.text = "Nhanh ~10' (${feeRates.fast} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.fast)*priceUsd)}"
                        rCustom.text = "TĂ¹y chá»‰nh (${feeRate} sat/vB) ~ $${"%.2f".format(estFee*priceUsd)}"
                        btn.isEnabled = total <= balance
                        btn.alpha = if (btn.isEnabled) 1f else 0.5f
                    } catch (_: Exception) { }
                } else {
                    btn.isEnabled = false
                }
            }
            
            feeGroup.setOnCheckedChangeListener { _, id ->
                customFeeInput.visibility = if (id == 4) View.VISIBLE else View.GONE
                updateEstimates()
            }
            val watcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateEstimates() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            toInput.addTextChangedListener(watcher)
            amountInput.addTextChangedListener(watcher)
            customFeeInput.addTextChangedListener(watcher)
            
            btn.setOnClickListener {
                val to = toInput.text.toString().trim()
                val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                val fee = when (feeGroup.checkedRadioButtonId) {
                    1 -> feeRates.slow
                    3 -> feeRates.fast
                    4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1,100) ?: 10
                    else -> feeRates.normal
                }
                val estFee = walletManager.estimateFee(to, amt, fee)
                dialog.dismiss()
                confirmSend(to, amt, fee, estFee)
            }
            updateEstimates()
        }
        dialog.show()
    }



    private fun confirmSend(to: String, amt: Double, feeRate: Int, estFee: Double) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val summary = TextView(this).apply {
            text = "Gá»­i: $amt BTC\nÄáº¿n: $to\nPhĂ­: ~$estFee BTC\nTá»•ng: ${amt + estFee} BTC"
            setPadding(0,0,0,20)
        }
        val passInput = EditText(this).apply {
            hint = "Nháº­p máº­t kháº©u Ä‘á»ƒ xĂ¡c nháº­n"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(summary)
        layout.addView(passInput)
        AlertDialog.Builder(this)
            .setTitle("XĂ¡c nháº­n gá»­i")
            .setView(layout)
            .setPositiveButton("XĂ¡c nháº­n") { _, _ ->
                val pass = passInput.text.toString()
                if (!walletManager.checkPassword(pass)) {
                    toast("Sai máº­t kháº©u")
                    return@setPositiveButton
                }
                // Delay 60s with progress
                val delayLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40,30,40,30)
                }
                val tv = TextView(this).apply { text = "Äang chuáº©n bá»‹ gá»­i sau 60 giĂ¢y..."; gravity = android.view.Gravity.CENTER }
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 60
                    progress = 60
                }
                val countdown = TextView(this).apply { text = "60s"; gravity = android.view.Gravity.CENTER; textSize = 18f }
                delayLayout.addView(tv); delayLayout.addView(progress); delayLayout.addView(countdown)
                val delayDialog = AlertDialog.Builder(this).setTitle("Delay báº£o máº­t").setView(delayLayout).setCancelable(false).create()
                delayDialog.show()
                var sec = 60
                val handler = android.os.Handler(mainLooper)
                val runnable = object : Runnable {
                    override fun run() {
                        sec--
                        progress.progress = sec
                        countdown.text = "${sec}s"
                        if (sec > 0) {
                            handler.postDelayed(this, 1000)
                        } else {
                            delayDialog.dismiss()
                            Thread {
                                try {
                                    val txid = walletManager.send(to, amt, feeRate)
                                    runOnUiThread {
                                        toast("ÄĂ£ gá»­i! TXID: ${txid.take(8)}...")
                                        refreshBalance()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Lá»—i gá»­i: ${e.message}") }
                                }
                            }.start()
                        }
                    }
                }
                handler.postDelayed(runnable, 1000)
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }


    private fun showSettings() {
        val items = arrayOf("đŸ‘ Xem seed phrase", "đŸ”‘ Äá»•i máº­t kháº©u", "âœï¸ Äá»•i tĂªn vĂ­", "đŸ—‘ XĂ³a vĂ­ vÄ©nh viá»…n", "đŸ”’ CĂ i Ä‘áº·t khĂ³a vĂ­", "â„¹ï¸ ThĂ´ng tin")
        AlertDialog.Builder(this)
            .setTitle("CĂ i Ä‘áº·t")
            .setItems(items) { _, w ->
                when(w) {
                    0 -> showSeedDialog()
                    1 -> showChangePassDialog()
                    2 -> showRenameDialog()
                    3 -> showDeleteDialog()
                    4 -> showLockSettings()
                    5 -> showInfo()
                }
            }
            .show()
    }

    private fun showSeedDialog() {
        val pass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        AlertDialog.Builder(this)
            .setTitle("Nháº­p máº­t kháº©u Ä‘á»ƒ xem seed")
            .setView(pass)
            .setPositiveButton("Xem") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                if (walletManager.unlock(id, pass.text.toString())) {
                    val seed = walletManager.getSeed()
                    val tv = TextView(this).apply {
                        text = seed
                        textSize = 16f
                        setTextIsSelectable(true)
                        setPadding(40,40,40,40)
                        gravity = Gravity.CENTER
                    }
                    AlertDialog.Builder(this)
                        .setTitle("â ï¸ KHĂ”NG CHIA Sáºº SEED")
                        .setView(tv)
                        .setPositiveButton("Copy 30s") { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("seed", seed))
                            handler.postDelayed({ cm.clearPrimaryClip() }, 30000)
                        }
                        .setNegativeButton("ÄĂ³ng", null)
                        .show()
                } else toast("Sai máº­t kháº©u")
            }
            .show()
    }

    private fun showChangePassDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val oldP = EditText(this).apply {
            hint = "Máº­t kháº©u cÅ©"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newP = EditText(this).apply {
            hint = "Máº­t kháº©u má»›i â‰¥8"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(oldP)
        layout.addView(newP)
        AlertDialog.Builder(this)
            .setTitle("Äá»•i máº­t kháº©u")
            .setView(layout)
            .setPositiveButton("Äá»•i") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                if (walletManager.changePassword(id, oldP.text.toString(), newP.text.toString()))
                    toast("ÄĂ£ Ä‘á»•i thĂ nh cĂ´ng")
                else toast("Sai máº­t kháº©u cÅ©")
            }
            .show()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            hint = "TĂªn vĂ­ má»›i"
            setText(walletManager.getActive()?.name?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Äá»•i tĂªn")
            .setView(input)
            .setPositiveButton("LÆ°u") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                walletManager.rename(id, input.text.toString())
                walletNameText.text = input.text.toString()
                toast("ÄĂ£ Ä‘á»•i tĂªn")
            }
            .show()
    }

    private fun showDeleteDialog() {
        val pass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("XĂ“A VÄ¨NH VIá»„N")
            .setMessage("Nháº­p máº­t kháº©u Ä‘á»ƒ xĂ³a. KhĂ´ng thá»ƒ khĂ´i phá»¥c náº¿u khĂ´ng cĂ³ seed!")
            .setView(pass)
            .setPositiveButton("XĂ“A") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                if (walletManager.unlock(id, pass.text.toString())) {
                    walletManager.delete(id)
                    showWelcome()
                    toast("ÄĂ£ xĂ³a")
                } else toast("Sai pass")
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle("iBTC v4.7")
            .setMessage("Build: 2026-05-25\nâ€¢ Block update 2s\nâ€¢ NĂºt LĂ m má»›i Ä‘á»©ng im")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

    private fun showLockSettings() {
        val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
        val current = prefs.getInt("auto_lock_minutes", 5)
        val items = arrayOf("KhĂ³a ngay", "1 phĂºt", "5 phĂºt", "15 phĂºt", "30 phĂºt", "KhĂ´ng tá»± Ä‘á»™ng khĂ³a")
        AlertDialog.Builder(this@MainActivity)
            .setTitle("CĂ i Ä‘áº·t khĂ³a vĂ­")
            .setSingleChoiceItems(items, when(current){1->1;5->2;15->3;30->4;0->5 else->2}) { dialog, which ->
                when(which) {
                    0 -> { walletManager.lock(); dialog.dismiss(); showUnlockDialog() }
                    1 -> prefs.edit().putInt("auto_lock_minutes",1).apply()
                    2 -> prefs.edit().putInt("auto_lock_minutes",5).apply()
                    3 -> prefs.edit().putInt("auto_lock_minutes",15).apply()
                    4 -> prefs.edit().putInt("auto_lock_minutes",30).apply()
                    5 -> prefs.edit().putInt("auto_lock_minutes",0).apply()
                }
                if (which != 0) toast("ÄĂ£ lÆ°u: ${items[which]}")
            }
            .setNegativeButton("ÄĂ³ng", null)
            .show()
    }
