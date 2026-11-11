package io.github.a_m_protasov.a747

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.a_m_protasov.a747.device.BluetoothTransport
import io.github.a_m_protasov.a747.device.UsbTransport
import io.github.a_m_protasov.a747.ui.A747Root
import io.github.a_m_protasov.a747.ui.A747Theme
import kotlinx.coroutines.launch

class A747App : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
    }

    companion object {
        lateinit var repo: Repo
            private set
    }
}

class MainActivity : ComponentActivity() {
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val repo get() = A747App.repo

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> repo.scope.launch { repo.disconnect() }
            }
        }
    }

    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingBluetooth?.let { if (granted) connectBluetooth(it) }
        pendingBluetooth = null
        if (!granted) repo.message.value = getString(R.string.bluetooth_permission_is_needed_to_talk_to_the_lo)
    }
    private var pendingBluetooth: BluetoothDevice? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val theme by repo.theme.collectAsState()
            val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            // status and navigation bar icons follow the app's theme, not only the system's
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            A747Theme(dark) {
                A747Root(
                    onConnectUsb = ::connectUsb,
                    pairedDevices = ::pairedDevices,
                    onConnectBluetooth = ::connectBluetooth,
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) connectUsb()
        else if (repo.device.value is DeviceState.Disconnected && UsbTransport.find(usb) != null) connectUsb()
    }

    /** Connects to a plugged-in logger, asking for USB permission first if needed. */
    fun connectUsb() {
        val device: UsbDevice = UsbTransport.find(usb) ?: run {
            repo.message.value = getString(R.string.logger_not_found_check_that_the_cable_carries_da)
            return
        }
        if (!usb.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(device, pi)
            repo.message.value = getString(R.string.allow_access_to_the_logger_when_android_asks)
            return
        }
        repo.connect(Link.USB) { UsbTransport.open(usb, device) }
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<Pair<String, BluetoothDevice>> {
        if (!hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31) askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return emptyList()
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { (it.name ?: it.address) to it }.sortedBy { it.first }
    }

    fun connectBluetooth(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            pendingBluetooth = device
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        repo.connect(Link.BLUETOOTH) { BluetoothTransport.open(device) }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "io.github.a_m_protasov.a747.USB_PERMISSION"
    }
}
