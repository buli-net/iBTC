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

    // ... (các biến giữ nguyên như bản trước, tôi không viết lại toàn bộ để tránh dài dòng)

    private fun showMainWallet() {
        // ... (phần cài đặt giao diện giữ nguyên)

        btnRefresh.setOnClickListener {
            refreshWalletFromSPV()          // cập nhật số dư, lịch sử, tỷ giá
            fetchBlockUpdate()              // cập nhật thống kê block
            fetchBtcStats()                 // cập nhật mempool, phí
            SyncService.getInstance()?.refreshProgress()   // cập nhật thanh progress SPV
            toast("Đang làm mới...")
        }

        // ... (phần còn lại giữ nguyên)
    }

    // ... (các hàm khác giữ nguyên)
}