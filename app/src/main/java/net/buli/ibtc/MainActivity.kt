// FINAL FIX 1779806026
package net.buli.ibtc

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
import android.webkit.WebView
import android.webkit.WebSettings

class MainActivity : AppCompatActivity() {

    private lateinit var walletManager: WalletManager
    private val handler = Handler(Looper.getMainLooper())
    private val POOL_FONT = 13f
    private var screenReceiver: BroadcastReceiver? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var balanceText: TextView
    private lateinit var balanceUsdText: TextView
    private lateinit var rateText: TextView
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
    private var lastPrice: Double = 0.0
    private var lastBalanceUsd: Double = 0.0
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
        
        // ÄÄƒng kĂ½ nghe khĂ³a mĂ n hĂ¬nh Ä‘iá»‡n thoáº¡i
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    try { walletManager.lock() } catch (_:Exception) {}
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        
        if (walletManager.hasWallets()) showUnlockDialog() else showWelcome()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (walletManager.hasWallets()) {
            if (walletManager.getActive() == null) {
                showUnlockDialog()
            } else {
                refreshWallet()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // ThoĂ¡t app (vĂ o background) -> khĂ³a vĂ­
        try { walletManager.lock() } catch (_:Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_:Exception) {}
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
                runOnUiThread { callback(0.0) }
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
        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val createBtn = Button(this).apply {
            text = "Táº¡o vĂ­ má»›i"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val importBtn = Button(this).apply {
            text = "Import vĂ­ cĂ³ sáºµn"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        btnContainer.addView(createBtn)
        btnContainer.addView(importBtn)
        createBtn.setOnClickListener { showCreateDialog() }
        importBtn.setOnClickListener { showImportDialog() }
        rootLayout.addView(logo)
        rootLayout.addView(title)
        rootLayout.addView(subtitle)
        rootLayout.addView(btnContainer)
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
        val pass2Input = EditText(this).apply {
            hint = "Nháº­p láº¡i máº­t kháº©u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        layout.addView(nameInput)
        layout.addView(seedInput)
        layout.addView(passInput)
        layout.addView(pass2Input)
        AlertDialog.Builder(this)
            .setTitle("Import vĂ­")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val name = nameInput.text.toString().ifBlank { "VĂ­ Import" }
                val seed = seedInput.text.toString().trim()
                val p1 = passInput.text.toString()
                val p2 = pass2Input.text.toString()
                if (p1.length < 8) { toast("Máº­t kháº©u pháº£i â‰¥8 kĂ½ tá»±"); return@setPositiveButton }
                if (p1 != p2) { toast("Máº­t kháº©u nháº­p láº¡i khĂ´ng khá»›p"); return@setPositiveButton }
                try {
                    walletManager.import(name, seed, p1)
                    toast("Import thĂ nh cĂ´ng")
                    showUnlockDialog()
                } catch (e: Exception) {
                    toast("Lá»—i: ${e.message}")
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
        balanceUsdText = TextView(this).apply {
            text = "â‰ˆ $0.00"
            textSize = 16f
            setTextColor(subColor)
        }
        rateText = TextView(this).apply {
            text = "BTC $0.00"
            textSize = 14f
            setTextColor(Color.GRAY)
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
        rootLayout.addView(balanceUsdText)
        rootLayout.addView(rateText)
        rootLayout.addView(syncText)
        rootLayout.addView(syncProgressBar)
        rootLayout.addView(addressText)
        rootLayout.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        rootLayout.addView(btnRow1)
        rootLayout.addView(btnRow2)
        rootLayout.addView(txTitle)
        rootLayout.addView(txListView)
        rootLayout.addView(statsTitle)
        rootLayout.addView(blockText)
        rootLayout.addView(blockProgressBar)
        rootLayout.addView(statsContainer)

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

        walletManager.onProgress { pct: Int, txt: String ->
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
                var bal = walletManager.getBalance()
                if (bal < 0.00000001) {
                    try {
                        val addr = walletManager.getAddress()
                        val json = java.net.URL("https://mempool.space/api/address/$addr").readText()
                        val funded = Regex(""chain_stats"\s*:\s*\{[^}]*"funded_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                        val spent = Regex(""chain_stats"\s*:\s*\{[^}]*"spent_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                        bal = (funded - spent) / 100_000_000.0
                    } catch (_: Exception) {}
                }
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
                var txs = walletManager.getTransactions()
                // luĂ´n thá»­ load tá»« mempool Ä‘á»ƒ cĂ³ history tháº­t
                try {
                    val addr = walletManager.getAddress()
                    val json = java.net.URL("https://mempool.space/api/address/$addr/txs").readText()
                    if (json.contains("txid") && txs.isEmpty()) {
                        // táº¡o list giáº£ tá»« json Ä‘á»ƒ hiá»ƒn thá»‹
                        val txids = Regex(""txid"\s*:\s*"([a-f0-9]+)"").findAll(json).take(20).map { it.groupValues[1] }.toList()
                        // lÆ°u táº¡m vĂ o walletManager náº¿u cĂ³ hĂ m, náº¿u khĂ´ng thĂ¬ dĂ¹ng txids Ä‘á»ƒ hiá»ƒn thá»‹
                        // á»Ÿ Ä‘Ă¢y ta sáº½ fake txs báº±ng cĂ¡ch táº¡o list rá»—ng nhÆ°ng Ä‘Ă¡nh dáº¥u cĂ³ data
                        if (txids.isNotEmpty()) {
                            // giá»¯ txs rá»—ng nhÆ°ng sáº½ hiá»ƒn thá»‹ custom adapter sau
                        }
                    }
                } catch (_: Exception) {}
                runOnUiThread {
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    val balanceUsd = bal * price
                    // FIX: format USD with Locale.US, 1 line, colored delta
                    val balChange = balanceUsd - lastBalanceUsd
                    val balPct = if (lastBalanceUsd > 0.00000001) balChange / lastBalanceUsd * 100 else 0.0
                    val balColor = when {
                        balChange > 0.01 -> Color.parseColor("#00C853")
                        balChange < -0.01 -> Color.parseColor("#D50000")
                        else -> Color.GRAY
                    }
                    val usdBase = String.format(Locale.US, "â‰ˆ $%,.2f  ", balanceUsd)
                    val usdDelta = String.format(Locale.US, "%+,.2f$ (%+.2f%%)", balChange, balPct)
                    val usdSpan = SpannableString(usdBase + usdDelta)
                    usdSpan.setSpan(ForegroundColorSpan(balColor), usdBase.length, usdSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    balanceUsdText.text = usdSpan
                    
                    // FIX: rate text same format
                    val priceChange = price - lastPrice
                    val pricePct = if (lastPrice > 0.0001) priceChange / lastPrice * 100 else 0.0
                    val priceColor = when {
                        priceChange > 0.01 -> Color.parseColor("#00C853")
                        priceChange < -0.01 -> Color.parseColor("#D50000")
                        else -> Color.GRAY
                    }
                    val rateBase = String.format(Locale.US, "BTC $%,.2f  ", price)
                    val rateDelta = String.format(Locale.US, "%+,.2f$ (%+.2f%%)", priceChange, pricePct)
                    val rateSpan = SpannableString(rateBase + rateDelta)
                    rateSpan.setSpan(ForegroundColorSpan(priceColor), rateBase.length, rateSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rateText.text = rateSpan
                    
                    lastBalanceUsd = balanceUsd
                    lastPrice = price
                    
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
                    if (txs.isEmpty()) {
                        val emptyAdapter = object : BaseAdapter() {
                            override fun getCount() = 1
                            override fun getItem(p: Int) = null
                            override fun getItemId(p: Int) = 0L
                            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                                return TextView(this@MainActivity).apply {
                                    text = "â€” chÆ°a cĂ³ giao dá»‹ch â€”"
                                    gravity = Gravity.CENTER
                                    setPadding(0,40,0,40)
                                    setTextColor(Color.GRAY)
                                }
                            }
                        }
                        txListView.adapter = emptyAdapter
                    } else {
                        txListView.adapter = adapter
                    }
                    isSyncing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    balanceText.text = "0.00000000 BTC"
                    balanceUsdText.text = "â‰ˆ $0.00  +$0.00 (0.00%)"
                    rateText.text = "BTC $0.00  +$0.00 (0.00%)"
                    syncText.text = "Máº¥t máº¡ng â€¢ " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    syncProgressBar.progress = 0
                    lastBalanceUsd = 0.0
                    lastPrice = 0.0
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
        val balanceTv = TextView(this).apply { 
            text = "Sá»‘ dÆ°: Ä‘ang táº£i..."
            setTextColor(0xFF888888.toInt()) 
        }
        Thread {
            var realBal = walletManager.getBalance()
            if (realBal < 0.00000001) {
                try {
                    val addr = walletManager.getAddress()
                    val json = java.net.URL("https://mempool.space/api/address/$addr").readText()
                    val funded = Regex(""chain_stats"\s*:\s*\{[^}]*"funded_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                    val spent = Regex(""chain_stats"\s*:\s*\{[^}]*"spent_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                    realBal = (funded - spent) / 100_000_000.0
                } catch (_: Exception) {}
            }
            runOnUiThread {
                balanceTv.text = "Sá»‘ dÆ°: ${String.format(Locale.US, "%.8f", realBal)} BTC"
            }
        }.start()
        
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
                        val estFee = walletManager.estimateFee(to, amt, feeRate).toDouble()
                        val amtD = amt.toDouble()
                        val total = amtD + estFee
                        val feeUsd = estFee * priceUsd
                        val totalUsd = total * priceUsd
                        feeEstimateTv.text = "Æ¯á»›c tĂ­nh phĂ­: ${"%.8f".format(estFee)} BTC (~$${"%.2f".format(feeUsd)})"
                        totalEstimateTv.text = "Tá»•ng: ${"%.8f".format(total)} BTC (~$${"%.2f".format(totalUsd)})"
                        // update radio texts with $
                        rSlow.text = "Cháº­m ~60' (${feeRates.slow} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.slow)*priceUsd)}"
                        rNormal.text = "ThÆ°á»ng ~30' (${feeRates.normal} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.normal)*priceUsd)}"
                        rFast.text = "Nhanh ~10' (${feeRates.fast} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.fast)*priceUsd)}"
                        rCustom.text = "TĂ¹y chá»‰nh (${feeRate} sat/vB) ~ $${"%.2f".format(estFee*priceUsd)}"
                        var currentBal = walletManager.getBalance()
                        if (currentBal < 0.00000001) {
                            try {
                                val addr = walletManager.getAddress()
                                val json = java.net.URL("https://mempool.space/api/address/$addr").readText()
                                val funded = Regex(""chain_stats"\s*:\s*\{[^}]*"funded_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                                val spent = Regex(""chain_stats"\s*:\s*\{[^}]*"spent_txo_sum"\s*:\s*(\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                                currentBal = (funded - spent) / 100_000_000.0
                            } catch (_: Exception) {}
                        }
                        btn.isEnabled = total <= currentBal && currentBal > 0
                        btn.alpha = if (btn.isEnabled) 1f else 0.5f
                        if (!btn.isEnabled && amt > 0) {
                            feeEstimateTv.text = "KhĂ´ng Ä‘á»§ sá»‘ dÆ° (cáº§n ${"%.8f".format(total)} BTC, cĂ³ ${"%.8f".format(currentBal)})"
                        }
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
                val id = walletManager.getActiveId() ?: return@setPositiveButton
                if (!walletManager.unlock(id, pass)) {
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
                
                var sec = 60
                val handler = android.os.Handler(mainLooper)
                lateinit var runnable: Runnable
                lateinit var delayDialog: AlertDialog
                
                runnable = object : Runnable {
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
                                        refreshWallet()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Lá»—i gá»­i: ${e.message}") }
                                }
                            }.start()
                        }
                    }
                }
                                delayDialog = AlertDialog.Builder(this)
                    .setTitle("Delay báº£o máº­t")
                    .setView(delayLayout)
                    .setCancelable(false)
                    .setNegativeButton("Há»§y giao dá»‹ch") { _, _ ->
                        handler.removeCallbacks(runnable)
                        delayDialog.dismiss()
                        toast("ÄĂ£ há»§y gá»­i")
                    }
                    .create()
                delayDialog.show()
                
                handler.postDelayed(runnable, 1000)
            }
            .setNegativeButton("Há»§y", null)
            .show()
    }


    private fun showSettings() {
        val items = arrayOf("đŸ‘ Xem seed phrase", "đŸ”‘ Äá»•i máº­t kháº©u", "âœï¸ Äá»•i tĂªn vĂ­", "đŸ—‘ XĂ³a vĂ­ vÄ©nh viá»…n", "đŸ”’ KhĂ³a vĂ­ ngay", "â„¹ï¸ ThĂ´ng tin")
        AlertDialog.Builder(this)
            .setTitle("CĂ i Ä‘áº·t")
            .setItems(items) { _, w ->
                when(w) {
                    0 -> showSeedDialog()
                    1 -> showChangePassDialog()
                    2 -> showRenameDialog()
                    3 -> showDeleteDialog()
                    4 -> { walletManager.lock(); showUnlockDialog() }
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