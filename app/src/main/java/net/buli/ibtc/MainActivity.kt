package net.buli.ibtc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var wm: WalletManager
    private var qrCallback: ((String) -> Unit)? = null
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { content -> qrCallback?.invoke(content) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Chặn chụp màn hình
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        wm = WalletManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60000)
                try {
                    wm.init()
                    wm.getBalance()
                    wm.price()
                } catch (_: Exception) {}
            }
        }

        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(
                primary = Color(0xFF7C4DFF),
                secondary = Color(0xFF7C4DFF),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White
            ) else lightColorScheme(
                primary = Color(0xFF7C4DFF),
                secondary = Color(0xFF7C4DFF),
                background = Color.White,
                surface = Color.White,
                onPrimary = Color.White,
                onBackground = Color(0xFF1A1A1A),
                onSurface = Color(0xFF1A1A1A)
            )

            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var hasWallet by remember { mutableStateOf(wm.hasWallets()) }
                    var price by remember { mutableStateOf(0.0) }
                    var walletName by remember { mutableStateOf(wm.getActive()?.name ?: "") }
                    val context = LocalContext.current
                    var isLocked by remember { mutableStateOf(false) }
                    var currentId by remember { mutableStateOf("") }

                    // Tự động khóa sau 2 phút
                    LaunchedEffect(isLocked, hasWallet) {
                        if (!isLocked && hasWallet) {
                            delay(120000)
                            withContext(Dispatchers.IO) { wm.lock() }
                            isLocked = true
                        }
                    }

                    LaunchedEffect(hasWallet) {
                        if (hasWallet) {
                            withContext(Dispatchers.IO) {
                                try {
                                    currentId = wm.getActiveId() ?: ""
                                    isLocked = true
                                    walletName = ""
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    if (isLocked && hasWallet) {
                        var pass by remember { mutableStateOf("") }
                        var err by remember { mutableStateOf("") }
                        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text("🔒 iBTC", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(24.dp))
                            OutlinedTextField(value = pass, onValueChange = { pass = it; err = "" }, label = { Text("Mật khẩu") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val ok = wm.unlock(currentId, pass)
                                    withContext(Dispatchers.Main) {
                                        if (ok) {
                                            isLocked = false
                                            wm.init()
                                            walletName = wm.getActive()?.name ?: ""
                                            price = wm.price()
                                        } else err = "Sai mật khẩu (5 lần sẽ khóa)"
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("MỞ KHÓA") }
                        }
                        return@Surface
                    }

                    if (!hasWallet) {
                        var showCreate by remember { mutableStateOf(false) }
                        var showImport by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text("iBTC", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { showCreate = true }, Modifier.fillMaxWidth().height(50.dp)) { Text("TẠO VÍ MỚI") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { showImport = true }, Modifier.fillMaxWidth().height(50.dp)) { Text("IMPORT SEED") }
                        }
                        if (showCreate) {
                            var name by remember { mutableStateOf("") }
                            var p1 by remember { mutableStateOf("") }
                            var p2 by remember { mutableStateOf("") }
                            var err by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showCreate = false },
                                confirmButton = {},
                                title = { Text("Tạo ví mới") },
                                text = {
                                    Column {
                                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Tên ví") }, modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(value = p1, onValueChange = { p1 = it }, label = { Text("Mật khẩu (>=8 ký tự)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(value = p2, onValueChange = { p2 = it }, label = { Text("Nhập lại") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = {
                                            if (p1.length < 8) { err = "Mật khẩu >=8 ký tự"; return@Button }
                                            if (p1 != p2) { err = "Không khớp"; return@Button }
                                            showCreate = false
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                wm.create(name, p1)
                                                wm.init()
                                                withContext(Dispatchers.Main) {
                                                    hasWallet = true
                                                    isLocked = false
                                                    walletName = wm.getActive()?.name ?: ""
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("TẠO VÍ") }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = { showCreate = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("HỦY") }
                                    }
                                }
                            )
                        }
                        if (showImport) {
                            var name by remember { mutableStateOf("") }
                            var seed by remember { mutableStateOf("") }
                            var p1 by remember { mutableStateOf("") }
                            var p2 by remember { mutableStateOf("") }
                            var err by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showImport = false },
                                confirmButton = {},
                                title = { Text("Import ví") },
                                text = {
                                    Column {
                                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(value = seed, onValueChange = { seed = it }, label = { Text("Seed 12 từ") }, modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(value = p1, onValueChange = { p1 = it }, label = { Text("Mật khẩu mới (>=8)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(value = p2, onValueChange = { p2 = it }, label = { Text("Nhập lại") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = {
                                            if (p1.length < 8) { err = "Mật khẩu >=8"; return@Button }
                                            if (p1 != p2) { err = "Không khớp"; return@Button }
                                            showImport = false
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val ok = wm.import(name, seed, p1) != null
                                                if (ok) {
                                                    wm.init()
                                                    withContext(Dispatchers.Main) {
                                                        hasWallet = true
                                                        isLocked = false
                                                        walletName = wm.getActive()?.name ?: ""
                                                    }
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("IMPORT") }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = { showImport = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("HỦY") }
                                    }
                                }
                            )
                        }
                    } else {
                        var tab by remember { mutableStateOf(0) }
                        var showMenu by remember { mutableStateOf(false) }
                        var showRename by remember { mutableStateOf(false) }
                        var showDetails by remember { mutableStateOf(false) }
                        var showChangePass by remember { mutableStateOf(false) }
                        var showDeleteConfirm by remember { mutableStateOf(false) }

                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(walletName) },
                                    actions = {
                                        IconButton(onClick = { showMenu = true }) { Text("⋮", fontSize = 20.sp) }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                            DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { showMenu = false; showRename = true })
                                            DropdownMenuItem(text = { Text("Đổi mật khẩu") }, onClick = { showMenu = false; showChangePass = true })
                                            DropdownMenuItem(text = { Text("Chi tiết ví") }, onClick = { showMenu = false; showDetails = true })
                                            DropdownMenuItem(text = { Text("Khóa ví") }, onClick = {
                                                showMenu = false
                                                lifecycleScope.launch(Dispatchers.IO) { wm.lock() }
                                                isLocked = true
                                            })
                                            DropdownMenuItem(text = { Text("Xóa ví") }, onClick = { showMenu = false; showDeleteConfirm = true })
                                        }
                                    }
                                )
                            }
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                Column(Modifier.fillMaxSize()) {
                                    TabRow(selectedTabIndex = tab) {
                                        Tab(selected = tab == 0, onClick = { tab = 0 }) { Text("Ví", Modifier.padding(12.dp)) }
                                        Tab(selected = tab == 1, onClick = { tab = 1 }) { Text("Gửi/Nhận", Modifier.padding(12.dp)) }
                                    }
                                    if (tab == 0) {
                                        var balance by remember { mutableStateOf(0.0) }
                                        var progress by remember { mutableStateOf(0) }
                                        var status by remember { mutableStateOf("Chưa sync") }
                                        var transactions by remember { mutableStateOf(listOf<TransactionInfo>()) }
                                        val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                                        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        LaunchedEffect(Unit) {
                                            wm.onProgress { p, t -> lifecycleScope.launch(Dispatchers.Main) { progress = p; status = t } }
                                            withContext(Dispatchers.IO) {
                                                balance = wm.getBalance(); price = wm.price(); transactions = wm.getTransactions()
                                            }
                                            while (true) {
                                                delay(60000)
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        val newBalance = wm.getBalance()
                                                        val newPrice = wm.price()
                                                        val newTransactions = wm.getTransactions()
                                                        withContext(Dispatchers.Main) {
                                                            balance = newBalance; price = newPrice; transactions = newTransactions
                                                            status = "Auto sync " + timeFormat.format(Date())
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        Column(Modifier.padding(16.dp)) {
                                            Card(Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(16.dp)) {
                                                    Text("Số dư:")
                                                    Text("%.8f BTC".format(balance), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                                    val totalUsd = balance * price
                                                    Text("≈ $%,.2f".format(totalUsd), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                                    Text("$%,.2f / BTC".format(price), fontSize = 12.sp, color = Color.Gray)
                                                    Text("$status • Giá tự động", fontSize = 12.sp)
                                                    if (progress in 1..99) LinearProgressIndicator(progress = progress / 100f, Modifier.fillMaxWidth().padding(top = 8.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Button(onClick = {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val newBalance = wm.getBalance(); val newPrice = wm.price(); val newTransactions = wm.getTransactions()
                                                    withContext(Dispatchers.Main) { balance = newBalance; price = newPrice; transactions = newTransactions; status = "Sync tay"; progress = 100 }
                                                }
                                            }, Modifier.fillMaxWidth()) { Text("SYNC NGAY") }
                                            Spacer(Modifier.height(16.dp)); Text("Lịch sử", fontWeight = FontWeight.Bold)
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                items(transactions) { tx ->
                                                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                                        Row(Modifier.padding(12.dp)) {
                                                            Column(Modifier.weight(1f)) {
                                                                Text(tx.type, fontWeight = FontWeight.Bold); Text("%.8f".format(tx.amount)); Text(dateFormat.format(tx.time), fontSize = 11.sp)
                                                            }
                                                            Text(tx.txId.take(8), fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        var toAddress by remember { mutableStateOf("") }
                                        var amount by remember { mutableStateOf("") }
                                        var result by remember { mutableStateOf("") }
                                        var receiveAddress by remember { mutableStateOf("") }
                                        var fees by remember { mutableStateOf(FeeRates(5, 10, 20)) }
                                        var feeSelection by remember { mutableStateOf(1) }
                                        var customFee by remember { mutableStateOf("") }
                                        var showSendConfirm by remember { mutableStateOf(false) }
                                        var sendPass by remember { mutableStateOf("") }
                                        var sendErr by remember { mutableStateOf("") }
                                        var currentBalance by remember { mutableStateOf(0.0) }

                                        LaunchedEffect(Unit) { 
                                            withContext(Dispatchers.IO) { 
                                                receiveAddress = wm.getAddress()
                                                fees = wm.getFeeRates()
                                                currentBalance = wm.getBalance()
                                            } 
                                        }

                                        val selectedFeeRate = when (feeSelection) {
                                            0 -> fees.slow
                                            1 -> fees.normal
                                            2 -> fees.fast
                                            else -> customFee.toIntOrNull()?.coerceIn(1, 1000) ?: fees.normal
                                        }
                                        val amountVal = amount.toDoubleOrNull() ?: 0.0
                                        val estFeeBtc = if (toAddress.isNotBlank() && amountVal > 0) {
                                            try { wm.estimateFee(toAddress, amountVal, selectedFeeRate) } 
                                            catch (_: Exception) { selectedFeeRate * 250.0 / 100_000_000.0 }
                                        } else {
                                            selectedFeeRate * 250.0 / 100_000_000.0
                                        }
                                        val totalBtc = amountVal + estFeeBtc

                                        LazyColumn(Modifier.padding(16.dp)) {
                                            item {
                                                Text("Gửi BTC", fontWeight = FontWeight.Bold)
                                                OutlinedTextField(value = toAddress, onValueChange = { toAddress = it }, label = { Text("Địa chỉ") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { qrCallback = { scanned -> toAddress = scanned }; qrLauncher.launch(ScanOptions()) }) { Text("QR") } })
                                                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("BTC") }, modifier = Modifier.fillMaxWidth())
                                                
                                                Text("Phí mạng:", modifier = Modifier.padding(top = 8.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = feeSelection == 0, onClick = { feeSelection = 0 }); Text("Chậm (${fees.slow} sat/vB)") }
                                                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = feeSelection == 1, onClick = { feeSelection = 1 }); Text("Thường (${fees.normal} sat/vB)") }
                                                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = feeSelection == 2, onClick = { feeSelection = 2 }); Text("Nhanh (${fees.fast} sat/vB)") }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(selected = feeSelection == 3, onClick = { feeSelection = 3 }); Text("Tùy chỉnh")
                                                    if (feeSelection == 3) {
                                                        Spacer(Modifier.width(8.dp))
                                                        OutlinedTextField(value = customFee, onValueChange = { customFee = it.filter { c -> c.isDigit() } }, label = { Text("sat/vB") }, singleLine = true, modifier = Modifier.width(120.dp))
                                                    }
                                                }

                                                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                                    Column(Modifier.padding(12.dp)) {
                                                        Text("Ước tính phí: %.8f BTC (≈ $%.2f)".format(estFeeBtc, estFeeBtc * price))
                                                        Text("Tổng (gửi + phí): %.8f BTC".format(totalBtc), fontWeight = FontWeight.Bold)
                                                        Text("Số dư: %.8f BTC".format(currentBalance), fontSize = 12.sp)
                                                    }
                                                }

                                                Button(onClick = { showSendConfirm = true }, Modifier.fillMaxWidth(), enabled = toAddress.isNotBlank() && amountVal > 0 && totalBtc <= currentBalance) { Text("GỬI") }
                                                if (result.isNotEmpty()) Text(result, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

                                                if (showSendConfirm) {
                                                    AlertDialog(
                                                        onDismissRequest = { showSendConfirm = false },
                                                        title = { Text("Xác nhận gửi") },
                                                        text = {
                                                            Column {
                                                                Text("Gửi %.8f BTC tới:".format(amountVal))
                                                                SelectionContainer { Text(toAddress) }
                                                                Spacer(Modifier.height(4.dp))
                                                                Text("Phí: %.8f BTC".format(estFeeBtc))
                                                                Text("Tổng: %.8f BTC".format(totalBtc), fontWeight = FontWeight.Bold)
                                                                Spacer(Modifier.height(8.dp))
                                                                OutlinedTextField(value = sendPass, onValueChange = { sendPass = it; sendErr = "" }, label = { Text("Nhập mật khẩu ví") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                                                if (sendErr.isNotEmpty()) Text(sendErr, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                                                            }
                                                        },
                                                        confirmButton = {
                                                            Button(onClick = {
                                                                lifecycleScope.launch(Dispatchers.IO) {
                                                                    val id = wm.getActive()?.id ?: ""
                                                                    val ok = wm.unlock(id, sendPass)
                                                                    if (!ok) { withContext(Dispatchers.Main) { sendErr = "Sai mật khẩu" }; return@launch }
                                                                    val tx = wm.send(toAddress, amountVal, selectedFeeRate)
                                                                    withContext(Dispatchers.Main) { 
                                                                        result = tx
                                                                        showSendConfirm = false
                                                                        sendPass = ""
                                                                        toAddress = ""
                                                                        amount = ""
                                                                        currentBalance = wm.getBalance()
                                                                    }
                                                                }
                                                            }) { Text("XÁC NHẬN") }
                                                        },
                                                        dismissButton = { OutlinedButton(onClick = { showSendConfirm = false }) { Text("HỦY") } }
                                                    )
                                                }

                                                Spacer(Modifier.height(24.dp)); Text("Nhận BTC", fontWeight = FontWeight.Bold)
                                                val qrBitmap = remember(receiveAddress) {
                                                    val size = 512
                                                    val bitMatrix = QRCodeWriter().encode(receiveAddress.ifEmpty { "bitcoin:" }, BarcodeFormat.QR_CODE, size, size)
                                                    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
                                                }
                                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = null, Modifier.size(220.dp))
                                                    Spacer(Modifier.height(8.dp)); SelectionContainer { Text(receiveAddress) }
                                                }
                                                Button(onClick = { 
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("btc", receiveAddress))
                                                    Toast.makeText(context, "Đã copy", Toast.LENGTH_SHORT).show()
                                                    Handler(Looper.getMainLooper()).postDelayed({ clipboard.clearPrimaryClip() }, 30000)
                                                }, Modifier.fillMaxWidth()) { Text("COPY") }
                                            }
                                        }
                                    }
                                }
                            }
                            if (showRename) {
                                var newName by remember { mutableStateOf(walletName) }
                                AlertDialog(
                                    onDismissRequest = { showRename = false },
                                    confirmButton = {},
                                    title = { Text("Đổi tên ví") },
                                    text = {
                                        Column {
                                            OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    wm.getActive()?.let { activeWallet ->
                                                        wm.rename(activeWallet.id, newName)
                                                        withContext(Dispatchers.Main) { walletName = newName; showRename = false }
                                                    }
                                                }
                                            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("LƯU") }
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(onClick = { showRename = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("HỦY") }
                                        }
                                    }
                                )
                            }
                            if (showDetails) {
                                var passCheck by remember { mutableStateOf("") }; var verified by remember { mutableStateOf(false) }; var err by remember { mutableStateOf("") }
                                val address = wm.getAddress(); val seed = if (verified) wm.getSeed() else ""
                                AlertDialog(
                                    onDismissRequest = { showDetails = false },
                                    confirmButton = {},
                                    title = { Text(if (!verified) "Xác thực mật khẩu" else "Chi tiết ví") },
                                    text = {
                                        if (!verified) {
                                            Column {
                                                Text("Nhập mật khẩu để xem seed")
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedTextField(value = passCheck, onValueChange = { passCheck = it; err = "" }, label = { Text("Mật khẩu") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                                                Spacer(Modifier.height(16.dp))
                                                Button(onClick = {
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        val id = wm.getActive()?.id ?: ""
                                                        val ok = wm.unlock(id, passCheck)
                                                        withContext(Dispatchers.Main) { if (ok) verified = true else err = "Sai mật khẩu" }
                                                    }
                                                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("XÁC NHẬN") }
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedButton(onClick = { showDetails = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("ĐÓNG") }
                                            }
                                        } else {
                                            Column {
                                                Text("Tên: $walletName", fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(8.dp)); Text("Địa chỉ:"); SelectionContainer { Text(address) }
                                                Button(onClick = { 
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("addr", address))
                                                    Toast.makeText(context, "Đã copy địa chỉ", Toast.LENGTH_SHORT).show()
                                                    Handler(Looper.getMainLooper()).postDelayed({ clipboard.clearPrimaryClip() }, 30000)
                                                }, modifier = Modifier.fillMaxWidth()) { Text("Copy địa chỉ") }
                                                Spacer(Modifier.height(8.dp)); Text("Seed 12 từ:"); SelectionContainer { Text(seed) }
                                                Button(onClick = { 
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("seed", seed))
                                                    Toast.makeText(context, "Đã copy seed", Toast.LENGTH_SHORT).show()
                                                    Handler(Looper.getMainLooper()).postDelayed({ clipboard.clearPrimaryClip() }, 30000)
                                                }, modifier = Modifier.fillMaxWidth()) { Text("Copy seed") }
                                                Spacer(Modifier.height(12.dp))
                                                OutlinedButton(onClick = { showDetails = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("ĐÓNG") }
                                            }
                                        }
                                    }
                                )
                            }
                            if (showChangePass) {
                                var oldPass by remember { mutableStateOf("") }
                                var newPass1 by remember { mutableStateOf("") }
                                var newPass2 by remember { mutableStateOf("") }
                                var err by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showChangePass = false },
                                    confirmButton = {},
                                    title = { Text("Đổi mật khẩu") },
                                    text = {
                                        Column {
                                            OutlinedTextField(value = oldPass, onValueChange = { oldPass = it; err = "" }, label = { Text("Mật khẩu cũ") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                            OutlinedTextField(value = newPass1, onValueChange = { newPass1 = it; err = "" }, label = { Text("Mật khẩu mới (>=8)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                            OutlinedTextField(value = newPass2, onValueChange = { newPass2 = it; err = "" }, label = { Text("Nhập lại") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = {
                                                if (newPass1.length < 8) { err = "Mật khẩu mới >=8 ký tự"; return@Button }
                                                if (newPass1 != newPass2) { err = "Không khớp"; return@Button }
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val id = wm.getActive()?.id ?: ""
                                                    val ok = wm.changePassword(id, oldPass, newPass1)
                                                    withContext(Dispatchers.Main) {
                                                        if (ok) {
                                                            Toast.makeText(context, "Đổi mật khẩu thành công", Toast.LENGTH_SHORT).show()
                                                            showChangePass = false
                                                        } else {
                                                            err = "Sai mật khẩu cũ"
                                                        }
                                                    }
                                                }
                                            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("LƯU") }
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(onClick = { showChangePass = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("HỦY") }
                                        }
                                    }
                                )
                            }
                            if (showDeleteConfirm) {
                                var pass by remember { mutableStateOf("") }
                                var err by remember { mutableStateOf("") }
                                var isDeleting by remember { mutableStateOf(false) }
                                AlertDialog(
                                    onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
                                    confirmButton = {},
                                    title = { Text("⚠️ Xác nhận xóa ví") },
                                    text = {
                                        Column {
                                            Text("Hành động này KHÔNG thể hoàn tác!")
                                            Text("Nhập mật khẩu để xác nhận:", fontSize = 12.sp)
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedTextField(value = pass, onValueChange = { pass = it; err = "" }, label = { Text("Mật khẩu hiện tại") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !isDeleting)
                                            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                            Spacer(Modifier.height(16.dp))
                                            Button(
                                                onClick = {
                                                    isDeleting = true
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        val id = wm.getActive()?.id ?: ""
                                                        val ok = wm.unlock(id, pass)
                                                        if (!ok) {
                                                            withContext(Dispatchers.Main) { err = "Sai mật khẩu"; isDeleting = false }
                                                            return@launch
                                                        }
                                                        wm.delete(id)
                                                        val stillHas = wm.hasWallets()
                                                        withContext(Dispatchers.Main) {
                                                            hasWallet = stillHas
                                                            isLocked = false
                                                            walletName = ""
                                                            showDeleteConfirm = false
                                                            Toast.makeText(context, "Đã xóa ví vĩnh viễn", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                enabled = !isDeleting,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                                modifier = Modifier.fillMaxWidth().height(48.dp)
                                            ) { Text(if (isDeleting) "Đang xóa..." else "XÓA VĨNH VIỄN") }
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(onClick = { showDeleteConfirm = false }, enabled = !isDeleting, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("HỦY") }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.lock() } catch (_: Exception) {}
    }
}