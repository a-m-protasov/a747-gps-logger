package io.github.a_m_protasov.a747.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.a_m_protasov.a747.core.LoggerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * USB transport (protocol §1.1). The 747A+ lists its data interface before the comm
 * interface, so instead of a CDC driver we talk to the bulk endpoints directly.
 */
class UsbTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val data: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : LoggerTransport {
    private val buf = ByteArray(4096)

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, data.size - off, 2000)
            if (n < 0) throw IOException("USB write failed")
            off += n
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs.toInt().coerceAtLeast(10))
        if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    override suspend fun close() {
        conn.releaseInterface(data)
        conn.close()
    }

    companion object {
        const val VENDOR_ID = 0x0E8D
        const val PRODUCT_ID = 0x3329

        fun find(usb: UsbManager): UsbDevice? =
            usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }

        fun open(usb: UsbManager, device: UsbDevice): UsbTransport {
            val ifaces = (0 until device.interfaceCount).map(device::getInterface)
            val data = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                ?: throw IOException("no data interface")
            val comm = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val eps = (0 until data.endpointCount).map(data::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val epIn = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("no IN endpoint")
            val epOut = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("no OUT endpoint")
            val conn = usb.openDevice(device) ?: throw IOException("cannot open USB device")
            if (!conn.claimInterface(data, true)) { conn.close(); throw IOException("cannot claim interface") }
            val commIndex = comm?.id ?: 1
            // SET_LINE_CODING 115200 8N1 and SET_CONTROL_LINE_STATE DTR|RTS
            conn.controlTransfer(0x21, 0x20, 0, commIndex, byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8), 7, 1000)
            conn.controlTransfer(0x21, 0x22, 3, commIndex, null, 0, 1000)
            return UsbTransport(conn, data, epIn, epOut)
        }
    }
}

/** Bluetooth SPP transport (protocol §1.2). */
class BluetoothTransport private constructor(private val socket: BluetoothSocket) : LoggerTransport {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val buf = ByteArray(4096)

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) { output.write(data); output.flush() }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(buf, 0, minOf(buf.size, input.available()))
                if (n > 0) return@withContext buf.copyOf(n)
            }
            Thread.sleep(10)
        }
        ByteArray(0)
    }

    override suspend fun close() = withContext(Dispatchers.IO) { runCatching { socket.close() }; Unit }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission") // checked by the caller
        suspend fun open(device: BluetoothDevice): BluetoothTransport = withContext(Dispatchers.IO) {
            val socket = device.createRfcommSocketToServiceRecord(SPP)
            socket.connect()
            BluetoothTransport(socket)
        }
    }
}
