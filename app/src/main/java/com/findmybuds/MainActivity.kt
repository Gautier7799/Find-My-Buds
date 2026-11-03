package com.findmybuds

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Bud(val address: String, val name: String, val rssi: Int, val seenAt: Long)

class MainActivity : ComponentActivity() {
    private lateinit var finder: BluetoothFinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finder = BluetoothFinder(this)
        setContent { FindMyBudsScreen(finder) }
    }

    override fun onDestroy() {
        if (::finder.isInitialized) finder.stop()
        super.onDestroy()
    }
}

class BluetoothFinder(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var lastBeepAt = 0L
    private var lastCloseState = false

    val devices = mutableStateMapOf<String, Bud>()
    var scanning by mutableStateOf(false)
        private set
    var selectedAddress by mutableStateOf<String?>(null)
    var lastLocationText by mutableStateOf("لا يوجد موقع محفوظ بعد")
    var status by mutableStateOf("جاهز للبحث")

    fun hasPermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connect = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return scan && connect && location
    }

    fun start() {
        if (!hasPermissions()) {
            status = "امنح صلاحيات Bluetooth والموقع أولاً"
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            status = "فعّل Bluetooth من إعدادات الهاتف"
            return
        }
        try {
            scanner = bt.bluetoothLeScanner
            if (scanner == null) {
                status = "ماسح Bluetooth LE غير متاح"
                return
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val address = device.address ?: return
                    val name = try { device.name ?: result.scanRecord?.deviceName ?: "جهاز Bluetooth غير مسمى" }
                               catch (_: SecurityException) { "سماعة Bluetooth" }
                    devices[address] = Bud(address, name, result.rssi, System.currentTimeMillis())
                    if (selectedAddress == address) {
                        saveLastLocation()
                        maybeBeep(result.rssi)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    scanning = false
                    status = "فشل البحث (رمز $errorCode)"
                }
            }
            callback = cb
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner?.startScan(null, settings, cb)
            scanning = true
            status = "يتم البحث عن سماعات Bluetooth القريبة"
        } catch (e: SecurityException) {
            status = "لم تُمنح صلاحية البحث عن الأجهزة القريبة"
        } catch (e: Exception) {
            status = "تعذر بدء البحث: ${e.message ?: "خطأ غير معروف"}"
        }
    }

    fun stop() {
        try { callback?.let { scanner?.stopScan(it) } } catch (_: Exception) {}
        callback = null
        scanning = false
        status = "توقف البحث"
    }

    fun select(address: String) {
        selectedAddress = address
        status = "تم اختيار السماعة. اضغط ابدأ البحث لتتبع الإشارة"
        devices[address]?.let { saveLastLocation() }
    }

    private fun maybeBeep(rssi: Int) {
        val close = rssi >= -62
        val now = SystemClock.elapsedRealtime()
        if (close && (!lastCloseState || now - lastBeepAt > 3500)) {
            try {
                ToneGenerator(AudioManager.STREAM_ALARM, 75).startTone(ToneGenerator.TONE_PROP_BEEP, 450)
            } catch (_: Exception) {}
            lastBeepAt = now
        }
        lastCloseState = close
    }

    private fun saveLastLocation() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val loc: Location? = providers.mapNotNull { provider ->
                try { if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null }
                catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            if (loc != null) {
                val prefs = context.getSharedPreferences("finder", Context.MODE_PRIVATE)
                prefs.edit().putString("last_location", "${loc.latitude}, ${loc.longitude}")
                    .putLong("last_seen", System.currentTimeMillis()).apply()
                lastLocationText = "آخر إحداثيات: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            }
        } catch (_: Exception) {}
    }

    fun loadSavedLocation() {
        val prefs = context.getSharedPreferences("finder", Context.MODE_PRIVATE)
        val coords = prefs.getString("last_location", null)
        val time = prefs.getLong("last_seen", 0L)
        if (coords != null && time > 0) {
            val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))
            lastLocationText = "$coords · $date"
        }
    }

    fun signalLabel(rssi: Int): String = when {
        rssi >= -55 -> "قريب جداً"
        rssi >= -67 -> "قريب"
        rssi >= -80 -> "متوسط"
        else -> "ضعيف / بعيد"
    }

    fun signalPercent(rssi: Int): Float = ((rssi + 100) / 60f).coerceIn(0f, 1f)
}

@Composable
fun FindMyBudsScreen(finder: BluetoothFinder) {
    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) finder.start()
    }

    LaunchedEffect(Unit) { finder.loadSavedLocation() }
    val selected = finder.devices[finder.selectedAddress]
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = Color(0xFFB7F7D2),
            background = Color(0xFF0B1011),
            surface = Color(0xFF171F20)
        )
    }
    val bg = scheme.background
    val panel = scheme.surface
    val mint = scheme.primary
    val muted = scheme.onSurfaceVariant

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = mint, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("FIND MY BUDS", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Bluetooth earbud locator", color = muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(9.dp).background(if (finder.scanning) mint else muted, CircleShape))
                }
                Spacer(Modifier.height(24.dp))
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Radar, null, tint = mint, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(selected?.name ?: "لم يتم اختيار سماعة", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            selected?.let { "${finder.signalLabel(it.rssi)}  ·  ${it.rssi} dBm" } ?: "اختر سماعتك من الأجهزة المكتشفة",
                            color = muted, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        if (selected != null) {
                            LinearProgressIndicator(
                                progress = { finder.signalPercent(selected.rssi) },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = mint,
                                trackColor = Color(0xFF33413D)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("قوة الإشارة تتغير حسب الجدران والاتجاه والبطارية", color = muted, fontSize = 11.sp)
                        } else {
                            Text("ابدأ البحث ثم اختر سماعتك", color = muted)
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                if (finder.hasPermissions()) {
                                    if (finder.scanning) finder.stop() else finder.start()
                                } else permissionLauncher.launch(permissions.toTypedArray())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF092016)),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Default.BluetoothSearching, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (finder.scanning) "إيقاف البحث" else "ابدأ البحث", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الأجهزة المكتشفة", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${finder.devices.size} جهاز", color = muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(finder.devices.values.sortedByDescending { it.rssi }) { bud ->
                        Card(
                            onClick = { finder.select(bud.address) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (finder.selectedAddress == bud.address) Color(0xFF263B34) else panel
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Headphones, null, tint = mint)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bud.name, fontWeight = FontWeight.Medium)
                                    Text(bud.address, color = muted, fontSize = 10.sp)
                                }
                                Text("${bud.rssi} dBm", color = mint, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, tint = mint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("آخر مكان شوهدت فيه", fontWeight = FontWeight.SemiBold)
                        }
                        Text(finder.lastLocationText, color = muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, tint = mint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("تنبيه الهاتف عند اقتراب الإشارة", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(finder.status, color = muted, fontSize = 11.sp)
                Text("رنين السماعة نفسها يتطلب معرفة موديلها وبروتوكول الشركة.", color = Color(0xFFFFD18A), fontSize = 11.sp)
            }
        }
    }
}
