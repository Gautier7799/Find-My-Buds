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
import androidx.compose.material3.dynamicLightColorScheme
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
        if (result.values.any { it } && finder.hasPermissions()) finder.start()
    }
    LaunchedEffect(Unit) { finder.loadSavedLocation() }
    val selected = finder.devices[finder.selectedAddress]
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme(
            primary = Color(0xFF526B9A), onPrimary = Color.White,
            background = Color(0xFFF7F7FC), surface = Color.White,
            surfaceVariant = Color(0xFFE9EAF2)
        )
    }
    val muted = scheme.onSurfaceVariant

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Headphones, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(27.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("العثور على سماعاتي", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                        Text("Find My Buds", fontSize = 12.sp, color = muted)
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = if (finder.scanning) scheme.primaryContainer else scheme.surfaceVariant) {
                        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (finder.scanning) scheme.primary else muted, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if (finder.scanning) "يبحث الآن" else "متوقف", fontSize = 11.sp, color = scheme.onSurface)
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selected?.name ?: "Mibro Earbuds 2", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Text(if (selected != null) finder.signalLabel(selected.rssi) else "ابدأ البحث لاكتشاف السماعة القريبة", color = muted, fontSize = 13.sp)
                        Spacer(Modifier.height(20.dp))
                        Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { selected?.let { finder.signalPercent(it.rssi) } ?: 0.08f },
                                modifier = Modifier.fillMaxSize(), strokeWidth = 9.dp,
                                color = scheme.primary, trackColor = scheme.surfaceVariant
                            )
                            Surface(shape = CircleShape, color = scheme.secondaryContainer, modifier = Modifier.size(126.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Headphones, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(62.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(selected?.let { "\${it.rssi} dBm" } ?: "بانتظار إشارة Bluetooth", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Text("مؤشر تقريبي يتأثر بالجدران والاتجاه", fontSize = 11.sp, color = muted)
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                if (finder.hasPermissions()) {
                                    if (finder.scanning) finder.stop() else finder.start()
                                } else permissionLauncher.launch(permissions.toTypedArray())
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                            Spacer(Modifier.width(9.dp))
                            Text(if (finder.scanning) "إيقاف البحث" else "ابدأ البحث", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        if (selected != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("سيصدر الهاتف تنبيهاً عند الاقتراب من السماعة", color = muted, fontSize = 11.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الأجهزة القريبة", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = scheme.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text("\${finder.devices.size} مكتشف", color = muted, fontSize = 12.sp)
                }
                if (finder.devices.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Radar, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("لم نعثر على أجهزة بعد", fontWeight = FontWeight.Medium, color = scheme.onSurface)
                                Text("ضع السماعات بالقرب من الهاتف وابدأ البحث", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(finder.devices.values.sortedByDescending { it.rssi }) { bud ->
                            Card(
                                onClick = { finder.select(bud.address) }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = if (finder.selectedAddress == bud.address) scheme.primaryContainer else scheme.surface)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Headphones, contentDescription = null, tint = scheme.primary)
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(bud.name, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                                        Text(bud.address, color = muted, fontSize = 10.sp)
                                    }
                                    Text("\${bud.rssi} dBm", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("آخر مكان تم رصدها فيه", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = scheme.onSurface)
                                Text(finder.lastLocationText, color = muted, fontSize = 11.sp)
                            }
                        }
                        Divider(color = scheme.surfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(9.dp))
                            Text("التنبيه يصدر من الهاتف، وليس من السماعة", color = muted, fontSize = 11.sp)
                        }
                    }
                }
                Text(finder.status, color = muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
