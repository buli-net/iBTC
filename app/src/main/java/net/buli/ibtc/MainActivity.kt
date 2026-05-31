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
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import org.json.JSONObject
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
    private var viewsReady = false

    private lateinit var sparkline: LineChart

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            val cleanAddress = raw.removePrefix("bitcoin:").substringBefore("?")
            pendingAddressInput?.setText(cleanAddress)
            toast("Đã quét: ${cleanAddress.take(10)}...")
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
        if (viewsReady) {
            SyncService.getInstance()?.setProgressCallback { pct, txt ->
                runOnUiThread {
                    if (viewsReady) {
                        spvStatusText.text = "SPV: $txt"
                        spvProgressBar.progress = pct
                        fetchAndUpdatePrice()
                    }
                }
            }
        }
        if (walletManager.isLocked()) {
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
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_:Exception) {}
        try { walletManager.stop() } catch (_: Exception) {}
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

    private fun getTodayUtcStart(): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun fetchAndUpdatePrice() {
        Thread {
            val price = walletManager.price()
            runOnUiThread {
                if (viewsReady) {
                    updatePriceUI(price)
                }
            }
        }.start()
    }

    private fun updatePriceUI(price: Double) {
        val hasPrice = price > 0
        if (!hasPrice) {
            rateText.text = "BTC ---"
            rateText.setTextColor(Color.GRAY)
            balanceUsdText.text = "≈ $---"
            balanceUsdText.setTextColor(Color.GRAY)
            return
        }

        val prefsDaily = getSharedPreferences("daily_mark", Context.MODE_PRIVATE)
        val todayStart = getTodayUtcStart()
        var dailyPrice = prefsDaily.getFloat("daily_price", -1f).toDouble()
        var dailyTimestamp = prefsDaily.getLong("daily_timestamp", 0L)

        if (dailyTimestamp != todayStart || dailyPrice < 0) {
            dailyPrice = price
            prefsDaily.edit()
                .putFloat("daily_price", dailyPrice.toFloat())
                .putLong("daily_timestamp", todayStart)
                .apply()
        }

        val priceChange = price - dailyPrice
        val priceChangePercent = if (dailyPrice > 0) (priceChange / dailyPrice) * 100 else 0.0
        val priceArrow = when {
            priceChange > 0.01 -> "▲"
            priceChange < -0.01 -> "▼"
            else -> "●"
        }
        val priceColor = when {
            priceChange > 0.01 -> Color.parseColor("#00C853")
            priceChange < -0.01 -> Color.parseColor("#D50000")
            else -> Color.parseColor("#F7931A")
        }

        rateText.setTextColor(priceColor)
        rateText.text = String.format(Locale.US, "BTC $%,.2f %s %+.2f%% (%+.2f$)", price, priceArrow, priceChangePercent, priceChange)

        val bal = if (walletManager.isWalletSynced()) walletManager.getBalance() else 0.0
        val currentUsd = bal * price
        var dailyUsd = prefsDaily.getFloat("daily_usd", -1f).toDouble()
        if (dailyTimestamp != todayStart || dailyUsd < 0) {
            dailyUsd = currentUsd
            prefsDaily.edit().putFloat("daily_usd", dailyUsd.toFloat()).apply()
        }
        val usdChange = currentUsd - dailyUsd
        val usdChangePercent = if (dailyUsd > 0) (usdChange / dailyUsd) * 100 else 0.0
        val usdArrow = when {
            usdChange > 0.01 -> "▲"
            usdChange < -0.01 -> "▼"
            else -> "●"
        }
        val usdColor = when {
            usdChange > 0.01 -> Color.parseColor("#00C853")
            usdChange < -0.01 -> Color.parseColor("#D50000")
            else -> Color.parseColor("#F7931A")
        }
        balanceUsdText.setTextColor(usdColor)
        balanceUsdText.text = String.format(Locale.US, "≈ $%,.2f %s %+.2f%% (%+.2f$)", currentUsd, usdArrow, usdChangePercent, usdChange)
    }

    private fun refreshWalletFromSPV() {
        runOnUiThread {
            if (viewsReady) {
                addressText.text = "Địa chỉ: ${walletManager.getAddress()}"
            }
        }

        fetchAndUpdatePrice()

        if (!walletManager.isWalletSynced()) {
            runOnUiThread {
                if (viewsReady) spvStatusText.text = "SPV: Đang đồng bộ blockchain..."
            }
            return
        }

        if (isSyncing) return
        isSyncing = true
        runOnUiThread {
            if (viewsReady) spvStatusText.text = "SPV: Đang cập nhật số dư..."
        }
        Thread {
            try {
                val bal = walletManager.getBalance()
                val txs = walletManager.getTransactions()
                runOnUiThread {
                    if (!viewsReady) return@runOnUiThread
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    val price = walletManager.price()
                    if (price > 0) {
                        val prefsDaily = getSharedPreferences("daily_mark", Context.MODE_PRIVATE)
                        val todayStart = getTodayUtcStart()
                        var dailyUsd = prefsDaily.getFloat("daily_usd", -1f).toDouble()
                        if (prefsDaily.getLong("daily_timestamp", 0L) != todayStart || dailyUsd < 0) {
                            dailyUsd = bal * price
                            prefsDaily.edit().putFloat("daily_usd", dailyUsd.toFloat()).apply()
                        }
                        val currentUsd = bal * price
                        val usdChange = currentUsd - dailyUsd
                        val usdChangePercent = if (dailyUsd > 0) (usdChange / dailyUsd) * 100 else 0.0
                        val usdArrow = when {
                            usdChange > 0.01 -> "▲"
                            usdChange < -0.01 -> "▼"
                            else -> "●"
                        }
                        val usdColor = when {
                            usdChange > 0.01 -> Color.parseColor("#00C853")
                            usdChange < -0.01 -> Color.parseColor("#D50000")
                            else -> Color.parseColor("#F7931A")
                        }
                        balanceUsdText.setTextColor(usdColor)
                        balanceUsdText.text = String.format(Locale.US, "≈ $%,.2f %s %+.2f%% (%+.2f$)", currentUsd, usdArrow, usdChangePercent, usdChange)
                    }

                    val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1, txs.map { "" }) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent)
                            val tx = txs[position]
                            val text1 = view.findViewById<TextView>(android.R.id.text1)
                            val text2 = view.findViewById<TextView>(android.R.id.text2)
                            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            val mainColor = if (isDark) Color.WHITE else Color.BLACK
                            text1.setTextColor(mainColor)
                            text1.text = "${if (tx.type == "RECEIVE") "⬇" else "⬆"} ${tx.type} ${String.format(Locale.US, "%.8f", tx.amount)} BTC"
                            text2.text = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(tx.time) + " • " + tx.txId.take(12)
                            text2.setTextColor(mainColor)
                            text2.textSize = 11f
                            return view
                        }
                    }
                    txListView.adapter = adapter
                    isSyncing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (viewsReady) spvStatusText.text = "SPV: Lỗi cập nhật"
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
                if (walletManager.getActive() != null && !isFinishing && !isDestroyed) {
                    refreshWalletFromSPV()
                }
                if (!isFinishing && !isDestroyed) {
                    handler.postDelayed(this, 45000)
                }
            }
        }, 45000)
    }

    private fun fetchBlockUpdate() {
        Thread {
            try {
                val json = URL("https://mempool.space/api/v1/blocks").openStream().bufferedReader().readText()
                // Sử dụng JSONObject để parse chính xác
                val obj = JSONObject(json.substringAfter("[").substringBefore("]")).let { 
                    // Lấy block đầu tiên
                    JSONObject(json.substringAfter("[").substringBefore(","))
                }
                val height = obj.getInt("height")
                val lastTime = obj.getLong("timestamp")
                val nextHeight = height + 1
                val elapsed = (System.currentTimeMillis() / 1000 - lastTime).coerceAtLeast(0)
                val percent = ((elapsed * 100) / 600).toInt()
                val remain = 600 - elapsed
                runOnUiThread {
                    if (viewsReady) {
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
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (viewsReady) {
                        blockText.text = "Lỗi pool - tự thử lại"
                        blockProgressBar.progress = 0
                    }
                }
            }
        }.start()
    }

    private fun fetchBtcStats() {
        Thread {
            try {
                // Lấy block height hiện tại từ mempool.space
                val height = URL("https://mempool.space/api/blocks/tip/height").readText().trim().toInt()
                val halvings = height / 210000
                val reward = 50.0 / Math.pow(2.0, halvings.toDouble())
                val nextHalving = (halvings + 1) * 210000
                val blocksToHalving = nextHalving - height

                // Tổng số BTC đã khai thác
                val totalSats = URL("https://blockchain.info/q/totalbc").readText().trim().toLong()
                val totalMined = totalSats / 100000000.0

                // Difficulty adjustment
                val diffJson = URL("https://mempool.space/api/v1/difficulty-adjustment").readText()
                val diffObj = JSONObject(diffJson)
                val diffProgress = diffObj.getDouble("progressPercent").toFloat()

                // Mempool size
                val mempoolJson = URL("https://mempool.space/api/mempool").readText()
                val mempoolObj = JSONObject(mempoolJson)
                val mempoolCount = mempoolObj.getInt("count")

                // Fee recommendation
                val feesJson = URL("https://mempool.space/api/v1/fees/recommended").readText()
                val feesObj = JSONObject(feesJson)
                val feeFast = feesObj.getInt("fastestFee")

                // Hashrate (lấy từ API v1/mining/hashrate/1w)
                val hashJson = URL("https://mempool.space/api/v1/mining/hashrate/1w").readText()
                val hashObj = JSONObject(hashJson)
                val currentHash = hashObj.getDouble("currentHashrate")
                val hashEh = currentHash / 1e18

                val blocksToday = height % 144

                runOnUiThread {
                    if (viewsReady) {
                        val minedPct = ((totalMined / 21000000.0) * 100).toInt()
                        statBars["mined"]?.progress = minedPct
                        statTexts["mined"]?.text = "Đã khai thác: ${String.format("%.2f", totalMined)} / 21M BTC ($minedPct%)"
                        statTexts["halving"]?.text = "Halving #${halvings + 1}: còn $blocksToHalving blocks (~${blocksToHalving / 144} ngày)"
                        statTexts["reward"]?.text = "Thưởng block: $reward BTC (ban đầu 50 BTC)"
                        statTexts["diff"]?.text = "Difficulty adj: ${String.format("%.1f", diffProgress)}%"
                        statTexts["mempool"]?.text = "Mempool: $mempoolCount tx chờ"
                        statTexts["hash"]?.text = "Hashrate: ${String.format("%.0f", hashEh)} EH/s"
                        statTexts["fee"]?.text = "Phí nhanh: $feeFast sat/vB"
                        statTexts["today"]?.text = "Block hôm nay: $blocksToday / 144"
                        statTexts["supply"]?.text = "Cung lưu thông: ${String.format("%.2f", totalMined / 1000000)}M BTC"
                        statTexts["height"]?.text = "Block height: #$height"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Nếu lỗi, không cập nhật, giữ nguyên dữ liệu cũ
            }
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

    private fun addStat(key: String, label: String, color: Int) {
        val tv = TextView(this).apply {
            text = label
            textSize = POOL_FONT
            setTextColor(color)
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

    private fun setupSparkline() {
        sparkline = LineChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(120), dpToPx(40))
            setTouchEnabled(false)
            setDragEnabled(false)
            setScaleEnabled(false)
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            xAxis.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun updateSparkline(closePrices: List<Float>) {
        if (!viewsReady) return
        if (closePrices.isEmpty()) return
        val entries = closePrices.mapIndexed { index, price -> Entry(index.toFloat(), price) }
        val firstPrice = closePrices.first()
        val lastPrice = closePrices.last()
        val trendColor = when {
            lastPrice > firstPrice -> Color.parseColor("#00C853")
            lastPrice < firstPrice -> Color.parseColor("#D50000")
            else -> Color.parseColor("#F7931A")
        }
        val dataSet = LineDataSet(entries, "").apply {
            color = trendColor
            setCircleColor(Color.TRANSPARENT)
            lineWidth = 2f
            setDrawValues(false)
            setDrawCircles(false)
            setDrawFilled(true)
            fillColor = trendColor
            fillAlpha = 50
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        sparkline.data = LineData(dataSet)
        sparkline.invalidate()
    }

    private fun fetchSparkline() {
        BitcoinChartService.fetchKlines("1h", 30) { klines ->
            if (klines != null && klines.isNotEmpty()) {
                val closePrices = klines.map { it.close.toFloat() }
                runOnUiThread { updateSparkline(closePrices) }
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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

        val balanceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        balanceText = TextView(this).apply {
            text = "0.00000000 BTC"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mainColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        balanceRow.addView(balanceText)
        setupSparkline()
        balanceRow.addView(sparkline)

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
            setTextColor(mainColor)
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
            setTextColor(mainColor)
            setPadding(0, 10, 0, 10)
        }
        blockText = TextView(this).apply {
            text = "Đang kết nối mempool..."
            textSize = POOL_FONT
            setTextColor(mainColor)
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
        rootLayout.addView(balanceRow)
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

        addStat("mined", "Đã khai thác", mainColor)
        addStat("halving", "Halving", mainColor)
        addStat("reward", "Phần thưởng", mainColor)
        addStat("diff", "Difficulty", mainColor)
        addStat("mempool", "Mempool", mainColor)
        addStat("hash", "Hashrate", mainColor)
        addStat("fee", "Phí", mainColor)
        addStat("today", "Hôm nay", mainColor)
        addStat("supply", "Cung", mainColor)
        addStat("height", "Height", mainColor)

        btnReceive.setOnClickListener { showReceiveDialog() }
        btnSend.setOnClickListener { showSendDialog() }
        btnRefresh.setOnClickListener {
            refreshWalletFromSPV()
            fetchBlockUpdate()
            fetchBtcStats()
            SyncService.getInstance()?.refreshProgress()
            fetchSparkline()
            toast("Đang làm mới...")
        }
        btnSettings.setOnClickListener { showSettings() }

        walletManager.onProgress { pct, txt ->
            runOnUiThread {
                if (viewsReady) {
                    spvStatusText.text = "SPV: $txt"
                    spvProgressBar.progress = pct
                }
            }
        }
        SyncService.getInstance()?.setProgressCallback { pct, txt ->
            runOnUiThread {
                if (viewsReady) {
                    spvStatusText.text = "SPV: $txt"
                    spvProgressBar.progress = pct
                    fetchAndUpdatePrice()
                }
            }
        }

        viewsReady = true
        refreshWalletFromSPV()
        startAutoRefresh()
        startBlockProgress()
        fetchSparkline()
        fetchAndUpdatePrice()
    }

    // Các hàm dialog (giữ nguyên code cũ)
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

    private fun showSendDialog() { /* giữ nguyên code cũ (đã có) */ }
    private fun confirmSend(to: String, amt: Double, feeRate: Int, estFee: Double) { /* giữ nguyên */ }
    private fun showSettings() { /* giữ nguyên */ }
    private fun showSeedDialog() { /* giữ nguyên */ }
    private fun showChangePassDialog() { /* giữ nguyên */ }
    private fun showRenameDialog() { /* giữ nguyên */ }
    private fun showDeleteDialog() { /* giữ nguyên */ }
    private fun showInfo() { /* giữ nguyên */ }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}