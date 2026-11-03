package com.findmybuds

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
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
        val scan = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        val connect = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return scan && connect
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    fun start() {
        if (!hasPermissions()) {
            status = "امنح إذن الأجهزة القريبة لبدء البحث"
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

    fun alertPhone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 900)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 1000L)
        } catch (_: Exception) { status = "تعذر تشغيل التنبيه الصوتي" }
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
fun FindMyBudsLogoMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(size * 0.78f).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(size * 0.48f).clip(CircleShape).background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = "Find My Buds",
                    tint = Color(0xFF0082FF),
                    modifier = Modifier.size(size * 0.34f)
                )
            }
        }
    }
}

@Composable
fun FindMyBudsScreen(finder: BluetoothFinder) {
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
        if (finder.hasPermissions()) finder.start()
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
        if (finder.hasLocationPermission()) finder.status = "تم منح إذن الموقع لحفظ آخر مكان رُصدت فيه السماعة"
    }
    LaunchedEffect(Unit) { finder.loadSavedLocation() }
    val selected = finder.devices[finder.selectedAddress]
    val context = LocalContext.current
    val permissionRefreshVersion = permissionRefresh
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context) else lightColorScheme(
        primary = Color(0xFF526B9A), onPrimary = Color.White,
        background = Color(0xFFF7F7FC), surface = Color.White,
        surfaceVariant = Color(0xFFE9EAF2)
    )
    val muted = scheme.onSurfaceVariant

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                NavigationBar(containerColor = scheme.surface, tonalElevation = 2.dp) {
                    NavigationBarItem(!showHistory && !showSettings, { showHistory = false; showSettings = false }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("البحث") })
                    NavigationBarItem(showHistory, { showHistory = true; showSettings = false }, icon = { Icon(Icons.Default.History, null) }, label = { Text("آخر ظهور") })
                    NavigationBarItem(showSettings, { showSettings = true; showHistory = false }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("الإعدادات") })
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showSettings) {
                    Text("إعدادات التطبيق", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                    Text("تحكم في اتصال Bluetooth وأذونات النظام اللازمة للعثور على السماعات.", color = muted, fontSize = 13.sp)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bluetooth, null, tint = scheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Bluetooth", fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                    Text(if (finder.isBluetoothEnabled()) "مفعّل" else "غير مفعّل", color = muted, fontSize = 12.sp)
                                }
                                TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("إعدادات") }
                            }
                            HorizontalDivider(color = scheme.surfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Radar, null, tint = scheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("الأجهزة القريبة", fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                    Text(if (finder.hasPermissions()) "الإذن ممنوح" else "الإذن مطلوب للبحث", color = muted, fontSize = 12.sp)
                                }
                                Text(if (finder.hasPermissions()) "مسموح" else "غير مسموح", color = if (finder.hasPermissions()) scheme.primary else scheme.error, fontSize = 12.sp)
                            }
                            Button(onClick = { permissionLauncher.launch(bluetoothPermissions) }, modifier = Modifier.fillMaxWidth()) {
                                Text("طلب إذن الأجهزة القريبة")
                            }
                            HorizontalDivider(color = scheme.surfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, null, tint = scheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("الموقع", fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                    Text(if (finder.hasLocationPermission()) "مسموح — يمكن حفظ آخر موقع" else "اختياري لحفظ آخر موقع شوهدت فيه السماعة", color = muted, fontSize = 12.sp)
                                }
                            }
                            OutlinedButton(onClick = {
                                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }, modifier = Modifier.fillMaxWidth()) { Text("منح إذن الموقع") }
                            Text("إذن الموقع ليس شرطاً للبحث عبر Bluetooth على Android 12 وما بعده. إذا لم تمنحه، سيظل البحث متاحاً لكن لن تُحفظ إحداثيات آخر ظهور.", color = muted, fontSize = 12.sp)
                            HorizontalDivider(color = scheme.surfaceVariant)
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }, modifier = Modifier.fillMaxWidth()) { Text("فتح إعدادات التطبيق في النظام") }
                        }
                    }
                    Text(if (permissionRefreshVersion >= 0) finder.status else "", color = muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FindMyBudsLogoMark(size = 54.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("FIND MY BUDS", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp, color = scheme.onBackground)
                        Text("اعثر على سماعاتك بسهولة", fontSize = 12.sp, color = muted)
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = if (finder.scanning) scheme.primaryContainer else scheme.surfaceVariant) {
                        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (finder.scanning) scheme.primary else muted, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if (finder.scanning) "يبحث الآن" else "جاهز", fontSize = 11.sp, color = scheme.onSurface)
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = scheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selected?.name ?: "Mibro Earbuds 2", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Spacer(Modifier.height(5.dp))
                        Text(if (selected != null) finder.signalLabel(selected.rssi) else "ضع السماعات بالقرب من الهاتف وابدأ البحث", fontSize = 13.sp, color = muted)
                        Spacer(Modifier.height(22.dp))
                        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { selected?.let { finder.signalPercent(it.rssi) } ?: 0.035f }, modifier = Modifier.fillMaxSize(), strokeWidth = 10.dp, color = scheme.primary, trackColor = scheme.surfaceVariant)
                            Surface(shape = CircleShape, color = scheme.secondaryContainer, modifier = Modifier.size(150.dp)) {
                                Box(contentAlignment = Alignment.Center) { FindMyBudsLogoMark(size = 92.dp) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(selected?.let { "${it.rssi} dBm" } ?: "بانتظار إشارة Bluetooth", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Text("قوة الإشارة تقديرية وتتأثر بالجدران والاتجاه", fontSize = 11.sp, color = muted)
                        Spacer(Modifier.height(22.dp))
                        Button(onClick = {
                            if (finder.hasPermissions()) { if (finder.scanning) finder.stop() else finder.start() }
                            else permissionLauncher.launch(bluetoothPermissions)
                        }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Default.BluetoothSearching, null)
                            Spacer(Modifier.width(9.dp))
                            Text(if (finder.scanning) "إيقاف البحث" else "ابدأ العثور على السماعة", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { finder.alertPhone() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Default.VolumeUp, null)
                            Spacer(Modifier.width(9.dp))
                            Text("تشغيل تنبيه الهاتف")
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الأجهزة القريبة", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = scheme.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text("${finder.devices.size} جهاز", color = muted, fontSize = 12.sp)
                }
                if (finder.devices.isEmpty()) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Radar, null, tint = scheme.primary, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("لم نعثر على أجهزة بعد", fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                Text("ستظهر السماعات والأجهزة القريبة هنا", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        finder.devices.values.sortedByDescending { it.rssi }.take(5).forEach { bud ->
                            Card(onClick = { finder.select(bud.address) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (finder.selectedAddress == bud.address) scheme.primaryContainer else scheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Headphones, null, tint = scheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(bud.name, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                        Text(bud.address, color = muted, fontSize = 10.sp)
                                    }
                                    Text("${bud.rssi} dBm", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("آخر مكان شوهدت فيه", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = scheme.onSurface)
                                Text(finder.lastLocationText, color = muted, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = scheme.surfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("التنبيه الصوتي يصدر من الهاتف", color = muted, fontSize = 11.sp)
                        }
                    }
                }
                if (showHistory) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("آخر رصد محفوظ", fontWeight = FontWeight.Bold, color = scheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Text(finder.lastLocationText, color = muted)
                        }
                    }
                }
                Text(finder.status, color = muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
