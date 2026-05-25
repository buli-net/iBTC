package net.buli.ibtc
import android.content.Context
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*

data class WalletInfo(val id:String,val name:String)
data class TransactionInfo(val txId:String,val amount:Double,val type:String,val time:Date)
data class FeeRates(val slow:Int,val normal:Int,val fast:Int)

class WalletManager(private val ctx:Context){
    private val params=MainNetParams.get()
    private var active:WalletInfo?=null
    private var cachedSeed:String?=null
    private var cachedPassword:CharArray?=null
    private val prefs=ctx.getSharedPreferences("wallets",Context.MODE_PRIVATE)
    private var lastPrice=prefs.getFloat("last_price",67500f).toDouble()

    fun hasWallets()=prefs.all.keys.any{it.endsWith("_seed")}
    fun getActive()=active
    fun getActiveId()=prefs.all.keys.firstOrNull{it.endsWith("_seed")}?.removeSuffix("_seed")
    fun unlock(id:String,pw:String):Boolean{
        if(prefs.getInt("${id}_attempts",0)>=5) return false
        return try{
            val seed=CryptoUtil.decrypt(prefs.getString("${id}_seed","")!!,pw)
            cachedSeed=seed; cachedPassword=pw.toCharArray()
            active=WalletInfo(id,prefs.getString("${id}_name","")!!)
            prefs.edit().putInt("${id}_attempts",0).apply(); true
        }catch(e:Exception){ prefs.edit().putInt("${id}_attempts",prefs.getInt("${id}_attempts",0)+1).apply(); false }
    }
    fun lock(){ cachedPassword?.fill('0'); cachedPassword=null; cachedSeed=null; active=null }
    fun changePassword(id:String,old:String,new:String)=try{ val s=CryptoUtil.decrypt(prefs.getString("${id}_seed","")!!,old); prefs.edit().putString("${id}_seed",CryptoUtil.encrypt(s,new)).apply(); true }catch(_:Exception){false}
    fun rename(id:String,n:String)=try{ prefs.edit().putString("${id}_name",n).apply(); if(active?.id==id) active=active?.copy(name=n); true }catch(_:Exception){false}
    fun create(name:String,pw:String):WalletInfo{ val id=UUID.randomUUID().toString(); val seed=DeterministicSeed(SecureRandom(),128,""); val m=seed.mnemonicCode!!.joinToString(" "); val i=WalletInfo(id,if(name.isBlank())"Ví $id" else name); prefs.edit().putString("${id}_name",i.name).putString("${id}_seed",CryptoUtil.encrypt(m,pw)).apply(); cachedSeed=m; cachedPassword=pw.toCharArray(); active=i; return i }
    fun import(name:String,phrase:String,pw:String):WalletInfo?=try{ val c=phrase.trim().lowercase().replace(Regex("\\s+")," "); if(c.split(" ").size<12) null else { DeterministicSeed(c.split(" "),null,"",System.currentTimeMillis()/1000); val id=UUID.randomUUID().toString(); val i=WalletInfo(id,if(name.isBlank())"Imported" else name); prefs.edit().putString("${id}_name",i.name).putString("${id}_seed",CryptoUtil.encrypt(c,pw)).apply(); cachedSeed=c; cachedPassword=pw.toCharArray(); active=i; i } }catch(_:Exception){null}
    fun delete(id:String){ lock(); prefs.edit().remove("${id}_name").remove("${id}_seed").remove("${id}_attempts").apply() }
    fun init(){}; fun stop(){}; fun onProgress(cb:(Int,String)->Unit){cb(100,"Sẵn sàng")}
    fun getAddress():String{ val s=cachedSeed?:return""; val dk=DeterministicSeed(s.split(" "),null,"",0L); val ch=DeterministicKeyChain.builder().seed(dk).build(); return LegacyAddress.fromKey(params,ch.getKeyByPath(HDUtils.parsePath("M/44H/0H/0H/0/0"),true)).toString() }
    fun getSeed()=cachedSeed?:""
    private fun http(u:String)=try{ (URL(u).openConnection() as HttpURLConnection).apply{setRequestProperty("User-Agent","Mozilla/5.0");connectTimeout=7000;readTimeout=7000}.inputStream.bufferedReader().readText() }catch(_:Exception){""}
    fun getBalance():Double{ val a=getAddress(); if(a.isEmpty()) return 0.0; return try{ val o=JSONObject(http("https://blockstream.info/api/address/$a")).getJSONObject("chain_stats"); (o.getLong("funded_txo_sum")-o.getLong("spent_txo_sum"))/1e8 }catch(_:Exception){0.0} }
    fun getTransactions():List<TransactionInfo>{ val a=getAddress(); return try{ val arr=org.json.JSONArray(http("https://blockstream.info/api/address/$a/txs")); (0 until minOf(arr.length(),20)).map{ i-> val o=arr.getJSONObject(i); TransactionInfo(o.getString("txid"),0.0,"",Date()) } }catch(_:Exception){emptyList()} }
    fun price():Double{ try{ JSONObject(http("https://api.coinbase.com/v2/prices/BTC-USD/spot")).getJSONObject("data").getString("amount").toDoubleOrNull()?.let{lastPrice=it; return it} }catch(_:Exception){}; return lastPrice }
    fun getFeeRates()=try{ val j=JSONObject(http("https://mempool.space/api/v1/fees/recommended")); FeeRates(j.getInt("hourFee"),j.getInt("halfHourFee"),j.getInt("fastestFee")) }catch(_:Exception){FeeRates(5,10,20)}
    fun estimateFee(t:String,a:Double,r:Int)=r*250.0/1e8
    fun send(t:String,a:Double,r:Int)="Gửi đang phát triển"
}