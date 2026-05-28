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
                if (walletManager.getActive() != null && !isSyncing) {
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
                val height = Regex("\"height\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0
                val lastTime = Regex("\"timestamp\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
                val nextHeight = height + 1
                val elapsed = (System.currentTimeMillis()/1000 - lastTime).coerceAtLeast(0)
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

    private fun fetchBtcPriceUsd(callback: (Double) -> Unit) {
        Thread {
            try {
                val json = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd").readText()
                val price = Regex("\"usd\":([\\d.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 60000.0
                runOnUiThread { callback(price) }
            } catch (_: Exception) { runOnUiThread { callback(60000.0) } }
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
                    // ... các stat khác giữ nguyên ...
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
            text = label; textSize = POOL_FONT; setTextColor(Color.GRAY); setPadding(0,8,0,2); typeface = Typeface.DEFAULT
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; scaleY = 0.6f
        }
        statsContainer.addView(tv); statsContainer.addView(pb)
        statTexts[key] = tv; statBars[key] = pb
    }

    private fun showWelcome() { /* giữ nguyên như cũ */ }
    private fun showCreateDialog() { /* giữ nguyên */ }
    private fun showImportDialog() { /* giữ nguyên */ }
    private fun showUnlockDialog() { /* giữ nguyên */ }

    private fun showMainWallet() {
        val id = walletManager.getActiveId()
        if (id != null) WalletKitService.start(this, id)
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val mainColor = if (isDark) Color.WHITE else Color.BLACK
        val subColor = if (isDark) Color.LTGRAY else Color.DKGRAY

        walletNameText = TextView(this).apply {
            text = walletManager.getActive()?.name ?: "Ví"
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(mainColor)
        }
        balanceText = TextView(this).apply {
            text = "0.00000000 BTC"; textSize = 32f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(mainColor); setPadding(0,10,0,0)
        }
        balanceUsdText = TextView(this).apply { text = "≈ $0.00"; textSize = 16f; setTextColor(subColor) }
        rateText = TextView(this).apply { text = "BTC $0.00"; textSize = 14f; setTextColor(Color.GRAY) }
        syncText = TextView(this).apply { text = "Chưa đồng bộ"; textSize = 13f; setTextColor(subColor) }
        syncProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        addressText = TextView(this).apply {
            textSize = 12f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(subColor); setPadding(0,10,0,10)
        }
        blockText = TextView(this).apply { text = "Đang kết nối mempool..."; textSize = POOL_FONT; setTextColor(subColor); setPadding(0,8,0,2) }
        blockProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; scaleY = 0.7f }

        val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnReceive = Button(this).apply { text = "⬇ Nhận"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 } }
        val btnSend = Button(this).apply { text = "⬆ Gửi"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 } }
        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnRefresh = Button(this).apply { text = "⟳ Làm mới"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 } }
        val btnSettings = Button(this).apply { text = "⚙ Cài đặt"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 } }
        btnRow1.addView(btnReceive); btnRow1.addView(btnSend)
        btnRow2.addView(btnRefresh); btnRow2.addView(btnSettings)

        statsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,5,0,0) }
        val statsTitle = TextView(this).apply { text = "📊 Thống kê Bitcoin"; textSize = POOL_FONT; setPadding(0,20,0,5); setTextColor(mainColor) }
        val txTitle = TextView(this).apply { text = "Lịch sử giao dịch"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0,30,0,10); setTextColor(mainColor) }
        txListView = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600) }

        rootLayout.addView(walletNameText); rootLayout.addView(balanceText); rootLayout.addView(balanceUsdText)
        rootLayout.addView(rateText); rootLayout.addView(syncText); rootLayout.addView(syncProgressBar)
        rootLayout.addView(addressText); rootLayout.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        rootLayout.addView(btnRow1); rootLayout.addView(btnRow2); rootLayout.addView(statsTitle)
        rootLayout.addView(blockText); rootLayout.addView(blockProgressBar); rootLayout.addView(statsContainer)
        rootLayout.addView(txTitle); rootLayout.addView(txListView)

        addStat("mined","Đã khai thác"); addStat("halving","Halving"); addStat("reward","Phần thưởng")
        addStat("diff","Difficulty"); addStat("mempool","Mempool"); addStat("hash","Hashrate")
        addStat("fee","Phí"); addStat("today","Hôm nay"); addStat("supply","Cung"); addStat("height","Height")

        btnReceive.setOnClickListener { showReceiveDialog() }
        btnSend.setOnClickListener { showSendDialog() }
        btnRefresh.setOnClickListener { refreshWallet(); fetchBlockUpdate(); fetchBtcStats(); toast("Đang làm mới tất cả...") }
        btnSettings.setOnClickListener { showSettings() }

        walletManager.onProgress { pct, txt -> runOnUiThread { syncText.text = txt; syncProgressBar.progress = pct } }
        refreshWallet()
        startAutoPriceSync()
        startBlockProgress()
    }

    private fun refreshWallet() {
        if (isSyncing) return
        isSyncing = true
        runOnUiThread { syncText.text = "Đang kết nối API..."; syncProgressBar.progress = 10 }
        Thread {
            try {
                runOnUiThread { syncProgressBar.progress = 30 }
                val bal = walletManager.getBalance()
                runOnUiThread { syncText.text = "Đang tải giá BTC..."; syncProgressBar.progress = 60 }
                val price = walletManager.price()
                runOnUiThread { syncText.text = "Đang cập nhật địa chỉ..."; syncProgressBar.progress = 85 }
                val addr = walletManager.getAddress()
                val txs = walletManager.getTransactions()
                runOnUiThread {
                    balanceText.text = String.format(Locale.US, "%.8f BTC", bal)
                    val balanceUsd = bal * price
                    balanceUsdText.text = String.format(Locale.US, "≈ $%,.2f", balanceUsd)
                    rateText.text = String.format(Locale.US, "BTC $%,.2f", price)
                    lastBalanceUsd = balanceUsd
                    lastPrice = price
                    addressText.text = "Địa chỉ: $addr"
                    syncText.text = "Đã đồng bộ • " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    syncProgressBar.progress = 100
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
                            text2.setTextColor(Color.GRAY); text2.textSize = 11f
                            return view
                        }
                    }
                    txListView.adapter = adapter
                    isSyncing = false
                }
            } catch (e: Exception) {
                runOnUiThread { syncText.text = "Lỗi đồng bộ"; syncProgressBar.progress = 0; isSyncing = false }
            }
        }.start()
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
        val addressView = TextView(this).apply { text = address; textSize = 13f; gravity = Gravity.CENTER; setTextIsSelectable(true); setPadding(0,10,0,20) }
        val copyBtn = Button(this).apply { text = "Copy địa chỉ" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("btc_address", address))
            toast("Đã copy - sẽ tự xóa sau 30 giây")
            handler.postDelayed({ try { cm.clearPrimaryClip() } catch (_: Exception) {} }, 30000)
        }
        layout.addView(imageView); layout.addView(addressView); layout.addView(copyBtn)
        AlertDialog.Builder(this).setTitle("Nhận Bitcoin").setView(layout).setPositiveButton("Đóng", null).show()
    }

    // ================== HÀM GỬI BTC ĐÃ SỬA HOÀN TOÀN ==================
    private fun showSendDialog() {
        if (isSyncing) { toast("Đang sync, vui lòng đợi"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val toInput = EditText(this).apply { hint = "Địa chỉ BTC (bc1... hoặc 1... hoặc 3...)" }
        pendingAddressInput = toInput

        val scanBtn = Button(this).apply {
            text = "📷 Quét QR"
            setOnClickListener {
                try {
                    qrScanLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                        setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                        setPrompt("Quét địa chỉ BTC"); setBeepEnabled(true)
                    })
                } catch (e: Exception) { toast("Lỗi quét QR") }
            }
        }

        val amountInput = EditText(this).apply { hint = "Số lượng BTC"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val balanceTv = TextView(this).apply { text = "Đang tải số dư..."; setTextColor(0xFF888888.toInt()); setPadding(0,10,0,10) }

        val feeProgress = ProgressBar(this).apply { visibility = View.VISIBLE }
        val feeGroup = RadioGroup(this).apply { visibility = View.GONE }
        val rSlow = RadioButton(this); val rNormal = RadioButton(this); val rFast = RadioButton(this)
        val rCustom = RadioButton(this).apply { id = 4; text = "Tùy chỉnh" }
        val customFeeInput = EditText(this).apply { hint = "sat/vB (1-500)"; inputType = InputType.TYPE_CLASS_NUMBER; visibility = View.GONE; setText("10") }
        feeGroup.addView(rSlow); feeGroup.addView(rNormal); feeGroup.addView(rFast); feeGroup.addView(rCustom)

        val feeEstimateTv = TextView(this).apply { text = "Ước tính phí: -"; setPadding(0,20,0,0) }
        val totalEstimateTv = TextView(this).apply { text = "Tổng (gửi + phí): -" }

        layout.addView(toInput); layout.addView(scanBtn); layout.addView(amountInput); layout.addView(balanceTv)
        layout.addView(TextView(this).apply { text = "Phí mạng:"; setPadding(0,20,0,0) })
        layout.addView(feeProgress); layout.addView(feeGroup); layout.addView(customFeeInput)
        layout.addView(feeEstimateTv); layout.addView(totalEstimateTv)

        var priceUsd = 60000.0
        fetchBtcPriceUsd { p -> priceUsd = p }

        val dialog = AlertDialog.Builder(this).setTitle("Gửi BTC").setView(layout)
            .setPositiveButton("Tiếp tục", null).setNegativeButton("Hủy", null).create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false

            Thread {
                // Lấy số dư và fee rates
                val balance = walletManager.getBalance()
                val feeRates = try { walletManager.getFeeRates() } catch (e: Exception) { FeeRates(5, 10, 20) }
                runOnUiThread {
                    balanceTv.text = "Số dư: ${"%.8f".format(balance)} BTC"
                    feeProgress.visibility = View.GONE
                    feeGroup.visibility = View.VISIBLE
                    rSlow.id = 1; rSlow.text = "Chậm (${feeRates.slow} sat/vB)"
                    rNormal.id = 2; rNormal.text = "Thường (${feeRates.normal} sat/vB)"; rNormal.isChecked = true
                    rFast.id = 3; rFast.text = "Nhanh (${feeRates.fast} sat/vB)"

                    fun updateEstimates() {
                        val to = toInput.text.toString().trim()
                        val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                        val feeRate = when (feeGroup.checkedRadioButtonId) {
                            1 -> feeRates.slow
                            3 -> feeRates.fast
                            4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 10
                            else -> feeRates.normal
                        }
                        if (to.isNotEmpty() && to.length >= 26 && amt > 0) {
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
                                btn.isEnabled = total <= balance
                                btn.alpha = if (btn.isEnabled) 1f else 0.5f
                            } catch (e: Exception) { btn.isEnabled = false }
                        } else {
                            btn.isEnabled = false
                            feeEstimateTv.text = "Nhập địa chỉ và số tiền hợp lệ"
                            totalEstimateTv.text = ""
                        }
                    }

                    feeGroup.setOnCheckedChangeListener { _, _ ->
                        customFeeInput.visibility = if (feeGroup.checkedRadioButtonId == 4) View.VISIBLE else View.GONE
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
                            4 -> customFeeInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 10
                            else -> feeRates.normal
                        }
                        val estFee = walletManager.estimateFee(to, amt, fee)
                        dialog.dismiss()
                        confirmSend(to, amt, fee, estFee)
                    }
                    updateEstimates()
                }
            }.start()
        }
        dialog.show()
    }

    private fun confirmSend(to: String, amt: Double, feeRate: Int, estFee: Double) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val summary = TextView(this).apply { text = "Gửi: $amt BTC\nĐến: $to\nPhí: ~$estFee BTC\nTổng: ${amt + estFee} BTC"; setPadding(0,0,0,20) }
        val passInput = EditText(this).apply { hint = "Nhập mật khẩu để xác nhận"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(summary); layout.addView(passInput)
        AlertDialog.Builder(this).setTitle("Xác nhận gửi").setView(layout)
            .setPositiveButton("Xác nhận") { _, _ ->
                val pass = passInput.text.toString()
                val id = walletManager.getActiveId() ?: return@setPositiveButton
                if (!walletManager.unlock(id, pass)) { toast("Sai mật khẩu"); return@setPositiveButton }
                val delayLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,30,40,30) }
                val tv = TextView(this).apply { text = "Đang chuẩn bị gửi sau 60 giây..."; gravity = Gravity.CENTER }
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 60; progress = 60 }
                val countdown = TextView(this).apply { text = "60s"; gravity = Gravity.CENTER; textSize = 18f }
                delayLayout.addView(tv); delayLayout.addView(progress); delayLayout.addView(countdown)
                var sec = 60
                val handler = android.os.Handler(mainLooper)
                lateinit var runnable: Runnable
                lateinit var delayDialog: AlertDialog
                runnable = object : Runnable {
                    override fun run() {
                        sec--; progress.progress = sec; countdown.text = "${sec}s"
                        if (sec > 0) handler.postDelayed(this, 1000)
                        else {
                            delayDialog.dismiss()
                            Thread {
                                try {
                                    val txid = walletManager.send(to, amt, feeRate)
                                    runOnUiThread { toast("Đã gửi! TXID: ${txid.take(8)}..."); refreshWallet() }
                                } catch (e: Exception) { runOnUiThread { toast("Lỗi gửi: ${e.message}") } }
                            }.start()
                        }
                    }
                }
                delayDialog = AlertDialog.Builder(this).setTitle("Delay bảo mật").setView(delayLayout).setCancelable(false)
                    .setNegativeButton("Hủy giao dịch") { _, _ -> handler.removeCallbacks(runnable); delayDialog.dismiss(); toast("Đã hủy gửi") }
                    .create()
                delayDialog.show()
                handler.postDelayed(runnable, 1000)
            }
            .setNegativeButton("Hủy", null).show()
    }

    private fun showSettings() { /* giữ nguyên */ }
    private fun showSeedDialog() { /* giữ nguyên */ }
    private fun showChangePassDialog() { /* giữ nguyên */ }
    private fun showRenameDialog() { /* giữ nguyên */ }
    private fun showDeleteDialog() { /* giữ nguyên */ }
    private fun showInfo() { /* giữ nguyên */ }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}