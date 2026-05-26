// MainActivity FULL - Binance Live Chart
package net.buli.ibtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.journeyapps.barcodescanner.ScanContract
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var walletManager: WalletManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var balanceText: TextView
    private lateinit var balanceUsdText: TextView
    private lateinit var rateText: TextView
    private lateinit var addressText: TextView
    private lateinit var syncText: TextView
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var walletNameText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        walletManager = WalletManager(this)
        setupRoot()
        setContentView(scrollView)
        if (walletManager.hasWallets()) showMainWallet() else showWelcome()
    }

    private fun setupRoot() {
        rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        scrollView = ScrollView(this).apply { addView(rootLayout) }
    }

    private fun showWelcome() {
        rootLayout.removeAllViews()
        rootLayout.addView(TextView(this).apply { text = "₿ iBTC Wallet"; textSize = 28f; gravity = Gravity.CENTER })
    }

    private fun showMainWallet() {
        rootLayout.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val mainColor = if (isDark) Color.WHITE else Color.BLACK
        val subColor = Color.GRAY

        walletNameText = TextView(this).apply { text = "My Wallet"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(mainColor) }
        balanceText = TextView(this).apply { text = "0.00000000 BTC"; textSize = 32f; setTypeface(null, Typeface.BOLD); setTextColor(mainColor) }
        balanceUsdText = TextView(this).apply { text = "≈ $0.00"; textSize = 16f; setTextColor(subColor) }
        rateText = TextView(this).apply { text = "BTC $0.00"; textSize = 14f; setTextColor(subColor) }
        syncText = TextView(this).apply { text = "Live"; textSize = 13f; setTextColor(subColor) }
        syncProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 50 }
        addressText = TextView(this).apply { text = "bc1q..."; textSize = 12f; setTextColor(subColor) }

        val chart = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 340)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun load(tf: String) {
            val it = mapOf("1m" to "1m","5m" to "5m","1h" to "1h","4h" to "4h","1D" to "1d","1W" to "1w","1M" to "1M")[tf]!!
            val bg = if (isDark) "#0d1117" else "#ffffff"
            val html = "<html><body style='margin:0;background:$bg'><canvas id=c height=320></canvas>" +
                    "<script src='https://cdn.jsdelivr.net/npm/chart.js'></script>" +
                    "<script src='https://cdn.jsdelivr.net/npm/chartjs-chart-financial'></script>" +
                    "<script>async function d(){const r=await fetch('https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$it&limit=100');const j=await r.json();const cd=j.map(x=>({x:x[0],o:+x[1],h:+x[2],l:+x[3],c:+x[4]}));const ma=[];for(let i=19;i<cd.length;i++){let s=0;for(let k=0;k<20;k++)s+=cd[i-k].c;ma.push({x:cd[i].x,y:s/20})}new Chart(c,{type:'candlestick',data:{datasets:[{data:cd,borderColor:'#F7931A',color:{up:'#00c853',down:'#d50000'}},{data:ma,type:'line',borderColor:'gold',pointRadius:0,borderWidth:1.5}]},options:{animation:false,scales:{x:{display:false},y:{ticks:{color:'${if(isDark)"#fff" else "#000"}'}}},plugins:{legend:{display:false}}}})}d();setInterval(d,15000)</script></body></html>"
            chart.loadDataWithBaseURL("https://binance.com", html, "text/html", "UTF-8", null)
        }
        listOf("1m","5m","1h","4h","1D","1W","1M").forEach { t ->
            bar.addView(Button(this).apply { text = t; textSize = 10f; setOnClickListener { load(t) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        load("1h")

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(walletNameText); addView(balanceText); addView(balanceUsdText); addView(rateText); addView(syncText); addView(syncProgressBar); addView(addressText) }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bar); addView(chart) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)) }

        rootLayout.addView(top)
        rootLayout.addView(Button(this).apply { text = "⟳ Làm mới"; setOnClickListener { load("1h") } })
    }
}
