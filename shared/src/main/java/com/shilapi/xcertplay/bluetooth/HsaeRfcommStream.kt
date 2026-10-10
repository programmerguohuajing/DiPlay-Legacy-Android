package com.shilapi.xcertplay.bluetooth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.anwsdk.service.IAnwPhoneLink
import com.anwsdk.service.IAnwSPPDataCallBack
import com.anwsdk.service.IAnwSocketDataCallBack
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HsaeRfcommStream private constructor(
    private val context: Context,
    private val service: IAnwPhoneLink,
    private val connectionIndex: Int,
    private val isSocketMode: Boolean,
    private val connection: ServiceConnection,
    private val socketCallback: IAnwSocketDataCallBack.Stub?,
    private val sppCallback: IAnwSPPDataCallBack.Stub?
) : BlockingDuplexByteStream {

    private val lock = Object()
    private val sendLock = Object()
    private val pending: ArrayDeque<ByteArray> = ArrayDeque()
    private var pendingBytes: Int = 0
    private var closed: Boolean = false
    private var peerEnded: Boolean = false
    private var failure: IOException? = null

    fun onDataReceived(data: ByteArray, length: Int) {
        val payload = if (data.size == length) data else data.copyOf(length)
        synchronized(lock) {
            if (!closed && !peerEnded) {
                pending.addLast(payload)
                pendingBytes += payload.size
                lock.notifyAll()
            }
        }
    }

    fun onDisconnected() {
        synchronized(lock) {
            peerEnded = true
            lock.notifyAll()
        }
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) {
                    throw IOException("HSAE RFCOMM stream is closed")
                }
            }
            val written = IntArray(1)
            var offset = 0
            while (offset < data.size) {
                val chunkSize = Math.min(data.size - offset, 4096)
                val chunk = if (offset == 0 && chunkSize == data.size) {
                    data
                } else {
                    data.copyOfRange(offset, offset + chunkSize)
                }
                val ret = try {
                    if (isSocketMode) {
                        service.ANWBT_SocketWrite(connectionIndex, chunk, chunk.size, written)
                    } else {
                        service.ANWBT_SPPWrite(connectionIndex, chunk, chunk.size, written)
                    }
                } catch (e: RemoteException) {
                    val io = IOException("RemoteException during HSAE RFCOMM send", e)
                    synchronized(lock) {
                        failure = io
                    }
                    throw io
                }

                if (ret < 0) {
                    val io = IOException("HSAE RFCOMM send returned error: ret=$ret")
                    synchronized(lock) {
                        failure = io
                    }
                    throw io
                }

                val nWritten = if (written[0] > 0) written[0] else chunkSize
                offset += nWritten
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (true) {
                val chunk = takePendingLocked(maxBytes)
                if (chunk != null) {
                    return chunk
                }
                failure?.let { throw it }
                if (peerEnded || closed) {
                    return EMPTY
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    return null
                }
                try {
                    lock.wait(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val first = pending.peekFirst() ?: return null
        return if (first.size <= maxBytes) {
            pending.removeFirst()
            pendingBytes -= first.size
            first
        } else {
            val chunk = first.copyOf(maxBytes)
            pending.removeFirst()
            pending.addFirst(first.copyOfRange(maxBytes, first.size))
            pendingBytes -= maxBytes
            chunk
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            lock.notifyAll()
        }
        try {
            if (isSocketMode) {
                if (socketCallback != null) {
                    runCatching {
                        service.ANWBT_UnRegistrySocketDataCallback(socketCallback)
                    }
                }
                service.ANWBT_SocketDisconnect(connectionIndex)
            } else {
                if (sppCallback != null) {
                    runCatching {
                        service.ANWBT_UnRegistrySPPDataCallback(sppCallback)
                    }
                }
                service.ANWBT_SPPDisconnect(connectionIndex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting HSAE RFCOMM index=$connectionIndex", e)
        }
        runCatching {
            context.unbindService(connection)
        }
    }

    companion object {
        private const val TAG = "HsaeRfcommStream"
        private const val SERVICE_ACTION = "com.anwsdk.service.AnwPhoneLink"
        private const val SERVICE_PACKAGE = "com.anwsdk.service"
        private val EMPTY = ByteArray(0)

        fun connect(context: Context, address: String, uuidString: String): BlockingDuplexByteStream {
            Log.i(TAG, "Connecting HSAE RFCOMM to $address uuid=$uuidString")
            val latch = CountDownLatch(1)
            var phoneLinkService: IAnwPhoneLink? = null
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    phoneLinkService = IAnwPhoneLink.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    phoneLinkService = null
                }
            }

            val intent = Intent(SERVICE_ACTION).apply {
                setPackage(SERVICE_PACKAGE)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IOException("Could not bind to AnwSdkService ($SERVICE_ACTION)")
            }

            if (!latch.await(5000L, TimeUnit.MILLISECONDS)) {
                context.unbindService(serviceConnection)
                throw IOException("Timed out waiting for AnwPhoneLink binding")
            }

            val service = phoneLinkService ?: run {
                context.unbindService(serviceConnection)
                throw IOException("AnwPhoneLink interface is null after connect")
            }

            val rfcBytes = uuidToBytes(uuidString)
            val guidBytes = uuidToGuidBytes(uuidString)

            var streamInstance: HsaeRfcommStream? = null
            val socketCallback = object : IAnwSocketDataCallBack.Stub() {
                override fun SocketDataIND(nIndex: Int, data: ByteArray?, dataLength: Int) {
                    if (data != null && dataLength > 0) {
                        streamInstance?.onDataReceived(data, dataLength)
                    }
                }
            }

            // Try Socket mode first
            try {
                service.ANWBT_RegistrySocketDataCallback(socketCallback)
                service.ANWBT_SocketInit()
                val index = intArrayOf(-1)
                var ret = service.ANWBT_SocketConnect(1, address, guidBytes, 0, 0L, 0L, index)
                Log.i(TAG, "ANWBT_SocketConnect(GUID) returned $ret index=${index[0]}")
                if (ret < 0 || index[0] < 0) {
                    ret = service.ANWBT_SocketConnect(1, address, rfcBytes, 0, 0L, 0L, index)
                    Log.i(TAG, "ANWBT_SocketConnect(RFC) returned $ret index=${index[0]}")
                }
                if (ret >= 0 && index[0] >= 0) {
                    val stream = HsaeRfcommStream(
                        context = context,
                        service = service,
                        connectionIndex = index[0],
                        isSocketMode = true,
                        connection = serviceConnection,
                        socketCallback = socketCallback,
                        sppCallback = null
                    )
                    streamInstance = stream
                    Log.i(TAG, "HSAE Socket connect successful index=${index[0]}")
                    return stream
                }
            } catch (e: Exception) {
                Log.w(TAG, "SocketConnect failed, falling back to SPP: ${e.message}")
            }

            // Fallback to SPP mode
            val sppCallback = object : IAnwSPPDataCallBack.Stub() {
                override fun SPPDataIND(nIndex: Int, data: ByteArray?, dataLength: Int) {
                    if (data != null && dataLength > 0) {
                        streamInstance?.onDataReceived(data, dataLength)
                    }
                }
            }

            try {
                service.ANWBT_RegistrySPPDataCallback(sppCallback)
                service.ANWBT_SPPInit(guidBytes)
                val index = intArrayOf(-1)
                var ret = service.ANWBT_SPPConnect(address, guidBytes, index)
                Log.i(TAG, "ANWBT_SPPConnect(GUID) returned $ret index=${index[0]}")
                if (ret < 0 || index[0] < 0) {
                    service.ANWBT_SPPInit(rfcBytes)
                    ret = service.ANWBT_SPPConnect(address, rfcBytes, index)
                    Log.i(TAG, "ANWBT_SPPConnect(RFC) returned $ret index=${index[0]}")
                }
                if (ret >= 0 && index[0] >= 0) {
                    val stream = HsaeRfcommStream(
                        context = context,
                        service = service,
                        connectionIndex = index[0],
                        isSocketMode = false,
                        connection = serviceConnection,
                        socketCallback = null,
                        sppCallback = sppCallback
                    )
                    streamInstance = stream
                    Log.i(TAG, "HSAE SPP connect successful index=${index[0]}")
                    return stream
                }
            } catch (e: Exception) {
                Log.w(TAG, "SPPConnect failed: ${e.message}")
            }

            runCatching { context.unbindService(serviceConnection) }
            throw IOException("HSAE Bluetooth RFCOMM connect failed for $address ($uuidString)")
        }

        internal fun uuidToBytes(uuidString: String): ByteArray {
            val uuid = UUID.fromString(uuidString)
            val bytes = ByteArray(16)
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            for (i in 0 until 8) {
                bytes[i] = ((msb ushr ((7 - i) * 8)) and 0xffL).toByte()
            }
            for (i in 8 until 16) {
                bytes[i] = ((lsb ushr ((15 - i) * 8)) and 0xffL).toByte()
            }
            return bytes
        }

        internal fun uuidToGuidBytes(uuidString: String): ByteArray {
            val raw = uuidToBytes(uuidString)
            val guid = ByteArray(16)
            guid[0] = raw[3]
            guid[1] = raw[2]
            guid[2] = raw[1]
            guid[3] = raw[0]
            guid[4] = raw[5]
            guid[5] = raw[4]
            guid[6] = raw[7]
            guid[7] = raw[6]
            System.arraycopy(raw, 8, guid, 8, 8)
            return guid
        }
    }
}
