package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], manifest = Config.NONE, shadows = [
    IphoneUsbBringupTest.Manager::class, IphoneUsbBringupTest.Device::class,
    IphoneUsbBringupTest.Configuration::class, IphoneUsbBringupTest.Interface::class,
    IphoneUsbBringupTest.Endpoint::class, IphoneUsbBringupTest.Connection::class,
])
class IphoneUsbBringupTest {
    private val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
    private val connectionState = Shadow.extract<Connection>(connection)
    private val manager = Shadow.newInstanceOf(UsbManager::class.java).also {
        Shadow.extract<Manager>(it).connection = connection
    }
    private val device = Shadow.newInstanceOf(UsbDevice::class.java).also {
        val configuration = Shadow.newInstanceOf(UsbConfiguration::class.java)
        Shadow.extract<Configuration>(configuration).interfaces = listOf(
            newInterface(1, 0xff, 0xfe, 2, listOf(endpoint(0x04), endpoint(0x85))),
            newInterface(2, 2, 13, 0, emptyList()),
        )
        Shadow.extract<Device>(it).configuration = configuration
    }

    @Test fun opensMuxOnlyAfterKernelConfigurationAndExportsDiagnostics() {
        val messages = mutableListOf<String>()
        val result = open(messages)
        assertTrue("USB bring-up result: $result", result is IphoneUsbHost.Iap2SessionResult.Connected)
        try {
            assertEquals(listOf("configure", "claim:1"), connectionState.operations)
            assertTrue(messages.any { it.contains("frameworkOk=true") })
            assertTrue(messages.any { it.contains("usb mux claim iface=1 ok=true") })
        } finally { (result as? IphoneUsbHost.Iap2SessionResult.Connected)?.session?.close() }
        assertEquals(1, connectionState.closeCount)
        assertEquals(listOf(1), connectionState.released)
    }

    @Test fun failedKernelConfigurationNeverClaimsMuxAndClosesTheConnection() {
        connectionState.frameworkSuccess = false
        val messages = mutableListOf<String>()
        val result = open(messages)
        assertTrue(result is IphoneUsbHost.Iap2SessionResult.Failed)
        assertEquals(listOf("configure"), connectionState.operations)
        assertEquals(1, connectionState.closeCount)
        assertTrue(messages.any { it.contains("frameworkOk=false") && it.contains("after=6") })
    }

    @Test fun linkageErrorInUsbSessionFailsAsDeviceUnavailable() {
        val managerState = Shadow.extract<Manager>(manager)
        managerState.throwLinkageError = true
        val messages = mutableListOf<String>()
        val result = open(messages)
        assertTrue(result is IphoneUsbHost.Iap2SessionResult.Failed)
        val failure = (result as IphoneUsbHost.Iap2SessionResult.Failed).error
        assertTrue(failure is IphoneUsbException.DeviceUnavailable)
        assertTrue(failure.cause is LinkageError)
    }

    private fun open(messages: MutableList<String>): IphoneUsbHost.Iap2SessionResult? {
        var result: IphoneUsbHost.Iap2SessionResult? = null
        IphoneUsbHost(
            RuntimeEnvironment.getApplication(), manager, IphoneUsbMatcher.appleVendor(),
            onDiagnostic = { messages.add(it) },
        ).openIap2UsbSessionAsync(device, Executor { it.run() }) { result = it }
        return result
    }

    private fun endpoint(address: Int): UsbEndpoint = Shadow.newInstanceOf(UsbEndpoint::class.java).also {
        Shadow.extract<Endpoint>(it).endpointAddress = address
    }

    private fun newInterface(id: Int, cls: Int, sub: Int, proto: Int, endpoints: List<UsbEndpoint>): UsbInterface =
        Shadow.newInstanceOf(UsbInterface::class.java).also {
            Shadow.extract<Interface>(it).apply {
                number = id; classCode = cls; subclass = sub; protocol = proto; pipes = endpoints
            }
        }

    @Implements(UsbManager::class)
    class Manager {
        var throwLinkageError = false
        lateinit var connection: UsbDeviceConnection
        @Implementation fun hasPermission(device: UsbDevice): Boolean = true
        @Implementation fun openDevice(device: UsbDevice): UsbDeviceConnection {
            if (throwLinkageError) throw UnsatisfiedLinkError("dlopen failed: library not found")
            return connection
        }
    }

    @Implements(UsbDevice::class)
    class Device {
        lateinit var configuration: UsbConfiguration
        @Implementation fun getVendorId(): Int = 0x05ac
        @Implementation fun getProductId(): Int = 0x12ab
        @Implementation fun getConfigurationCount(): Int = 1
        @Implementation fun getConfiguration(index: Int): UsbConfiguration = configuration
    }

    @Implements(UsbConfiguration::class)
    class Configuration {
        var interfaces = emptyList<UsbInterface>()
        @Implementation fun getId(): Int = 6
        @Implementation fun getInterfaceCount(): Int = interfaces.size
        @Implementation fun getInterface(index: Int): UsbInterface = interfaces[index]
    }

    @Implements(UsbInterface::class)
    class Interface {
        var number = 0
        var classCode = 0
        var subclass = 0
        var protocol = 0
        var pipes = emptyList<UsbEndpoint>()
        @Implementation fun getId(): Int = number
        @Implementation fun getAlternateSetting(): Int = 0
        @Implementation fun getInterfaceClass(): Int = classCode
        @Implementation fun getInterfaceSubclass(): Int = subclass
        @Implementation fun getInterfaceProtocol(): Int = protocol
        @Implementation fun getEndpointCount(): Int = pipes.size
        @Implementation fun getEndpoint(index: Int): UsbEndpoint = pipes[index]
    }

    @Implements(UsbEndpoint::class)
    class Endpoint {
        var endpointAddress = 0
        @Implementation fun getAddress(): Int = endpointAddress
        @Implementation fun getDirection(): Int = endpointAddress and 0x80
        @Implementation fun getType(): Int = UsbConstants.USB_ENDPOINT_XFER_BULK
    }

    @Implements(UsbDeviceConnection::class)
    class Connection {
        var frameworkSuccess = true
        var kernelConfigured = false
        var closeCount = 0
        val operations = mutableListOf<String>()
        val released = mutableListOf<Int>()
        @Implementation fun getFileDescriptor(): Int = -1
        @Implementation fun setConfiguration(configuration: UsbConfiguration): Boolean {
            operations += "configure"
            kernelConfigured = frameworkSuccess
            return frameworkSuccess
        }
        @Implementation fun claimInterface(iface: UsbInterface, force: Boolean): Boolean {
            operations += "claim:${iface.id}"
            return kernelConfigured
        }
        @Implementation fun releaseInterface(iface: UsbInterface): Boolean {
            released += iface.id
            return true
        }
        @Implementation fun close() { closeCount++ }
        @Implementation fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int,
            buffer: ByteArray?, length: Int, timeout: Int,
        ): Int {
            // Phone says configuration 6 even before the host kernel has selected it.
            if (request != 8) return -1
            buffer!![0] = 6
            return 1
        }
    }
}
