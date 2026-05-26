package net.buli.ibtc

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var walletManager: WalletManager
    private lateinit var balanceText: TextView
    private lateinit var btcAmountText: TextView
    private lateinit var usdAmountText: TextView
    private lateinit var txListView: ListView
    private lateinit var syncText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        walletManager = WalletManager(this)

        balanceText = findViewById(R.id.balanceText)
        btcAmountText = findViewById(R.id.btcAmountText)
        usdAmountText = findViewById(R.id.usdAmountText)
        txListView = findViewById(R.id.txListView)
        syncText = findViewById(R.id.syncText)

        findViewById<LinearLayout>(R.id.sendButton).setOnClickListener { showSendDialog() }
        findViewById<LinearLayout>(R.id.receiveButton).setOnClickListener { showReceiveDialog() }

        refreshWallet()
    }

    private fun getRealBalance(address: String): Double {
        return try {
            val conn = URL("https://blockstream.info/api/address/$address").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val chain = json.getJSONObject("chain_stats")
            val funded = chain.getLong("funded_txo_sum")
            val spent = chain.getLong("spent_txo_sum")
            (funded - spent) / 100000000.0
        } catch (e: Exception) { 0.0 }
    }

    private fun getRealTxs(address: String): List<Pair<String, Double>> {
        return try {
            val conn = URL("https://blockstream.info/api/address/$address/txs").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val list = mutableListOf<Pair<String, Double>>()
            for (i in 0 until kotlin.math.min(arr.length(), 20)) {
                val tx = arr.getJSONObject(i)
                val txid = tx.getString("txid")
                var amount = 0.0
                val vout = tx.getJSONArray("vout")
                for (j in 0 until vout.length()) {
                    val out = vout.getJSONObject(j)
                    if (out.optString("scriptpubkey_address") == address) {
                        amount += out.getLong("value") / 100000000.0
                    }
                }
                list.add(Pair(txid, amount))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    private fun refreshWallet() {
        Thread {
            val addr = walletManager.getAddress()
            val realBal = getRealBalance(addr)
            val realTxs = getRealTxs(addr)
            runOnUiThread {
                val bal = if (realBal > 0) realBal else walletManager.getBalance()
                balanceText.text = "%.8f BTC".format(bal)
                btcAmountText.text = "%.8f BTC".format(bal)
                usdAmountText.text = "$%.2f".format(bal * 65000.0)
                syncText.text = "Đã đồng bộ • $addr"

                if (realTxs.isEmpty()) {
                    txListView.adapter = object : BaseAdapter() {
                        override fun getCount() = 1
                        override fun getItem(p: Int) = null
                        override fun getItemId(p: Int) = 0L
                        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                            return TextView(this@MainActivity).apply {
                                text = "— chưa có giao dịch —"
                                gravity = Gravity.CENTER
                                setPadding(0,40,0,40)
                                setTextColor(android.graphics.Color.GRAY)
                            }
                        }
                    }
                } else {
                    txListView.adapter = object : BaseAdapter() {
                        override fun getCount() = realTxs.size
                        override fun getItem(p: Int) = realTxs[p]
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                            val view = v ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                            val (txid, amt) = realTxs[p]
                            view.findViewById<TextView>(android.R.id.text1).text = "+%.8f BTC".format(amt)
                            view.findViewById<TextView>(android.R.id.text2).text = txid.take(16) + "..."
                            return view
                        }
                    }
                }
            }
        }.start()
    }

    private fun showSendDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_send, null)
        val addrInput = view.findViewById<EditText>(R.id.addressInput)
        val amountInput = view.findViewById<EditText>(R.id.amountInput)
        val balanceInfo = view.findViewById<TextView>(R.id.balanceInfo)

        Thread {
            val realBal = getRealBalance(walletManager.getAddress()).let { if (it>0) it else walletManager.getBalance() }
            runOnUiThread { balanceInfo.text = "Số dư: %.8f BTC".format(realBal) }
        }.start()

        AlertDialog.Builder(this).setView(view)
           .setPositiveButton("Gửi") { _, _ ->
                val to = addrInput.text.toString()
                val amt = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                Thread {
                    val txid = walletManager.send(to, amt)
                    runOnUiThread {
                        Toast.makeText(this, if (txid != null) "Đã gửi: $txid" else "Lỗi gửi", Toast.LENGTH_LONG).show()
                        refreshWallet()
                    }
                }.start()
            }
           .setNegativeButton("Hủy", null).show()
    }

    private fun showReceiveDialog() {
        val addr = walletManager.getAddress()
        val view = TextView(this).apply { text = addr; textSize = 16f; setPadding(40,40,40,40) }
        AlertDialog.Builder(this).setTitle("Địa chỉ nhận").setView(view)
           .setPositiveButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                   .setPrimaryClip(android.content.ClipData.newPlainText("addr", addr))
            }.show()
    }
}