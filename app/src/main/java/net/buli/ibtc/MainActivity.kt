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
import org.json.JSONArray
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

    private var currentBalanceBtc = 0.0
    private var currentBtcPrice = 0.0

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            val cleanAddress = raw.removePrefix("bitcoin:").substringBefore("?")
            pendingAddressInput?.setText(cleanAddress)
            toast("Đã quét: ${cleanAddress.take(10)}...")
        }
    }

    private fun getColorForProgress(progress: Int): Int {
        val p = progress.coerceIn(0, 100)
        val r: Float
        val g: Float
        val b: Float
        when {
            p <= 50 -> {
                val factor = p / 50f
                r = factor * 255f
                g = 255f
                b = 0f
            }
            p <= 75 -> {
                val factor = (p - 50) / 25f
                r = 255f
                g = 255f - factor * (255f - 165f)
                b = 0f
            }
            else -> {
                val factor = (p - 75) / 25f
                r = 255f
                g = 165f - factor * 165f
                b = 0f
            }
        }
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
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
                        spvProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(pct))
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
                if (viewsReady) updatePriceUI(price)
            }
        }.start()
    }

    private fun updatePriceUI(price: Double) {
        currentBtcPrice = price
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

        val currentUsd = currentBalanceBtc * price
        var dailyUsd = prefsDaily.getFloat("daily_usd", -1f).toDouble()
        if (dailyTimestamp != todayStart || dailyUsd < 0) {
            dailyUsd = currentUsd
            prefsDaily.edit().putFloat("daily_usd", dailyUsd.toFloat()).apply()
        }

        val usdChange = currentUsd - dailyUsd
        val usdChangePercent = when {
            dailyUsd == 0.0 && currentUsd > 0 -> 100.0
            dailyUsd == 0.0 && currentUsd == 0.0 -> 0.0
            else -> (usdChange / dailyUsd) * 100
        }
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

    private fun fetchBalanceAndTxFromApi(address: String, callback: (Double, List<TransactionInfo>) -> Unit) {
        Thread {
            try {
                val url = URL("https://blockchain.info/address/$address?format=json")
                val json = url.openStream().bufferedReader().readText()
                val obj = JSONObject(json)
                val finalBalance = obj.getLong("final_balance") / 1e8
                val txsArray = obj.getJSONArray("txs")
                val transactions = mutableListOf<TransactionInfo>()
                for (i in 0 until txsArray.length()) {
                    val txObj = txsArray.getJSONObject(i)
                    val txHash = txObj.getString("hash")
                    val time = txObj.getLong("time") * 1000
                    val inputs = txObj.getJSONArray("inputs")
                    val outputs = txObj.getJSONArray("out")
                    var amount = 0.0
                    var type = ""
                    var isSend = false
                    for (j in 0 until inputs.length()) {
                        val prevOut = inputs.getJSONObject(j).optJSONObject("prev_out")
                        if (prevOut != null && prevOut.optString("addr") == address) {
                            isSend = true
                            break
                        }
                    }
                    if (isSend) {
                        var sent = 0.0
                        for (j in 0 until outputs.length()) {
                            val out = outputs.getJSONObject(j)
                            if (out.optString("addr") != address) {
                                sent += out.getLong("value") / 1e8
                            }
                        }
                        amount = sent
                        type = "SEND"
                    } else {
                        var received = 0.0
                        for (j in 0 until outputs.length()) {
                            val out = outputs.getJSONObject(j)
                            if (out.optString("addr") == address) {
                                received += out.getLong("value") / 1e8
                            }
                        }
                        amount = received
                        type = "RECEIVE"
                    }
                    if (amount > 0) {
                        transactions.add(TransactionInfo(txHash, amount, type, Date(time)))
                    }
                }
                transactions.sortByDescending { it.time }
                callback(finalBalance, transactions)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(0.0, emptyList())
            }
        }.start()
    }

    private fun refreshWalletFromSPV() {
        runOnUiThread {
            if (viewsReady) {
                addressText.text = "Địa chỉ: ${walletManager.getAddress()}"
            }
        }
        fetchAndUpdatePrice()

        val isSynced = walletManager.isWalletSynced()

        if (!isSynced) {
            runOnUiThread {
                if (viewsReady) spvStatusText.text = "SPV: Đang đồng bộ (hiển thị dữ liệu từ API)..."
            }
            val address = walletManager.getAddress()
            if (address.isNotEmpty()) {
                fetchBalanceAndTxFromApi(address) { balance, transactions ->
                    runOnUiThread {
                        if (viewsReady) {
                            currentBalanceBtc = balance
                            balanceText.text = String.format(Locale.US, "%.8f BTC", balance)
                            if (currentBtcPrice > 0) updatePriceUI(currentBtcPrice)
                            val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1, transactions.map { "" }) {
                                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                    val view = super.getView(position, convertView, parent)
                                    val tx = transactions[position]
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
                        }
                    }
                }
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
                    currentBalanceBtc = bal
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    if (currentBtcPrice > 0) updatePriceUI(currentBtcPrice)

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
                val url = URL("https://mempool.space/api/v1/blocks")
                val json = url.openStream().bufferedReader().readText()
                val jsonArray = JSONArray(json)
                if (jsonArray.length() == 0) throw Exception("Empty array")
                val obj = jsonArray.getJSONObject(0)
                val height = obj.getInt("height")
                val lastTime = obj.getLong("timestamp")
                val nextHeight = height + 1
                val elapsed = (System.currentTimeMillis() / 1000 - lastTime).coerceAtLeast(0)
                var percent = ((elapsed * 100) / 600).toInt()
                if (percent > 100) percent = 100
                val remain = 600 - elapsed
                runOnUiThread {
                    if (viewsReady) {
                        blockProgressBar.progress = percent
                        blockProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(percent))
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
                e.printStackTrace()
                runOnUiThread {
                    if (viewsReady) {
                        blockText.text = "Lỗi pool - tự thử lại"
                        blockProgressBar.progress = 0
                        blockProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(0))
                    }
                }
            }
        }.start()
    }

    private fun fetchBtcStats() {
        Thread {
            try {
                val height = URL("https://mempool.space/api/blocks/tip/height").readText().trim().toInt()

                val halvings = height / 210000
                val totalHalvings = 32
                val halvingProgress = (halvings.toFloat() / totalHalvings * 100).toInt()

                val reward = 50.0 / Math.pow(2.0, halvings.toDouble())
                val rewardPct = ((reward / 50.0) * 100).toInt()

                val nextHalving = (halvings + 1) * 210000
                val blocksToHalving = nextHalving - height
                val daysRemaining = if (blocksToHalving > 0) blocksToHalving / 144 else 0
                val halvingProgressValue = if (blocksToHalving <= 0) 100 else ((1 - blocksToHalving / 210000.0) * 100).toInt()
                val nextHalvingNumber = halvings + 1
                val halvingText = if (blocksToHalving <= 0) {
                    "Halving #$nextHalvingNumber đã diễn ra (100%)"
                } else {
                    "Halving #$nextHalvingNumber: còn $blocksToHalving blocks (~$daysRemaining ngày) - ${halvingProgressValue}%"
                }

                val totalSats = URL("https://blockchain.info/q/totalbc").readText().trim().toLong()
                val totalMined = totalSats / 100000000.0
                val minedPct = ((totalMined / 21000000.0) * 100).toInt()

                val diffJson = URL("https://mempool.space/api/v1/difficulty-adjustment").readText()
                val diffObj = JSONObject(diffJson)
                val diffProgress = diffObj.getDouble("progressPercent").toFloat()

                val mempoolJson = URL("https://mempool.space/api/mempool").readText()
                val mempoolObj = JSONObject(mempoolJson)
                val mempoolCount = mempoolObj.getInt("count")
                val mempoolPct = (mempoolCount / 300000.0 * 100).toInt().coerceAtMost(100)

                val feesJson = URL("https://mempool.space/api/v1/fees/recommended").readText()
                val feesObj = JSONObject(feesJson)
                val feeFast = feesObj.getInt("fastestFee")
                val feePct = feeFast.coerceAtMost(100)

                val hashJson = URL("https://mempool.space/api/v1/mining/hashrate/1w").readText()
                val hashObj = JSONObject(hashJson)
                val currentHash = hashObj.getDouble("currentHashrate")
                val hashEh = currentHash / 1e18

                val blocksToday = height % 144
                val todayPct = (blocksToday * 100 / 144)
                val heightPct = height % 100

                runOnUiThread {
                    if (viewsReady) {
                        statBars["mined"]?.apply {
                            progress = minedPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(minedPct))
                        }
                        statTexts["mined"]?.text = "Đã khai thác: ${String.format("%.2f", totalMined)} / 21M BTC ($minedPct%)"

                        statBars["halvingCount"]?.apply {
                            progress = halvingProgress
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(halvingProgress))
                        }
                        statTexts["halvingCount"]?.text = "Halving count: $halvings / $totalHalvings ($halvingProgress%)"

                        statBars["halving"]?.apply {
                            progress = halvingProgressValue
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(halvingProgressValue))
                        }
                        statTexts["halving"]?.text = halvingText

                        statBars["reward"]?.apply {
                            progress = rewardPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(rewardPct))
                        }
                        statTexts["reward"]?.text = "Thưởng block: $reward BTC (ban đầu 50 BTC)"

                        statBars["diff"]?.apply {
                            progress = diffProgress.toInt()
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(diffProgress.toInt()))
                        }
                        statTexts["diff"]?.text = "Difficulty adj: ${String.format("%.1f", diffProgress)}%"

                        statBars["mempool"]?.apply {
                            progress = mempoolPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(mempoolPct))
                        }
                        statTexts["mempool"]?.text = "Mempool: $mempoolCount tx chờ"

                        statBars["hash"]?.apply {
                            progress = 70
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(70))
                        }
                        statTexts["hash"]?.text = "Hashrate: ${String.format("%.0f", hashEh)} EH/s"

                        statBars["fee"]?.apply {
                            progress = feePct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(feePct))
                        }
                        statTexts["fee"]?.text = "Phí nhanh: $feeFast sat/vB"

                        statBars["today"]?.apply {
                            progress = todayPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(todayPct))
                        }
                        statTexts["today"]?.text = "Block hôm nay: $blocksToday / 144"

                        statBars["supply"]?.apply {
                            progress = minedPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(minedPct))
                        }
                        statTexts["supply"]?.text = "Cung lưu thông: ${String.format("%.2f", totalMined / 1000000)}M BTC"

                        statBars["height"]?.apply {
                            progress = heightPct
                            progressTintList = android.content.res.ColorStateList.valueOf(getColorForProgress(heightPct))
                        }
                        statTexts["height"]?.text = "Block height: #$height"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
        BitcoinChartService.fetchKlines("1h", 24) { klines ->
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
        val isDark = (resources.configuration.ui