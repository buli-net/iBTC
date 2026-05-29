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
    private val POOL_FONT = 13f
    private var screenReceiver: BroadcastReceiver? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var balanceText: TextView
    private lateinit var balanceUsdText: TextView
    private lateinit var rateText: TextView
    private lateinit var addressText: TextView
    private lateinit var blockText: TextView
    private lateinit var blockProgressBar: ProgressBar
    private lateinit var txListView: ListView
    private lateinit var walletNameText: TextView
    private lateinit var statsContainer: LinearLayout
    private lateinit var spvStatusText: TextView
    private lateinit var spvProgressBar: ProgressBar
    private val statBars = mutableMapOf<String, ProgressBar>()
    private val statTexts = mutableMapOf<String, TextView>()
    private var isSyncing = false
    private var autoRefreshStarted = false
    private var pendingAddressInput: EditText? = null
    private var lastPrice: Double? = null
    private var lastBalanceUsd: Double = 0.0
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { addr ->
            pendingAddressInput?.setText(addr)
            toast("Đã quét: ${addr.take(10)}...")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        walletManager = WalletManager(this)
        setupRootLayout()
        setContentView(scrollView)

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) { }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        try {
            if (walletManager.getActiveId() != null || walletManager.hasWallets()) {
                showUnlockDialog()
            } else {
                showWelcome()
            }
        } catch (_: Exception) {
            showWelcome()
        }
    }

    override fun onResume() {
        super.onResume()
        SyncService.getInstance()?.setProgressCallback { pct, txt ->
            runOnUiThread {
                spvStatusText.text = "SPV: $txt"
                spvProgressBar.progress = pct
            }
        }
        try {
            if (walletManager.getActiveId() != null || walletManager.hasWallets()) {
                showUnlockDialog()
            } else {
                showWelcome()
            }
        } catch (_: Exception) {
            showWelcome()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_:Exception) {}
        try { walletManager.stop() } catch (_: Exception) {}
        walletManager.lock()
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

    // ================== SPV refresh (không dùng API) ==================
    private fun refreshWalletFromSPV() {
        if (isSyncing) return
        isSyncing = true
        runOnUiThread {
            spvStatusText.text = "SPV: Đang cập nhật số dư..."
        }
        Thread {
            try {
                val bal = walletManager.getBalance()
                val txs = walletManager.getTransactions()
                val price = walletManager.price() // có thể là 0.0 nếu chưa lấy được
                runOnUiThread {
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    val balanceUsd = if (price > 0) bal * price else 0.0
                    val priceDisplay = if (price > 0) String.format(Locale.US, "BTC $%,.2f", price) else "BTC ---"

                    // Tính biến động số dư USD (nếu có giá)
                    if (price > 0 && lastPrice != null && lastPrice!! > 0) {
                        val balChange = balanceUsd - lastBalanceUsd
                        val balPct = if (lastBalanceUsd > 0) balChange / lastBalanceUsd * 100 else 0.0
                        val balArrow = when {
                            balChange > 0.01 -> "▲"
                            balChange < -0.01 -> "▼"
                            else -> "●"
                        }
                        val balColor = when {
                            balChange > 0.01 -> Color.parseColor("#00C853")
                            balChange < -0.01 -> Color.parseColor("#D50000")
                            else -> Color.GRAY
                        }
                        balanceUsdText.setTextColor(balColor)
                        balanceUsdText.text = String.format(Locale.US, "≈ $%,.2f %s %+.2f%% (%+.2f$)", balanceUsd, balArrow, balPct, balChange)

                        // Tính biến động tỷ giá
                        val priceChange = price - lastPrice!!
                        val pricePct = if (lastPrice!! > 0) priceChange / lastPrice!! * 100 else 0.0
                        val priceArrow = when {
                            priceChange > 0.01 -> "▲"
                            priceChange < -0.01 -> "▼"
                            else -> "●"
                        }
                        val priceColor = when {
                            priceChange > 0.01 -> Color.parseColor("#00C853")
                            priceChange < -0.01 -> Color.parseColor("#D50000")
                            else -> Color.GRAY
                        }
                        rateText.setTextColor(priceColor)
                        rateText.text = String.format(Locale.US, "BTC $%,.2f %s %+.2f%% (%+.2f$)", price, priceArrow, pricePct, priceChange)
                    } else {
                        // Không có giá hoặc lần đầu
                        balanceUsdText.text = if (price > 0) String.format(Locale.US, "≈ $%,.2f", balanceUsd) else "≈ $---"
                        rateText.text = priceDisplay
                    }

                    lastBalanceUsd = balanceUsd
                    lastPrice = if (price > 0) price else null

                    val addr = walletManager.getAddress()
                    addressText.text = "Địa chỉ: $addr"
                    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, txs.map { "" }) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent)
                            val tx = txs[position]
                            val text1 = view.findViewById<TextView>(android.R.id.text1)
                            val text2 = view.findViewById<TextView>(android.R.id.text2)
                            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            text1.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                            text1.text = "${if (tx.type == "RECEIVE") "⬇" else "⬆"} ${tx.type} ${String.format(Locale.US, "%.8f", tx.amount)} BTC"
                            text2.text = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(tx.time) + " • " + tx.txId.take(12)
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
                    spvStatusText.text = "SPV: Lỗi cập nhật"
                    isSyncing = false
                }
            }
        }.start()
    }

    private fun startAutoRefresh() {
        if (autoRefreshStarted) return
        autoRefreshStarted = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (walletManager.getActive() != null && !isSyncing && !isFinishing && !isDestroyed) {
                    refreshWalletFromSPV()
                }
                if (!isFinishing && !isDestroyed) {
                    handler.postDelayed(this, 45000)
                }
            }
        }, 45000)
    }

    // ================== Thống kê mạng (chỉ hiển thị) ==================
    private fun fetchBlockUpdate() {
        Thread {
            try {
                val json = URL("https://mempool.space/api/v1/blocks").openStream().bufferedReader().readText()
                val height = Regex("\"height\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0
                val lastTime = Regex("\"timestamp\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                val nextHeight = height + 1
                val elapsed = (System.currentTimeMillis() / 1000 - lastTime).coerceAtLeast(0)
                val percent = ((elapsed * 100) / 600).toInt()
                val remain = 600 - elapsed
                runOnUiThread {
                    blockProgressBar.progress = percent.coerceAtMost(100)
                    if (remain >= 0) {
                        val mins = remain / 60
                        val secs = remain % 60
                        blockText.text = "Đang khai thác block #$nextHeight — $percent% (~${mins}m${String.format("%02d", secs)}s)"
                    } else {
                        val over = -remain
                        val mins = over / 60
                        val secs = over % 60
                        blockText.text = "Block #$nextHeight đã quá hạn +${mins}m${String.format("%02d", secs)}s ($percent%)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { blockText.text = "Lỗi pool - tự thử lại"; blockProgressBar.progress = 0 }
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
                val diffProgress = Regex("\"progressPercent\":([\\d.]+)").find(diffJson)?.groupValues?.get(1)?.toFloat() ?: 0f
                val mempoolJson = URL("https://mempool.space/api/mempool").readText()
                val mempoolCount = Regex("\"count\":(\\d+)").find(mempoolJson)?.groupValues?.get(1)?.toInt() ?: 0
                val feesJson = URL("https://mempool.space/api/v1/fees/recommended").readText()
                val feeFast = Regex("\"fastestFee\":(\\d+)").find(feesJson)?.groupValues?.get(1)?.toInt() ?: 0
                val hashJson = URL("https://mempool.space/api/v1/mining/hashrate/1w").readText()
                val currentHash = Regex("\"currentHashrate\":([\\d.]+)").find(hashJson)?.groupValues?.get(1)?.toDouble() ?: 0.0
                runOnUiThread {
                    val minedPct = ((totalMined / 21000000.0) * 100).toInt()
                    statBars["mined"]?.progress = minedPct
                    statTexts["mined"]?.text = "Đã khai thác: ${String.format("%.2f", totalMined)} / 21M BTC ($minedPct%)"
                    val halvingPct = ((1 - blocksToHalving / 210000.0) * 100).toInt()
                    statBars["halving"]?.progress = halvingPct
                    statTexts["halving"]?.text = "Halving #${halvings + 1}: còn $blocksToHalving blocks (~${blocksToHalving / 144} ngày)"
                    val rewardPct = ((reward / 50.0) * 100).toInt()
                    statBars["reward"]?.progress = rewardPct
                    statTexts["reward"]?.text = "Thưởng block: $reward BTC (ban đầu 50 BTC)"
                    statBars["diff"]?.progress = diffProgress.toInt()
                    statTexts["diff"]?.text = "Difficulty adj: ${String.format("%.1f", diffProgress)}%"
                    val mempoolPct = (mempoolCount / 300000.0 * 100).toInt().coerceAtMost(100)
                    statBars["mempool"]?.progress = mempoolPct
                    statTexts["mempool"]?.text = "Mempool: $mempoolCount tx chờ"
                    val hashEh = currentHash / 1e18
                    statBars["hash"]?.progress = 70
                    statTexts["hash"]?.text = "Hashrate: ${String.format("%.0f", hashEh)} EH/s"
                    statBars["fee"]?.progress = feeFast.coerceAtMost(100)
                    statTexts["fee"]?.text = "Phí nhanh: $feeFast sat/vB"
                    val blocksToday = height % 144
                    statBars["today"]?.progress = (blocksToday * 100 / 144)
                    statTexts["today"]?.text = "Block hôm nay: $blocksToday / 144"
                    statBars["supply"]?.progress = minedPct
                    statTexts["supply"]?.text = "Cung lưu thông: ${String.format("%.2f", totalMined / 1000000)}M BTC"
                    statBars["height"]?.progress = height % 100
                    statTexts["height"]?.text = "Block height: #$height"
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun startBlockProgress() {
        blockText.text = "Đang kết nối mempool..."
        handler.post(object : Runnable {
            override fun run() { fetchBlockUpdate(); handler.postDelayed(this, 2000) }
        })
        handler.post(object : Runnable {
            override fun run() { fetchBtcStats(); handler.postDelayed(this, 30000) }
        })
    }

    private fun addStat(key: String, label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = POOL_FONT
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 2)
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

    // ================== CÁC DIALOG ==================
    private fun showWelcome() {
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isDark) Color.WHITE else Color.BLACK
        val logo = TextView(this).apply {
            text = "₿"
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
            text = "Bitcoin wallet an toàn, mã nguồn mở"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 60)
        }
        val createBtn = Button(this).apply {
            text = "Tạo ví mới"
            textSize = 16f
            setPadding(0, 30, 0, 30)
        }
        val importBtn = Button(this).apply {
            text = "Import ví có sẵn"
            textSize = 16f
        }
        val space = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val nameInput = EditText(this).apply { hint = "Tên ví (tùy chọn)"; inputType = InputType.TYPE_CLASS_TEXT }
        val passInput = EditText(this).apply {
            hint = "Mật khẩu (tối thiểu 8 ký tự)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val pass2Input = EditText(this).apply {
            hint = "Nhập lại mật khẩu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val warning = TextView(this).apply {
            text = "⚠️ Lưu mật khẩu cẩn thận. Mất = mất ví."
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(0, 20, 0, 0)
        }
        layout.addView(nameInput)
        layout.addView(passInput)
        layout.addView(pass2Input)
        layout.addView(warning)
        AlertDialog.Builder(this)
            .setTitle("Tạo ví Bitcoin mới")
            .setView(layout)
            .setPositiveButton("Tạo") { _, _ ->
                val name = nameInput.text.toString().trim()
                val p1 = passInput.text.toString()
                val p2 = pass2Input.text.toString()
                if (p1.length < 8) { toast("Mật khẩu phải ≥8 ký tự"); return@setPositiveButton }
                if (p1 != p2) { toast("Mật khẩu không khớp"); return@setPositiveButton }
                try {
                    walletManager.create(name, p1)
                    Thread { walletManager.init() }.start()
                    toast("Tạo ví thành công")
                    showMainWallet()
                } catch (e: Exception) { toast("Lỗi: ${e.message}") }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showImportDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val nameInput = EditText(this).apply { hint = "Tên ví" }
        val seedInput = EditText(this).apply {
            hint = "12 hoặc 24 từ seed, cách nhau bằng space"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val passInput = EditText(this).apply {
            hint = "Mật khẩu mới (≥8 ký tự)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val confirmPassInput = EditText(this).apply {
            hint = "Nhập lại mật khẩu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        layout.addView(nameInput)
        layout.addView(seedInput)
        layout.addView(passInput)
        layout.addView(confirmPassInput)
        AlertDialog.Builder(this)
            .setTitle("Import ví")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val name = nameInput.text.toString().trim()
                val seed = seedInput.text.toString().trim()
                val pass = passInput.text.toString()
                val confirm = confirmPassInput.text.toString()
                if (pass.length < 8) {
                    toast("Mật khẩu phải ≥8 ký tự")
                    return@setPositiveButton
                }
                if (pass != confirm) {
                    toast("Mật khẩu không khớp")
                    return@setPositiveButton
                }
                val info = walletManager.import(name, seed, pass)
                if (info == null) toast("Seed không hợp lệ (cần 12-24 từ)")
                else {
                    Thread { walletManager.init() }.start()
                    toast("Import thành công")
                    showMainWallet()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showUnlockDialog() {
        val id = walletManager.getActiveId()
        if (id == null) { showWelcome(); return }
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isDark) Color.WHITE else Color.BLACK
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40)
        }
        val title = TextView(this).apply {
            text = "🔒 Ví đã khóa"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
            setTextColor(titleColor)
        }
        val passInput = EditText(this).apply {
            hint = "Nhập mật khẩu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val unlockBtn = Button(this).apply {
            text = "Mở khóa"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        }
        unlockBtn.setOnClickListener {
            val pass = passInput.text.toString()
            if (walletManager.unlock(id, pass)) {
                Thread { walletManager.init() }.start()
                showMainWallet()
            } else {
                toast("Sai mật khẩu (khóa sau 5 lần)")
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
            text = walletManager.getActive()?.name ?: "Ví"
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
            text = "≈ $---"
            textSize = 16f
            setTextColor(subColor)
        }
        rateText = TextView(this).apply {
            text = "BTC ---"
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        spvStatusText = TextView(this).apply {
            text = "SPV: Đang khởi động..."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 4)
        }
        spvProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F7931A"))
            scaleY = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4; bottomMargin = 8 }
        }
        addressText = TextView(this).apply {
            textSize = 12f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(subColor)
            setPadding(0, 10, 0, 10)
        }
        blockText = TextView(this).apply {
            text = "Đang kết nối mempool..."
            textSize = POOL_FONT
            setTextColor(subColor)
            setPadding(0, 8, 0, 2)
            typeface = Typeface.DEFAULT
        }
        blockProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            scaleY = 0.7f
        }

        val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnReceive = Button(this).apply {
            text = "⬇ Nhận"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val btnSend = Button(this).apply {
            text = "⬆ Gửi"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnRefresh = Button(this).apply {
            text = "⟳ Làm mới"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val btnSettings = Button(this).apply {
            text = "⚙ Cài đặt"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        btnRow1.addView(btnReceive)
        btnRow1.addView(btnSend)
        btnRow2.addView(btnRefresh)
        btnRow2.addView(btnSettings)

        statsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 5, 0, 0) }
        val statsTitle = TextView(this).apply {
            text = "📊 Thống kê Bitcoin"
            textSize = POOL_FONT
            typeface = Typeface.DEFAULT
            setPadding(0, 20, 0, 5)
            setTextColor(mainColor)
        }
        val txTitle = TextView(this).apply {
            text = "Lịch sử giao dịch"
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
        rootLayout.addView(spvStatusText)
        rootLayout.addView(spvProgressBar)
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

        addStat("mined", "Đã khai thác")
        addStat("halving", "Halving")
        addStat("reward", "Phần thưởng")
        addStat("diff", "Difficulty")
        addStat("mempool", "Mempool")
        addStat("hash", "Hashrate")
        addStat("fee", "Phí")
        addStat("today", "Hôm nay")
        addStat("supply", "Cung")
        addStat("height", "Height")

        btnReceive.setOnClickListener { showReceiveDialog() }
        btnSend.setOnClickListener { showSendDialog() }
        btnRefresh.setOnClickListener {
            refreshWalletFromSPV()
            fetchBlockUpdate()
            fetchBtcStats()
            toast("Đang làm mới từ SPV...")
        }
        btnSettings.setOnClickListener { showSettings() }

        walletManager.onProgress { pct, txt ->
            runOnUiThread {
                spvStatusText.text = "SPV: $txt"
                spvProgressBar.progress = pct
            }
        }
        SyncService.getInstance()?.setProgressCallback { pct, txt ->
            runOnUiThread {
                spvStatusText.text = "SPV: $txt"
                spvProgressBar.progress = pct
            }
        }

        refreshWalletFromSPV()
        startAutoRefresh()
        startBlockProgress()
    }

    private fun showReceiveDialog() {
        val address = walletManager.getAddress()
        if (address.isEmpty()) { toast("Ví chưa sẵn sàng"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40) }
        val imageView = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(512, 512).apply { bottomMargin = 20 } }
        Thread {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(address, BarcodeFormat.QR_CODE, 512, 512)
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                for (x in 0 until 512) for (y in 0 until 512) bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                runOnUiThread { imageView.setImageBitmap(bmp) }
            } catch (e: Exception) { runOnUiThread { toast("Lỗi tạo QR: ${e.message}") } }
        }.start()
        val addressView = TextView(this).apply {
            text = address
            textSize = 13f
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, 10, 0, 20)
        }
        val copyBtn = Button(this).apply { text = "Copy địa chỉ" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("btc_address", address))
            toast("Đã copy - sẽ tự xóa sau 30 giây")
            handler.postDelayed({ try { cm.clearPrimaryClip() } catch (_: Exception) {} }, 30000)
        }
        layout.addView(imageView)
        layout.addView(addressView)
        layout.addView(copyBtn)
        AlertDialog.Builder(this).setTitle("Nhận Bitcoin").setView(layout).setPositiveButton("Đóng", null).show()
    }

    // ================== DIALOG GỬI BTC ==================
    private fun showSendDialog() {
        if (isSyncing) {
            toast("Đang cập nhật SPV, vui lòng đợi")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val toInput = EditText(this).apply { hint = "Địa chỉ BTC (bc1... hoặc 1... hoặc 3...)" }
        pendingAddressInput = toInput

        val scanBtn = Button(this).apply {
            text = "📷 Quét QR như Trust"
            setOnClickListener {
                try {
                    qrScanLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                        setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                        setPrompt("Quét địa chỉ BTC")
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                    })
                } catch (e: Exception) {
                    toast("Cần thêm thư viện ZXing")
                }
            }
        }

        val amountInput = EditText(this).apply {
            hint = "Số lượng BTC"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val balanceTv = TextView(this).apply {
            text = "Đang tải số dư..."
            setTextColor(0xFF888888.toInt())
            setPadding(0,10,0,10)
        }

        val warningTv = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(0,10,0,0)
            visibility = View.GONE
        }

        val feeRates = try { walletManager.getFeeRates() } catch (_: Exception) { FeeRates(5, 10, 20) }
        val feeGroup = RadioGroup(this)
        val rSlow = RadioButton(this).apply { id = 1; text = "Chậm (${feeRates.slow} sat/vB)" }
        val rNormal = RadioButton(this).apply { id = 2; text = "Thường (${feeRates.normal} sat/vB)"; isChecked = true }
        val rFast = RadioButton(this).apply { id = 3; text = "Nhanh (${feeRates.fast} sat/vB)" }
        val rCustom = RadioButton(this).apply { id = 4; text = "Tùy chỉnh" }
        val customFeeInput = EditText(this).apply {
            hint = "1-500 sat/vB"
            inputType = InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
            setText("10")
        }
        feeGroup.addView(rSlow); feeGroup.addView(rNormal); feeGroup.addView(rFast); feeGroup.addView(rCustom)

        val feeEstimateTv = TextView(this).apply { text = "Ước tính phí: -"; setPadding(0,20,0,0) }
        val totalEstimateTv = TextView(this).apply { text = "Tổng (gửi + phí): -" }

        layout.addView(toInput)
        layout.addView(scanBtn)
        layout.addView(amountInput)
        layout.addView(balanceTv)
        layout.addView(warningTv)
        layout.addView(TextView(this).apply { text = "Chọn phí mạng:"; setPadding(0,20,0,0) })
        layout.addView(feeGroup)
        layout.addView(customFeeInput)
        layout.addView(feeEstimateTv)
        layout.addView(totalEstimateTv)

        var priceUsd = 60000.0
        var currentBalance = 0.0
        var isSpvSynced = false

        val dialog = AlertDialog.Builder(this)
            .setTitle("Gửi BTC")
            .setView(layout)
            .setPositiveButton("Tiếp tục", null)
            .setNegativeButton("Hủy", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false

            fun updateUI() {
                val to = toInput.text.toString().trim()
                val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                val feeRate = when (feeGroup.checkedRadioButtonId) {
                    1 -> feeRates.slow
                    3 -> feeRates.fast
                    4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1,500) ?: 10
                    else -> feeRates.normal
                }

                if (!isSpvSynced) {
                    btn.isEnabled = false
                    warningTv.text = "⚠️ Ví đang đồng bộ SPV, vui lòng đợi hoàn tất."
                    warningTv.visibility = View.VISIBLE
                    feeEstimateTv.text = ""
                    totalEstimateTv.text = ""
                    return
                }

                if (to.isEmpty() || to.length < 26 || amt <= 0.0) {
                    btn.isEnabled = false
                    warningTv.visibility = View.GONE
                    feeEstimateTv.text = "Nhập địa chỉ và số tiền hợp lệ"
                    totalEstimateTv.text = ""
                    return
                }

                if (!walletManager.isValidAddress(to)) {
                    btn.isEnabled = false
                    warningTv.text = "⚠️ Địa chỉ BTC không hợp lệ"
                    warningTv.visibility = View.VISIBLE
                    feeEstimateTv.text = ""
                    totalEstimateTv.text = ""
                    return
                }

                try {
                    val estFee = walletManager.estimateFee(to, amt, feeRate)
                    val total = amt + estFee
                    val feeUsd = estFee * priceUsd
                    val totalUsd = total * priceUsd

                    feeEstimateTv.text = "Phí: ${"%.8f".format(estFee)} BTC (~$${"%.2f".format(feeUsd)})"
                    totalEstimateTv.text = "Tổng: ${"%.8f".format(total)} BTC (~$${"%.2f".format(totalUsd)})"
                    rSlow.text = "Chậm (${feeRates.slow} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.slow) * priceUsd)}"
                    rNormal.text = "Thường (${feeRates.normal} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.normal) * priceUsd)}"
                    rFast.text = "Nhanh (${feeRates.fast} sat/vB) ~ $${"%.2f".format(walletManager.estimateFee(to, amt, feeRates.fast) * priceUsd)}"
                    rCustom.text = "Tùy chỉnh (${feeRate} sat/vB) ~ $${"%.2f".format(estFee * priceUsd)}"

                    if (currentBalance <= total) {
                        btn.isEnabled = false
                        warningTv.text = "⚠️ Số dư không đủ (cần > ${"%.8f".format(total)} BTC)"
                        warningTv.visibility = View.VISIBLE
                    } else {
                        btn.isEnabled = true
                        warningTv.visibility = View.GONE
                    }
                    btn.alpha = if (btn.isEnabled) 1f else 0.5f
                } catch (e: Exception) {
                    btn.isEnabled = false
                    warningTv.text = "Lỗi: ${e.message}"
                    warningTv.visibility = View.VISIBLE
                }
            }

            Thread {
                currentBalance = walletManager.getBalance()
                isSpvSynced = walletManager.isWalletSynced()
                runOnUiThread {
                    balanceTv.text = "Số dư: ${"%.8f".format(currentBalance)} BTC"
                    updateUI()
                }
            }.start()

            feeGroup.setOnCheckedChangeListener { _, _ ->
                customFeeInput.visibility = if (feeGroup.checkedRadioButtonId == 4) View.VISIBLE else View.GONE
                updateUI()
            }
            val watcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateUI() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            toInput.addTextChangedListener(watcher)
            amountInput.addTextChangedListener(watcher)
            customFeeInput.addTextChangedListener(watcher)

            val spvHandler = Handler(Looper.getMainLooper())
            val spvRunnable = object : Runnable {
                override fun run() {
                    if (dialog.isShowing) {
                        val synced = walletManager.isWalletSynced()
                        if (synced != isSpvSynced) {
                            isSpvSynced = synced
                            updateUI()
                        }
                        spvHandler.postDelayed(this, 2000)
                    }
                }
            }
            spvHandler.post(spvRunnable)

            btn.setOnClickListener {
                spvHandler.removeCallbacks(spvRunnable)
                val to = toInput.text.toString().trim()
                val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                if (amt <= 0.0) {
                    toast("Số BTC không hợp lệ")
                    return@setOnClickListener
                }
                if (!walletManager.isValidAddress(to)) {
                    toast("Địa chỉ BTC không hợp lệ")
                    return@setOnClickListener
                }
                val fee = when (feeGroup.checkedRadioButtonId) {
                    1 -> feeRates.slow
                    3 -> feeRates.fast
                    4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1,500) ?: 10
                    else -> feeRates.normal
                }
                val estFee = walletManager.estimateFee(to, amt, fee)
                dialog.dismiss()
                confirmSend(to, amt, fee, estFee)
            }
            updateUI()
        }
        dialog.show()
    }

    private fun confirmSend(to: String, amt: Double, feeRate: Int, estFee: Double) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val summary = TextView(this).apply {
            text = "Gửi: $amt BTC\nĐến: $to\nPhí: ~$estFee BTC\nTổng: ${amt + estFee} BTC"
            setPadding(0,0,0,20)
        }
        val passInput = EditText(this).apply {
            hint = "Nhập mật khẩu để xác nhận"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(summary)
        layout.addView(passInput)
        AlertDialog.Builder(this)
            .setTitle("Xác nhận gửi")
            .setView(layout)
            .setPositiveButton("Xác nhận") { _, _ ->
                val pass = passInput.text.toString()
                val id = walletManager.getActiveId() ?: return@setPositiveButton
                if (!walletManager.unlock(id, pass)) {
                    toast("Sai mật khẩu")
                    return@setPositiveButton
                }
                val delayLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40,30,40,30)
                }
                val tv = TextView(this).apply { text = "Đang chuẩn bị gửi sau 60 giây..."; gravity = Gravity.CENTER }
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 60
                    progress = 60
                }
                val countdown = TextView(this).apply { text = "60s"; gravity = Gravity.CENTER; textSize = 18f }
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
                                        toast("Đã gửi! TXID: ${txid.take(8)}...")
                                        refreshWalletFromSPV()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Lỗi gửi: ${e.message}") }
                                }
                            }.start()
                        }
                    }
                }
                delayDialog = AlertDialog.Builder(this)
                    .setTitle("Delay bảo mật")
                    .setView(delayLayout)
                    .setCancelable(false)
                    .setNegativeButton("Hủy giao dịch") { _, _ ->
                        handler.removeCallbacks(runnable)
                        delayDialog.dismiss()
                        toast("Đã hủy gửi")
                    }
                    .create()
                delayDialog.show()

                handler.postDelayed(runnable, 1000)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showSettings() {
        val items = arrayOf("👁 Xem seed phrase", "🔑 Đổi mật khẩu", "✏️ Đổi tên ví", "🗑 Xóa ví vĩnh viễn", "🔒 Khóa ví ngay", "ℹ️ Thông tin")
        AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setItems(items) { _, w ->
                when(w) {
                    0 -> showSeedDialog()
                    1 -> showChangePassDialog()
                    2 -> showRenameDialog()
                    3 -> showDeleteDialog()
                    4 -> {
                        walletManager.lock()
                        showUnlockDialog()
                    }
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
            .setTitle("Nhập mật khẩu để xem seed")
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
                        .setTitle("⚠️ KHÔNG CHIA SẺ SEED")
                        .setView(tv)
                        .setPositiveButton("Copy 30s") { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("seed", seed))
                            handler.postDelayed({ cm.clearPrimaryClip() }, 30000)
                        }
                        .setNegativeButton("Đóng", null)
                        .show()
                } else toast("Sai mật khẩu")
            }
            .show()
    }

    private fun showChangePassDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30)
        }
        val oldP = EditText(this).apply {
            hint = "Mật khẩu cũ"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newP = EditText(this).apply {
            hint = "Mật khẩu mới ≥8"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmP = EditText(this).apply {
            hint = "Nhập lại mật khẩu mới"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(oldP)
        layout.addView(newP)
        layout.addView(confirmP)
        AlertDialog.Builder(this)
            .setTitle("Đổi mật khẩu")
            .setView(layout)
            .setPositiveButton("Đổi") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                val newPass = newP.text.toString()
                val confirm = confirmP.text.toString()
                if (newPass.length < 8) {
                    toast("Mật khẩu mới phải ≥8 ký tự")
                    return@setPositiveButton
                }
                if (newPass != confirm) {
                    toast("Mật khẩu mới không khớp")
                    return@setPositiveButton
                }
                if (walletManager.changePassword(oldP.text.toString(), newPass))
                    toast("Đã đổi thành công")
                else toast("Sai mật khẩu cũ")
            }
            .show()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            hint = "Tên ví mới"
            setText(walletManager.getActive()?.name?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Đổi tên")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                walletManager.rename(input.text.toString())
                walletNameText.text = input.text.toString()
                toast("Đã đổi tên")
            }
            .show()
    }

    private fun showDeleteDialog() {
        val pass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("XÓA VĨNH VIỄN")
            .setMessage("Nhập mật khẩu để xóa. Không thể khôi phục nếu không có seed!")
            .setView(pass)
            .setPositiveButton("XÓA") { _, _ ->
                val id = walletManager.getActiveId()?: return@setPositiveButton
                if (walletManager.unlock(id, pass.text.toString())) {
                    walletManager.delete(id)
                    showWelcome()
                    toast("Đã xóa")
                } else toast("Sai pass")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle("iBTC v4.7")
            .setMessage("Build: 2026-05-29\n• SPV 100%\n• Foreground service\n• Không API")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}