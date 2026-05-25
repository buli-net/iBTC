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
        handler.postDelayed(object : Runnable {
            override fun run() {
                val active = walletManager.getActive()
                if (active!= null && System.currentTimeMillis() - lastInteractionTime > AUTO_LOCK_MS) {
                    walletManager.lock()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Tự động khóa sau 2 phút không dùng", Toast.LENGTH_SHORT).show()
                        showUnlockDialog()
                    }
                }
                handler.postDelayed(this, 10000)
            }
        }, 10000)
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
                val height = Regex("\"height\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt()?: 0
                val lastTime = Regex("\"timestamp\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong()?: 0L
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
                runOnUiThread {
                    blockText.text = "Lỗi pool - tự thử lại"
                    blockProgressBar.progress = 0
                }
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
                val diffProgress = Regex("\"progressPercent\":([\\d.]+)").find(diffJson)?.groupValues?.get(1)?.toFloat()?: 0f
                val mempoolJson = URL("https://mempool.space/api/mempool").readText()
                val mempoolCount = Regex("\"count\":(\\d+)").find(mempoolJson)?.groupValues?.get(1)?.toInt()?: 0
                val feesJson = URL("https://mempool.space/api/v1/fees/recommended").readText()
                val feeFast = Regex("\"fastestFee\":(\\d+)").find(feesJson)?.groupValues?.get(1)?.toInt()?: 0
                val hashJson = URL("https://mempool.space/api/v1/mining/hashrate/1w").readText()
                val currentHash = Regex("\"currentHashrate\":([\\d.]+)").find(hashJson)?.groupValues?.get(1)?.toDouble()?: 0.0

                runOnUiThread {
                    val minedPct = ((totalMined / 21000000.0) * 100).toInt()
                    statBars["mined"]?.progress = minedPct
                    statTexts["mined"]?.text = "Đã khai thác: ${String.format("%.2f", totalMined)} / 21M BTC ($minedPct%)"

                    val halvingPct = ((1 - blocksToHalving / 210000.0) * 100).toInt()
                    statBars["halving"]?.progress = halvingPct
                    statTexts["halving"]?.text = "Halving #${halvings+1}: còn $blocksToHalving blocks (~${blocksToHalving/144} ngày)"

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
                    statTexts["supply"]?.text = "Cung lưu thông: ${String.format("%.2f", totalMined/1000000)}M BTC"

                    statBars["height"]?.progress = height % 100
                    statTexts["height"]?.text = "Block height: #$height"
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun startBlockProgress() {
        blockText.text = "Đang kết nối mempool..."
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
        val logo = TextView(this).apply { text = "₿"; textSize = 72f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#F7931A")); setPadding(0, 80, 0, 20) }
        val title = TextView(this).apply { text = "iBTC Wallet v4.7"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(titleColor) }
        val subtitle = TextView(this).apply { text = "Bitcoin wallet an toàn, mã nguồn mở"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(0, 8, 0, 60) }
        val createBtn = Button(this).apply { text = "Tạo ví mới"; textSize = 16f; setPadding(0, 30, 0, 30) }
        val importBtn = Button(this).apply { text = "Import ví có sẵn"; textSize = 16f }
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
        val passInput = EditText(this).apply { hint = "Mật khẩu (tối thiểu 8 ký tự)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        val pass2Input = EditText(this).apply { hint = "Nhập lại mật khẩu"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        val warning = TextView(this).apply { text = "⚠️ Lưu mật khẩu cẩn thận. Mất = mất ví."; textSize = 12f; setTextColor(Color.RED); setPadding(0, 20, 0, 0) }
        layout.addView(nameInput)
        layout.addView(passInput)
        layout.addView(pass2Input)
        layout.addView(warning)
        AlertDialog.Builder(this).setTitle("Tạo ví Bitcoin mới").setView(layout).setPositiveButton("Tạo") { _, _ ->
            val name = nameInput.text.toString().trim()
            val p1 = passInput.text.toString()
            val p2 = pass2Input.text.toString()
            if (p1.length < 8) { toast("Mật khẩu phải ≥8 ký tự"); return@setPositiveButton }
            if (p1!= p2) { toast("Mật khẩu không khớp"); return@setPositiveButton }
            try {
                walletManager.create(name, p1)
                Thread { walletManager.init() }.start()
                toast("Tạo ví thành công")
                showMainWallet()
            } catch (e: Exception) {
                toast("Lỗi: ${e.message}")
            }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showImportDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val nameInput = EditText(this).apply { hint = "Tên ví" }
        val seedInput = EditText(this).apply { hint = "12 hoặc 24 từ seed, cách nhau bằng space"; minLines = 3; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val passInput = EditText(this).apply { hint = "Đặt mật khẩu mới ≥8 ký tự"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        layout.addView(nameInput)
        layout.addView(seedInput)
        layout.addView(passInput)
        AlertDialog.Builder(this).setTitle("Import ví").setView(layout).setPositiveButton("Import") { _, _ ->
            val name = nameInput.text.toString().trim()
            val seed = seedInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (pass.length < 8) { toast("Mật khẩu quá ngắn"); return@setPositiveButton }
            val info = walletManager.import(name, seed, pass)
            if (info == null) {
                toast("Seed không hợp lệ (cần 12-24 từ)")
            } else {
                Thread { walletManager.init() }.start()
                toast("Import thành công")
                showMainWallet()
            }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showUnlockDialog() {
        val id = walletManager.getActiveId()
        if (id == null) { showWelcome(); return }
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isDark) Color.WHITE else Color.BLACK
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40) }
        val title = TextView(this).apply { text = "🔒 Ví đã khóa"; textSize = 24f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 40); setTextColor(titleColor) }
        val passInput = EditText(this).apply { hint = "Nhập mật khẩu"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance(); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val unlockBtn = Button(this).apply { text = "Mở khóa"; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 } }
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

        walletNameText = TextView(this).apply { text = walletManager.getActive()?.name?: "Ví"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(mainColor) }
        balanceText = TextView(this).apply { text = "0.00000000 BTC"; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; setTextColor(mainColor); setPadding(0, 10, 0, 0) }
        priceText = TextView(this).apply { text = "≈ $0.00"; textSize = 16f; setTextColor(subColor) }
        syncText = TextView(this).apply { text = "Chưa đồng bộ"; textSize = 13f; setTextColor(subColor) }
        syncProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        addressText = TextView(this).apply { textSize = 12f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTextColor(subColor); setPadding(0, 10, 0, 10) }

        blockText = TextView(this).apply { text = "Đang kết nối mempool..."; textSize = POOL_FONT; setTextColor(subColor); setPadding(0,8,0,2); typeface = Typeface.DEFAULT }
        blockProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; scaleY = 0.7f }

        val btnRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnReceive = Button(this).apply { text = "⬇ Nhận"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 } }
        val btnSend = Button(this).apply { text = "⬆ Gửi"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 } }

        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnRefresh = Button(this).apply { text = "⟳ Làm mới"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 } }
        val btnSettings = Button(this).apply { text = "⚙ Cài đặt"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 } }

        btnRow1.addView(btnReceive)
        btnRow1.addView(btnSend)
        btnRow2.addView(btnRefresh)
        btnRow2.addView(btnSettings)

        statsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 5, 0, 0) }
        val statsTitle = TextView(this).apply { text = "📊 Thống kê Bitcoin"; textSize = POOL_FONT; typeface = Typeface.DEFAULT; setPadding(0, 20, 0, 5); setTextColor(mainColor) }
        val txTitle = TextView(this).apply { text = "Lịch sử giao dịch"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 30, 0, 10); setTextColor(mainColor) }
        txListView = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600) }

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
            // SỬA: bỏ xoay, nút đứng im như 3 nút kia
            refreshWallet()
            fetchBlockUpdate()
            fetchBtcStats()
            toast("Đang làm mới tất cả...")
        }
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
                    priceText.text = String.format(Locale.US, "≈ $%,.2f (BTC $%,.2f)", bal * price, price)
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
                            text1.text = "${if (tx.type == "Nhận") "⬇" else "⬆"} ${tx.type} ${String.format(Locale.US, "%.8f", tx.amount)}"
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
                for (x in 0 until 512) { for (y in 0 until 512) { bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE) } }
                runOnUiThread { imageView.setImageBitmap(bmp) }
            } catch (e: Exception) { runOnUiThread { toast("Lỗi tạo QR: ${e.message}") } }
        }.start()
        val addressView = TextView(this).apply { text = address; textSize = 13f; gravity = Gravity.CENTER; setTextIsSelectable(true); setPadding(0, 10, 0, 20) }
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

    private fun showSendDialog() {
        if (isSyncing) {
            toast("Đang sync, vui lòng đợi")
            return
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val toInput = EditText(this).apply { hint = "Địa chỉ BTC (bc1... hoặc 1... hoặc 3...)" }
        val amountInput = EditText(this).apply { hint = "Số lượng BTC"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val feeRates = try { walletManager.getFeeRates() } catch (_: Exception) { FeeRates(5, 10, 20) }
        val feeGroup = RadioGroup(this)
        val rSlow = RadioButton(this).apply { text = "Chậm ~60 phút (${feeRates.slow} sat/vB)"; id = 1 }
        val rNormal = RadioButton(this).apply { text = "Thường ~30 phút (${feeRates.normal} sat/vB)"; id = 2; isChecked = true }
        val rFast = RadioButton(this).apply { text = "Nhanh ~10 phút (${feeRates.fast} sat/vB)"; id = 3 }
        feeGroup.addView(rSlow)
        feeGroup.addView(rNormal)
        feeGroup.addView(rFast)
        layout.addView(toInput)
        layout.addView(amountInput)
        layout.addView(TextView(this).apply { text = "Chọn phí mạng:"; setPadding(0,20,0,0) })
        layout.addView(feeGroup)
        AlertDialog.Builder(this).setTitle("Gửi BTC").setView(layout).setPositiveButton("Tiếp tục") { _, _ ->
            val to = toInput.text.toString().trim()
            val amt = amountInput.text.toString().toDoubleOrNull()?: 0.0
            if (to.length < 26 || amt <= 0) {
                toast("Địa chỉ hoặc số tiền không hợp lệ")
                return@setPositiveButton
            }
            val fee = when (feeGroup.checkedRadioButtonId) {
                1 -> feeRates.slow
                3 -> feeRates.fast
                else -> feeRates.normal
            }
            val estFee = walletManager.estimateFee(to, amt, fee)
            confirmSend(to, amt, fee, estFee)
        }.setNegativeButton("Hủy", null).show()
    }

    private fun confirmSend(to: String, amt: Double, feeRate: Int, estFee: Double) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val summary = TextView(this).apply { text = "Gửi: $amt BTC\nĐến: $to\nPhí: ~$estFee BTC\nTổng: ${amt + estFee} BTC"; setPadding(0,0,0,20) }
        val passInput = EditText(this).apply { hint = "Nhập mật khẩu để xác nhận"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        layout.addView(summary)
        layout.addView(passInput)
        AlertDialog.Builder(this).setTitle("Xác nhận").setView(layout).setPositiveButton("Gửi ngay") { _, _ ->
            val id = walletManager.getActiveId()?: return@setPositiveButton
            if (!walletManager.unlock(id, passInput.text.toString())) {
                toast("Sai mật khẩu")
                return@setPositiveButton
            }
            Thread {
                val result = walletManager.send(to, amt, feeRate)
                runOnUiThread {
                    if (result.startsWith("Lỗi")) toast(result) else {
                        toast("Đã gửi! TXID: ${result.take(12)}...")
                        refreshWallet()
                    }
                }
            }.start()
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showSettings() {
        val items = arrayOf("👁 Xem seed phrase", "🔑 Đổi mật khẩu", "✏️ Đổi tên ví", "🗑 Xóa ví vĩnh viễn", "🔒 Khóa ví ngay", "ℹ️ Thông tin")
        AlertDialog.Builder(this).setTitle("Cài đặt").setItems(items) { _, w ->
            when(w) {
                0 -> showSeedDialog()
                1 -> showChangePassDialog()
                2 -> showRenameDialog()
                3 -> showDeleteDialog()
                4 -> { walletManager.lock(); showUnlockDialog() }
                5 -> showInfo()
            }
        }.show()
    }

    private fun showSeedDialog() {
        val pass = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        AlertDialog.Builder(this).setTitle("Nhập mật khẩu để xem seed").setView(pass).setPositiveButton("Xem") { _, _ ->
            val id = walletManager.getActiveId()?: return@setPositiveButton
            if (walletManager.unlock(id, pass.text.toString())) {
                val seed = walletManager.getSeed()
                val tv = TextView(this).apply { text = seed; textSize = 16f; setTextIsSelectable(true); setPadding(40,40,40,40); gravity = Gravity.CENTER }
                AlertDialog.Builder(this).setTitle("⚠️ KHÔNG CHIA SẺ SEED").setView(tv).setPositiveButton("Copy 30s") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("seed", seed))
                    handler.postDelayed({ cm.clearPrimaryClip() }, 30000)
                }.setNegativeButton("Đóng", null).show()
            } else toast("Sai mật khẩu")
        }.show()
    }

    private fun showChangePassDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30) }
        val oldP = EditText(this).apply { hint = "Mật khẩu cũ"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val newP = EditText(this).apply { hint = "Mật khẩu mới ≥8"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(oldP)
        layout.addView(newP)
        AlertDialog.Builder(this).setTitle("Đổi mật khẩu").setView(layout).setPositiveButton("Đổi") { _, _ ->
            val id = walletManager.getActiveId()?: return@setPositiveButton
            if (walletManager.changePassword(id, oldP.text.toString(), newP.text.toString())) toast("Đã đổi thành công") else toast("Sai mật khẩu cũ")
        }.show()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply { hint = "Tên ví mới"; setText(walletManager.getActive()?.name?: "") }
        AlertDialog.Builder(this).setTitle("Đổi tên").setView(input).setPositiveButton("Lưu") { _, _ ->
            val id = walletManager.getActiveId()?: return@setPositiveButton
            walletManager.rename(id, input.text.toString())
            walletNameText.text = input.text.toString()
            toast("Đã đổi tên")
        }.show()
    }

    private fun showDeleteDialog() {
        val pass = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("XÓA VĨNH VIỄN").setMessage("Nhập mật khẩu để xóa. Không thể khôi phục nếu không có seed!").setView(pass).setPositiveButton("XÓA") { _, _ ->
            val id = walletManager.getActiveId()?: return@setPositiveButton
            if (walletManager.unlock(id, pass.text.toString())) {
                walletManager.delete(id)
                showWelcome()
                toast("Đã