package net.buli.ibtc

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding

class MainActivity : AppCompatActivity() {
    private lateinit var rootLayout: LinearLayout
    private lateinit var balanceText: TextView
    private lateinit var balanceUsdText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        setContentView(ScrollView(this).apply { addView(rootLayout) })
        showMain()
    }

    private fun showMain() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        balanceText = TextView(this).apply { text="0.12345678 BTC"; textSize=32f; typeface=Typeface.DEFAULT_BOLD; setTextColor(if(isDark)Color.WHITE else Color.BLACK) }
        balanceUsdText = TextView(this).apply { text="≈ $7,500"; textSize=16f; setTextColor(Color.GRAY) }

        val chart = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 340)
            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        fun load(tf:String){
            val i = mapOf("1m" to "1m","5m" to "5m","1h" to "1h","4h" to "4h","1D" to "1d","1W" to "1w","1M" to "1M")[tf]!!
            val html = """
            <html><body style="margin:0;background:${if(isDark)"#000" else "#fff"}">
            <canvas id=c></canvas>
            <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/chartjs-chart-financial"></script>
            <script>
            async function d(){
              let r=await fetch('https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$i&limit=100');
              let j=await r.json();
              let cd=j.map(x=>({x:x[0],o:+x[1],h:+x[2],l:+x[3],c:+x[4]}));
              new Chart(c,{type:'candlestick',data:{datasets:[{data:cd}]},options:{animation:false}});
            } d(); setInterval(d,15000);
            </script></body></html>
            """.trimIndent()
            chart.loadDataWithBaseURL("https://binance.com", html, "text/html","UTF-8",null)
        }
        val bar = LinearLayout(this).apply{ orientation=LinearLayout.HORIZONTAL }
        listOf("1m","5m","1h","4h","1D","1W","1M").forEach{ t-> bar.addView(Button(this).apply{text=t;setOnClickListener{load(t)}}, LinearLayout.LayoutParams(0,-2,1f)) }
        load("1h")

        val left = LinearLayout(this).apply{ orientation=LinearLayout.VERTICAL; addView(balanceText); addView(balanceUsdText) }
        val right = LinearLayout(this).apply{ orientation=LinearLayout.VERTICAL; addView(bar); addView(chart) }
        val top = LinearLayout(this).apply{ orientation=LinearLayout.HORIZONTAL; addView(left, LinearLayout.LayoutParams(0,-2,1f)); addView(right, LinearLayout.LayoutParams(0,-2,1.3f)) }
        rootLayout.addView(top)
    }
}