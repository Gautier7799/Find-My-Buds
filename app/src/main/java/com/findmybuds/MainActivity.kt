package com.findmybuds

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    private var lastAlertBand = -1
    private var scanRetries = 0
    var scanResultCount by mutableIntStateOf(0)
        private set
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rssiSamples = mutableMapOf<String, ArrayDeque<Int>>()
    private val smoothedRssi = mutableMapOf<String, Float>()

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
        if (scanning) return
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
            try { callback?.let { scanner?.stopScan(it) } } catch (_: Exception) {}
            callback = null
            scanResultCount = 0
            scanner = bt.bluetoothLeScanner
            val activeScanner = scanner
            if (activeScanner == null) {
                status = "ماسح Bluetooth LE غير متاح"
                return
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { handleResult(it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    scanning = false
                    status = "فشل مسح BLE: ${scanErrorText(errorCode)} (رمز $errorCode). اضغط لإعادة المحاولة"
                }
            }
            callback = cb
            // Start with Android's platform-default unfiltered BLE scan.
            // Avoid optional controller matching modes while diagnosing discovery.
            activeScanner.startScan(cb)
            scanning = true
            status = "بدأ مسح BLE؛ ننتظر إعلانات الأجهزة القريبة"
            mainHandler.postDelayed({
                if (scanning && scanResultCount == 0) {
                    status = "المسح بدأ لكن لم تصل أي إعلانات BLE خلال 12 ثانية؛ نحتاج التحقق من بث الأجهزة وإذن المسح"
                }
            }, 12_000L)
        } catch (e: SecurityException) {
            scanning = false
            status = "لم تُمنح صلاحية البحث عن الأجهزة القريبة"
        } catch (e: Exception) {
            scanning = false
            status = "تعذر بدء البحث: ${e.message ?: "خطأ غير معروف"}"
        }
    }

    private fun scanErrorText(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "المسح بدأ مسبقاً"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "فشل تسجيل التطبيق لدى Bluetooth"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "خطأ داخلي في Bluetooth"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "ميزة المسح غير مدعومة"
        5 -> "موارد Bluetooth غير كافية"
        6 -> "طلبات المسح متكررة بسرعة"
        else -> "خطأ غير معروف"
    }

    private fun handleResult(result: ScanResult) {
        scanResultCount++
        status = "وصلت نتائج BLE: $scanResultCount"
        val address = try { result.device.address } catch (_: SecurityException) { return }
        if (address.isNullOrBlank()) return
        val name = try { result.device.name ?: result.scanRecord?.deviceName ?: "جهاز Bluetooth غير مسمى" }
                   catch (_: SecurityException) { "سماعة Bluetooth" }
        val now = System.currentTimeMillis()
        val samples = rssiSamples.getOrPut(address) { ArrayDeque() }
        samples.addLast(result.rssi)
        while (samples.size > 3) samples.removeFirst()
        val median = samples.toList().sorted()[samples.size / 2].toFloat()
        val previous = smoothedRssi[address]
        val filtered = if (previous == null) median else (previous * 0.35f + median * 0.65f)
        smoothedRssi[address] = filtered
        val stableRssi = filtered.toInt()
        devices[address] = Bud(address, name, stableRssi, now)
        scanRetries = 0
        if (selectedAddress == address) {
            saveLastLocation()
            maybeBeep(stableRssi)
        }
    }

    fun pruneStaleDevices() {
        // Earbuds may advertise intermittently; do not drop them after a brief radio gap.
        val cutoff = System.currentTimeMillis() - 30000L
        val stale = devices.values.filter { it.seenAt < cutoff }.map { it.address }
        stale.forEach { address ->
            devices.remove(address)
            if (selectedAddress != address) {
                rssiSamples.remove(address)
                smoothedRssi.remove(address)
            }
        }
        if (selectedAddress != null && selectedAddress !in devices.keys) {
            lastAlertBand = -1
            status = "انقطعت إشارة السماعة؛ نواصل البحث عنها"
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
        lastAlertBand = -1
        status = "تم اختيار السماعة. اضغط ابدأ البحث لتتبع الإشارة"
        devices[address]?.let { saveLastLocation() }
    }

    fun alertPhone() {
        playPhoneTone(100, 1200)
    }

    private fun maybeBeep(rssi: Int) {
        val band = when {
            rssi >= -60 -> 3
            rssi >= -75 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
        if (band == 0) {
            lastAlertBand = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        val interval = when (band) {
            3 -> 850L
            2 -> 1350L
            else -> 2100L
        }
        if (band != lastAlertBand || now - lastBeepAt >= interval) {
            val volume = when (band) {
                3 -> 82
                2 -> 68
                else -> 52
            }
            playPhoneTone(volume, if (band == 3) 300 else 220)
            lastBeepAt = now
        }
        lastAlertBand = band
    }

    private fun playPhoneTone(volume: Int, durationMs: Int) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume.coerceIn(0, 100))
            val started = tone.startTone(ToneGenerator.TONE_PROP_BEEP2, durationMs)
            if (!started) status = "تعذر بدء النغمة؛ تحقق من مستوى صوت الوسائط"
            mainHandler.postDelayed(
                { try { tone.release() } catch (_: Exception) {} },
                durationMs + 120L
            )
        } catch (_: Exception) {
            status = "تعذر تشغيل التنبيه الصوتي"
        }
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
                Modifier.size(size * 0.42f).clip(CircleShape).background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = "Find My Buds",
                    tint = Color(0xFF0082FF),
                    modifier = Modifier.size(size * 0.20f)
                )
            }
        }
    }
}

@Composable
private fun SignalGauge(
    rssi: Int?,
    progress: Float,
    accent: Color,
    track: Color,
    contentColor: Color
) {
    Box(Modifier.size(208.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f - 7.dp.toPx()
            listOf(0.58f, 0.78f, 1f).forEach { factor ->
                drawCircle(
                    color = track.copy(alpha = if (factor == 1f) 0.65f else 0.38f),
                    radius = maxRadius * factor,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - maxRadius, center.y - maxRadius),
                size = androidx.compose.ui.geometry.Size(maxRadius * 2f, maxRadius * 2f),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Surface(
            shape = CircleShape,
            color = track.copy(alpha = 0.72f),
            modifier = Modifier.size(124.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, contentColor.copy(alpha = 0.10f))
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    rssi?.toString() ?: "—",
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text("dBm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = contentColor.copy(alpha = 0.68f))
                Spacer(Modifier.height(5.dp))
                Text("قوة الإشارة", fontSize = 10.sp, color = contentColor.copy(alpha = 0.72f))
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
    LaunchedEffect(Unit) {
        finder.loadSavedLocation()
        while (true) {
            delay(1000L)
            finder.pruneStaleDevices()
        }
    }
    val selected = finder.devices[finder.selectedAddress]
    val context = LocalContext.current
    val permissionRefreshVersion = permissionRefresh
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (isDark) {
        darkColorScheme(
            primary = Color(0xFFB5C8F2), onPrimary = Color(0xFF172A4D),
            background = Color(0xFF111318), surface = Color(0xFF1A1D24),
            surfaceVariant = Color(0xFF30343D)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF526B9A), onPrimary = Color.White,
            background = Color(0xFFF7F7FC), surface = Color.White,
            surfaceVariant = Color(0xFFE9EAF2)
        )
    }
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
                } else if (showHistory) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FindMyBudsLogoMark(size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("آخر ظهور", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                            Text("تفاصيل آخر رصد محفوظ للسماعة المحددة", fontSize = 12.sp, color = muted)
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(shape = RoundedCornerShape(18.dp), color = scheme.secondaryContainer) {
                                Icon(Icons.Default.History, null, tint = scheme.onSecondaryContainer, modifier = Modifier.padding(15.dp).size(30.dp))
                            }
                            Text("آخر مكان شوهدت فيه", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                            Text(finder.lastLocationText, fontSize = 14.sp, color = scheme.onSurfaceVariant)
                            HorizontalDivider(color = scheme.surfaceVariant)
                            Text(
                                "يُسجّل هذا المكان من موقع الهاتف وقت رصد السماعة عبر Bluetooth، وليس موقعًا مباشرًا أو GPS مدمجًا في السماعة.",
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showHistory = false },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(17.dp)
                            ) {
                                Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(8.dp))
                                Text("العودة إلى البحث")
                            }
                        }
                    }
                    Text("للحصول على سجل أحدث، اختر السماعة وابدأ البحث عندما تكون بالقرب منها.", color = muted, fontSize = 12.sp)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FindMyBudsLogoMark(size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("FIND MY BUDS", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, color = scheme.onBackground)
                            Text("رفيقك للعثور على سماعاتك · v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = muted)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (finder.scanning) scheme.primaryContainer else scheme.surfaceVariant
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(if (finder.scanning) Color(0xFF20A66A) else muted, CircleShape))
                                Spacer(Modifier.width(7.dp))
                                Text(if (finder.scanning) "بحث مباشر" else "متوقف", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                            }
                        }
                    }

                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(32.dp))
                            .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer)))
                            .border(1.dp, scheme.onPrimaryContainer.copy(alpha = 0.10f), RoundedCornerShape(32.dp))
                            .padding(24.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("وضع العثور", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = scheme.onPrimaryContainer.copy(alpha = 0.66f))
                                    Spacer(Modifier.height(4.dp))
                                    Text("العثور على سماعاتك", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
                                    Text("تابع قوة الإشارة لحظة بلحظة", fontSize = 12.sp, color = scheme.onPrimaryContainer.copy(alpha = 0.76f))
                                }
                                Surface(shape = RoundedCornerShape(14.dp), color = scheme.surface.copy(alpha = 0.65f)) {
                                    Icon(Icons.Default.Headphones, null, tint = scheme.primary, modifier = Modifier.padding(10.dp).size(23.dp))
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(selected?.name ?: "Mibro Earbuds 2", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                selected?.let { finder.signalLabel(it.rssi) } ?: "بانتظار اكتشاف السماعة",
                                fontSize = 12.sp,
                                color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                            Spacer(Modifier.height(18.dp))
                            SignalGauge(
                                rssi = selected?.rssi,
                                progress = selected?.let { finder.signalPercent(it.rssi) } ?: 0f,
                                accent = scheme.primary,
                                track = scheme.surface.copy(alpha = 0.58f),
                                contentColor = scheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (selected == null) "اختر السماعة من الأجهزة المكتشفة" else "تتغير القراءة مباشرة مع اقترابك وابتعادك",
                                fontSize = 11.sp,
                                color = scheme.onPrimaryContainer.copy(alpha = 0.72f)
                            )
                            Spacer(Modifier.height(22.dp))
                            Button(
                                onClick = {
                                    if (finder.hasPermissions()) {
                                        if (finder.scanning) finder.stop() else finder.start()
                                    } else permissionLauncher.launch(bluetoothPermissions)
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)
                            ) {
                                Icon(if (finder.scanning) Icons.Default.Bluetooth else Icons.Default.BluetoothSearching, null)
                                Spacer(Modifier.width(9.dp))
                                Text(if (finder.scanning) "إيقاف البحث" else "ابدأ البحث الآن", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            TextButton(onClick = { finder.alertPhone() }) {
                                Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(18.dp), tint = scheme.onPrimaryContainer)
                                Spacer(Modifier.width(7.dp))
                                Text("تشغيل نغمة على الهاتف", color = scheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("الأجهزة القريبة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                            Text("اختر السماعة لمتابعة قوة إشارتها", fontSize = 11.sp, color = muted)
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = scheme.secondaryContainer) {
                            Text("${finder.devices.size} أجهزة", modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontSize = 11.sp, color = scheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (finder.devices.isEmpty()) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = scheme.surface)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(17.dp), color = scheme.secondaryContainer) {
                                    Icon(Icons.Default.Radar, null, tint = scheme.onSecondaryContainer, modifier = Modifier.padding(13.dp).size(28.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("المسح جاهز", fontWeight = FontWeight.Bold, color = scheme.onSurface)
                                    Text("ابدأ البحث لتظهر أجهزة Bluetooth القريبة هنا.", color = muted, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            finder.devices.values.sortedByDescending { it.rssi }.take(6).forEach { bud ->
                                val isSelected = finder.selectedAddress == bud.address
                                Card(
                                    onClick = { finder.select(bud.address) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) scheme.primaryContainer else scheme.surface),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(15.dp), color = if (isSelected) scheme.surface.copy(alpha = 0.75f) else scheme.secondaryContainer) {
                                            Icon(Icons.Default.Headphones, null, tint = scheme.primary, modifier = Modifier.padding(11.dp).size(24.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(bud.name, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, fontSize = 14.sp)
                                            Text(if (isSelected) "السماعة المحددة" else "اضغط لتحديد هذا الجهاز", color = muted, fontSize = 10.sp)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("${bud.rssi} dBm", fontWeight = FontWeight.Bold, color = scheme.primary, fontSize = 13.sp)
                                            Text(finder.signalLabel(bud.rssi), color = muted, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(15.dp), color = scheme.secondaryContainer) {
                                Icon(Icons.Default.LocationOn, null, tint = scheme.onSecondaryContainer, modifier = Modifier.padding(11.dp).size(23.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("آخر مكان شوهدت فيه", fontWeight = FontWeight.Bold, color = scheme.onSurface, fontSize = 13.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(finder.lastLocationText, color = muted, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.History, null, tint = muted)
                        }
                    }
                    Text("قوة الإشارة تقديرية وتتأثر بالجدران والمسافة واتجاه السماعة.", color = muted, fontSize = 10.sp)
                    Text(finder.status, color = muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
