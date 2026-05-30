package net.buli.ibtc

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class KlineData(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

object BitcoinChartService {
    private const val BASE_URL = "https://api.binance.com/api/v3/klines"
    private val client = OkHttpClient()
    private val gson = Gson()

    fun fetchKlines(
        interval: String = "1h",
        limit: Int = 168,
        callback: (List<KlineData>?) -> Unit
    ) {
        val url = "$BASE_URL?symbol=BTCUSDT&interval=$interval&limit=$limit"
        val request = Request.Builder().url(url).build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    callback(null)
                    return@Thread
                }
                val jsonString = response.body?.string()
                if (jsonString == null) {
                    callback(null)
                    return@Thread
                }
                val jsonArray = gson.fromJson(jsonString, Array<Array<Any>>::class.java)
                val klines = jsonArray.map { item ->
                    KlineData(
                        openTime = (item[0] as Double).toLong(),
                        open = (item[1] as String).toDouble(),
                        high = (item[2] as String).toDouble(),
                        low = (item[3] as String).toDouble(),
                        close = (item[4] as String).toDouble(),
                        volume = (item[5] as String).toDouble()
                    )
                }
                callback(klines)
            } catch (e: IOException) {
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }
}