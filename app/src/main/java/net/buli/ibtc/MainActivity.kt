package net.buli.ibtc
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {
    private lateinit var wm:WalletManager
    private val h=Handler(Looper.getMainLooper())
    private var last=System.currentTimeMillis()
    override fun onCreate(b:Bundle?){super.onCreate(b); window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE); wm=WalletManager(this); val l=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; setContentView(l); if(wm.hasWallets()) unlock() else create() }
    override fun onDestroy(){ h.removeCallbacksAndMessages(null); wm.lock(); super.onDestroy() }
    private fun create(){ val n=EditText(this); val p=EditText(this); AlertDialog.Builder(this).setView(LinearLayout(this).apply{addView(n);addView(p)}).setPositiveButton("OK"){_,_-> wm.create(n.text.toString(),p.text.toString()); main() }.show() }
    private fun unlock(){ val id=wm.getActiveId()!!; val p=EditText(this); AlertDialog.Builder(this).setView(p).setPositiveButton("OK"){_,_-> if(wm.unlock(id,p.text.toString())) main() }.show() }
    private fun main(){ val t=TextView(this); val a=TextView(this); val b=Button(this).apply{text="QR"}; b.setOnClickListener{ val iv=ImageView(this); Thread{ val bmp=Bitmap.createBitmap(512,512,Bitmap.Config.RGB_565); val bit=QRCodeWriter().encode(wm.getAddress(),BarcodeFormat.QR_CODE,512,512); for(x in 0 until 512) for(y in 0 until 512) bmp.setPixel(x,y,if(bit[x,y])Color.BLACK else Color.WHITE); runOnUiThread{iv.setImageBitmap(bmp)} }.start(); AlertDialog.Builder(this).setView(iv).show() }; Thread{ val bal=wm.getBalance(); runOnUiThread{ t.text="%.8f BTC".format(bal); a.text=wm.getAddress() } }.start(); (findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as LinearLayout).apply{removeAllViews();addView(t);addView(a);addView(b)} }
}