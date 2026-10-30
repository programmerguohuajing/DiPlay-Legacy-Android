package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import com.shilapi.xcertplay.compat.BluetoothCompat
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.media.musicVolumeWarning
import com.shilapi.xcertplay.bluetooth.BluetoothAddressResolver
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayMediaActivity
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoPlaybackDelivery
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.HotspotAddressRecovery
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.LegacyWifiP2pGroupManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.HostapdHotspotManager
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.MdnsSniffer
import com.shilapi.xcertplay.network.PhoneProbe
import com.shilapi.xcertplay.network.WifiScanPause
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessInterfaceDiagnostics
import com.shilapi.xcertplay.network.WirelessReceiveDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.util.safeStackTrace
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
    startSessionSent: Boolean = false,
): Boolean = handoffRequested || tunnelActive || sessionActive || startSessionSent

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
@android.annotation.SuppressLint("MissingPermission")
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        WifiScanPause.restoreIfNeeded(context.applicationContext)
        BydNavigationOutputs.start(context.applicationContext)
        BydNavigationOutputs.setClusterStreamControl(::applyClusterUi)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager: UsbManager? = if (realSdkInt() >= Build.VERSION_CODES.M) {
        context.getSystemService(UsbManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }
    private val bluetoothAdapter = BluetoothCompat.getAdapter(appContext)
    private val iphoneHost by lazy {
        IphoneUsbHost(
            appContext,
            requireUsbManager(),
            if (config.iphoneDevices.isNotEmpty()) {
                IphoneUsbMatcher(config.iphoneDevices)
            } else {
                IphoneUsbMatcher.appleVendor()
            },
            onDiagnostic = ::connectionDiagnostic,
        )
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false
    private var lastNoIphoneDevicesLogNanos = 0L

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var wiredAborted = false
    private val wiredTeardownPending = AtomicBoolean(false)
    private val wiredGeneration = AtomicInteger(0)
    @Volatile private var wiredUsbSession: Iap2UsbSession? = null
    @Volatile private var wiredNcm: NcmUsbBridge? = null
    /** Issue #266 direct mode: the sysfs identity of the phone a direct session runs on. */
    private val directAttemptRunning = AtomicBoolean(false)
    /** Issue #266: pacing for direct bring-up attempts; reset on a real detach. */
    @Volatile private var lastDirectAttemptStartNanos = 0L
    @Volatile private var directDevice: com.shilapi.xcertplay.transport.SysfsUsbDevice? = null
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var wifiScanPause: WifiScanPause? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    // Issue #293 (upstream port): the address family advertised to the phone, its
    // alternate-family candidate, and the recovery sampler used to flip families
    // after a first-TCP timeout. Without the flip every retry re-advertised the
    // same family and repeated the identical failure.
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAlternateAddress: InetAddress? = null
    @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
    /**
     * Issue #95: set when a wired AirPlay session becomes active, cleared when the wired
     * control loop sends StartSession. Lets the wired live loop fail fast when the iPhone
     * never opens the AirPlay TCP connection.
     */
    private val wiredTcpArrived = AtomicBoolean(false)
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }.apply { if (realSdkInt() >= 21) removeOnCancelPolicy = true }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessPostTransportConfigurationSent = AtomicBoolean(false)
    private val wirelessBootstrapReleaseScheduled = AtomicBoolean(false)
    /** Issue #343: true once this session's handoff turned Bluetooth off; restored at session end. */
    private val autoBtDisabled = AtomicBoolean(false)
    private val wirelessAirPlayRequestsInFlight = AtomicInteger()
    private val wirelessLastAirPlayActivityMs = AtomicLong(0L)
    private val wirelessTcpAcceptedAtMs = AtomicLong(0L)
    /** Set when the handoff fell back and kept Bluetooth as the only iAP2 channel. */
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var detachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (closed || (config.transport == CarPlayTransport.WIRED && wiredAborted)) return
            val replacement = activeSession !== session
            if (replacement) {
                BydNavigationOutputs.start(appContext)
                BydNavigationOutputs.carPlaySessionStarted()
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            if (replacement) restoreDashboardContent(session)
            // Issue #95: wired first-TCP watchdog 鈥?a session only becomes active after the
            // iPhone opened the AirPlay TCP connection and completed the handshake.
            if (config.transport != CarPlayTransport.WIRELESS) {
                wiredTcpArrived.set(true)
                // Persisted, not just in memory: the head unit may kill this process
                // before the next reconnect (issue #269).
                persistWiredSessionCompleted(true)
            }
            if (config.transport == CarPlayTransport.WIRELESS) {
                wirelessChain(12, "SUCCESS: AirPlay session active")
            }
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            if (config.transport == CarPlayTransport.WIRELESS) {
                val output = runCatching {
                    @Suppress("DEPRECATION")
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    if (audio == null) "audioManager=unavailable" else {
                        @Suppress("DEPRECATION")
                        "a2dpOn=${audio.isBluetoothA2dpOn} musicActive=${audio.isMusicActive} " +
                            "musicVolume=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/" +
                            "${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
                    }
                }.getOrElse { "audioDiagnostics=${it.javaClass.simpleName}" }
                debugLog("wireless audio route at AirPlay session start: $output; " +
                    "OEM A2DP/MCU source selection may differ from Android routing APIs")
                // Issue #88: near-zero STREAM_MUSIC volume is the most common "packets flow
                // but no sound" cause on head units; surface it loudly at session start.
                runCatching {
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    musicVolumeWarning(audio)?.let { debugLog(it) }
                }
            }
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            // A closing reader can report its end after a replacement session
            // is active. It must not clear that session's UI or playback state.
            if (activeSession !== session) return
            if (activeSession === session) {
                activeSession = null
                BydNavigationOutputs.endNow(preserveTurnOverlay = !closed && config.transport == CarPlayTransport.WIRELESS)
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            val deviceId = params["deviceID"] as? String ?: params["deviceIdentifier"] as? String
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")} deviceId=${if (deviceId != null) "[address]" else "none"}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                // Issue #128: on head units with native Bluetooth music, the iPhone is often
                // also paired with that A2DP sink. iOS then resolves audio away from CarPlay
                // (kept on the phone) and never opens the AirPlay audio stream. This hint makes
                // the conflict visible in the report; the fix is user-side (forget the native
                // BT pairing), the app cannot re-route iOS audio.
                debugLog(
                    "wireless audio route hint: if this iPhone is also paired with the head " +
                        "unit's native Bluetooth music, iOS may keep audio on the phone and " +
                        "never request the CarPlay audio stream; disconnect/forget that " +
                        "pairing so CarPlay is the only audio route",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onModesChanged(
            session: AirPlaySession,
            appStates: List<Map<String, Any?>>,
            resources: List<Map<String, Any?>>,
        ) {
            debugLog("AirPlay modesChanged appStates=$appStates resources=$resources")
            uiListener?.onModesChanged(session, appStates, resources)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun currentAppStates(): List<Map<String, Any?>> = activeSession?.currentAppStates() ?: emptyList()

    fun changeModes(
        appStates: List<Map<String, Any?>> = emptyList(),
        resources: List<Map<String, Any?>> = emptyList(),
    ): Boolean {
        val session = activeSession ?: return false
        val params = linkedMapOf<String, Any?>()
        if (appStates.isNotEmpty()) params["appStates"] = appStates
        if (resources.isNotEmpty()) params["resources"] = resources
        return session.sendCommand(linkedMapOf(
            "type" to "changeModes",
            "params" to params,
        ))
    }

    /**
     * Yields MainAudio ownership back to iPhone (transferType=Untake) and reclaims Screen ownership
     * via CarPlay changeModes (transferType=Take).
     * Used when returning from native head unit media/radio or on audio focus recovery.
     */
    fun takeMainAudioAndScreen(): Boolean {
        val session = activeSession ?: return false
        if (config.transport == CarPlayTransport.WIRELESS) {
            debugLog("skipped resume changeModes for wireless session; retaining negotiated Screen and MainAudio ownership")
            return false
        }
        val resources = listOf(
            linkedMapOf("resourceID" to 1, "transferType" to 1), // Take Screen
            linkedMapOf("resourceID" to 2, "transferType" to 2), // Untake MainAudio
        )
        debugLog("requesting CarPlay MainAudio (Untake) and Screen (Take) via changeModes")
        return changeModes(resources = resources)
    }

    /**
     * Yields MainAudio ownership back to iPhone via CarPlay changeModes (transferType=Untake).
     * In CarPlay protocol, transferType=1 (Take) means the accessory seizes the resource; sending
     * transferType=2 (Untake) instructs iOS to resume streaming audio over CarPlay.
     */
    fun takeMainAudio(): Boolean {
        val session = activeSession ?: return false
        // Issue #293: like takeMainAudioAndScreen(), a MainAudio Untake on a wireless
        // session makes the phone end the screen stream and close the session (seen
        // ~4 ms after the request, right after a touch, on two head units). The untake
        // exists for wired sessions returning to native media; wireless keeps ownership.
        if (config.transport == CarPlayTransport.WIRELESS) {
            debugLog("skipped MainAudio changeModes for wireless session; retaining negotiated MainAudio ownership")
            return false
        }
        val resources = listOf(
            linkedMapOf("resourceID" to 2, "transferType" to 2), // Untake MainAudio (iPhone outputs audio)
        )
        debugLog("requesting CarPlay MainAudio via changeModes (transferType=Untake)")
        return changeModes(resources = resources)
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        if (!hasRequiredUsbService()) return
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            runCatching { CarPlayBluetoothManager.get(appContext).disable() }
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
            detachCloseable = iphoneHost.registerDetachReceiver(::onIphoneDetached)
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    /** Returns true if this wired controller is waiting for an iPhone to be plugged in or reconnected. */
    fun isWiredWaitingForIphone(): Boolean =
        config.transport == CarPlayTransport.WIRED && !closed && (phase == Phase.IPHONE || wiredAborted)

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shilapi.xcertplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** A visible physical map and its session/stream generation; null for a paused/virtual/turn-card route. */
    fun dashboardMapRoute(): Any? {
        val session = activeSession ?: return null
        val content = session.clusterContentRoute() ?: return null
        val stream = content.first
        val route = session to stream
        val visibility = clusterUiVisibility
        val shown = visibility?.takeIf { it.first == route }?.second ?: true
        if (!DashboardMapEligibility.permits(content.second,
                dashboardMapOutputVisible, stream, shown, closed)) return null
        return Triple(session, stream, dashboardMapEpoch.get() to content.third)
    }

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            dashboardMapOutputVisible = false
        }
        firstTcpWatchdog?.terminate()
        startupTimer.shutdownNow()
        (hotspot as? ManualHotspotManager)?.close()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        BydNavigationOutputs.endNow()
        com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
        BydNavigationOutputs.clearClusterStreamControl(::applyClusterUi)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                        closeBestEffort("Wi-Fi scan pause") { wifiScanPause?.close() }
                        wifiScanPause = null
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
            clusterUiVisibility = (session to stream) to true
        }
        if (shown == clusterUiShown && (!shown || session.clusterUrl() != null)) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            clusterUiVisibility = (session to stream) to shown
            dashboardMapEpoch.incrementAndGet()
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        BydNavigationOutputs.onFrame(frame)
        com.shilapi.xcertplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        if (!hasRequiredUsbService()) return
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val privateRoot = if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) appContext.noBackupFilesDir else appContext.filesDir
        val offlineDirectory = java.io.File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    requireUsbManager(),
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (requireUsbManager().hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessPostTransportConfigurationSent.set(false)
            wirelessBootstrapReleaseScheduled.set(false)
            wirelessTcpAcceptedAtMs.set(0L)
            wirelessHandoffFellBack.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        executor.execute { runWireless(generation) }
    }

    /**
     * 上报设备加入/离开 Wi-Fi Direct 组（port 自 ding2548-ui/DiPlay@main）。
     *
     * 两种无线失败在其他日志里看起来一模一样——"手机没加入我们的 Wi-Fi"和
     * "手机加入了但没连 AirPlay"都以没有 `airplay connection accepted from`
     * 收尾，但修法完全相反。这几行是唯一能区分它们的证据，因此在整个
     * 建连窗口内运行，每次变化都记一笔。
     */
    private fun startWirelessClientWatch(
        generation: Int,
        manager: WirelessHotspotManager,
        interfaceName: String?,
    ) {
        Thread(
            {
                var lastReported = -1
                val deadline = System.nanoTime() +
                    WIRELESS_CLIENT_WATCH_MILLIS * 1_000_000L
                while (
                    !closed &&
                    generation == wirelessGeneration.get() &&
                    System.nanoTime() < deadline
                ) {
                    val count = manager.joinedClientCount()
                    if (count != null && count != lastReported) {
                        val justJoined = count > 0 && lastReported == 0
                        lastReported = count
                        debugLog(
                            if (count == 0) {
                                "wireless group clients=0；手机尚未加入本机 Wi-Fi"
                            } else {
                                "wireless group clients=$count；已有设备加入本机 Wi-Fi"
                            },
                        )
                        if (justJoined) {
                            // 手机刚入网：它到底拿没拿到 IP、mDNS 栈活没活？
                            // 没有这一问，"手机无流量"分不清是 DHCP 失败还是手机侧 CarPlay 策略静默。
                            PhoneProbe.start(interfaceName) { debugLog(it) }
                        }
                    }
                    try {
                        Thread.sleep(WIRELESS_CLIENT_WATCH_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "xcertplay-wifi-clients",
        ).apply { isDaemon = true }.start()
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        Thread(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        var stage = "hotspot startup"
        try {
            debugLog("wireless bring-up generation=$generation starting")
            // Issue #79: without a Bluetooth adapter, wireless CarPlay is impossible 鈥?
            // iPhone needs Bluetooth for discovery/pairing. Fail fast instead of waiting
            // 60s for a Wi-Fi connection that will never come.
            // Issue #138: gate on the hybrid adapter (vendor service + standard Android),
            // not the raw Android adapter alone 鈥?on some head units (e.g. ECARX E01)
            // Bluetooth only works through the vendor Bluetooth service while
            // getDefaultAdapter() reports null or disabled.
            val hybridBt = CarPlayBluetoothManager.get(appContext)
            wirelessChain(1, "Bluetooth adapter supported")
            if (!hybridBt.isSupported()) {
                throw IOException(
                    "No Bluetooth adapter on this head unit; wireless CarPlay requires Bluetooth " +
                        "for iPhone discovery. Use wired USB instead."
                )
            }
            // Issue #117: adapter present but switched off 鈥?the iPhone can never discover
            // the head unit, so fail fast with an actionable message instead of the 60s
            // Wi-Fi wait ending in "Timed out waiting for iPhone to connect over Wi-Fi hotspot".
            // Issue #165: try enabling Bluetooth first. The old code threw here
            // without ever calling enable(), so head units whose Bluetooth
            // starts off (e.g. Rockchip rkpx3, no BT switch in Android
            // settings) could never get past this check.
            if (!hybridBt.isEnabled()) {
                runCatching { hybridBt.enable() }
                val enableDeadline = System.currentTimeMillis() + 3000L
                while (!hybridBt.isEnabled() && System.currentTimeMillis() < enableDeadline) {
                    try {
                        Thread.sleep(200L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            wirelessChain(2, "Bluetooth enabled")
            if (!hybridBt.isEnabled()) {
                // Issue #157: some vendor stacks (e.g. YunOS on Roewe RX5) keep Bluetooth
                // on outside the Android framework, so the framework adapter reports
                // disabled while the phone is already paired in the car system. When the
                // user already entered the iPhone address manually, Bluetooth discovery
                // is not needed, so proceed with a warning instead of failing here; the
                // chain below already skips Bluetooth when no device is selected.
                val savedPhone = config.wirelessBluetoothDeviceAddress
                if (savedPhone.isNullOrBlank()) {
                    throw IOException(
                        "Bluetooth is turned off on this head unit; wireless CarPlay requires Bluetooth " +
                            "for iPhone discovery. Turn on Bluetooth in the head unit system settings " +
                            "and retry, or use wired USB instead."
                    )
                }
                debugLog(
                    "wireless Bluetooth reported off by the framework; proceeding with the " +
                        "manually entered iPhone address (Bluetooth may be managed by a vendor stack)"
                )
            }
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = FirstTcpWatchdog(
                listener = listenerIdentity,
                schedule = { delay, action ->
                    val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { future.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && generation == wirelessGeneration.get()) {
                        flipWirelessAddressFamily(generation)
                        fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                            "No AirPlay TCP after CarPlay StartSession"), generation)
                        Thread({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
                log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
            )
            firstTcpWatchdog = watchdog
            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            wirelessChain(5, "starting hotspot")
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                return
            }
            pauseWifiScans(hotspotInfo.backend)
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val preferredFamily = HotspotAddressPolicy.parseFamily(
                appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
                    .getString(HOTSPOT_FAMILY_PREFERENCE_KEY, null),
            )
            val addressRecovery = HotspotAddressRecovery(
                sample = { hotspotCandidates(hotspotInfo, fallbackToSnapshot = false) },
                cancelled = { isStaleWirelessRun(generation) },
                pause = Thread::sleep,
                log = { if (!isStaleWirelessRun(generation)) debugLog(it) },
            )
            val candidates = addressRecovery.awaitCandidates(hotspotCandidates(hotspotInfo), preferredFamily)
            val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
                ?: hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                wirelessPublishedAddress = hostAddress
                wirelessAlternateAddress = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
                wirelessAddressRecovery = addressRecovery
            }
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            wirelessChain(6, "hotspot ready backend=${hotspotInfo.backend.label} iface=${hotspotInfo.interfaceName ?: "unknown"}")
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "preference=${preferredFamily ?: "default"} " +
                    "alternate=${wirelessAlternateAddress != null} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)
            hotspot?.let { startWirelessClientWatch(generation, it, hotspotInfo.interfaceName) }
            stage = "Bluetooth device selection"

            val adapter = bluetoothAdapter
            val hybridAdapter = CarPlayBluetoothManager.get(appContext)
            if (adapter != null && !adapter.isEnabled) {
                runCatching { adapter.enable() }
                // Upstream #822: wait only while the adapter is actually turning on;
                // break early on other states instead of sleeping the full deadline.
                val enableDeadline = System.currentTimeMillis() + 3000L
                while (!adapter.isEnabled && System.currentTimeMillis() < enableDeadline) {
                    val state = runCatching { adapter.state }.getOrNull()
                    if (state != BluetoothAdapter.STATE_TURNING_ON) break
                    try {
                        Thread.sleep(200L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            // Bring up the Hybrid stacks too. The system BluetoothAdapter
            // above does NOT enable the USB dongle stack: on T3 the dongle
            // is the only data-capable Bluetooth, and without this call it
            // stays disabled and wireless can never reach it (P0-1).
            runCatching { hybridAdapter.enable() }
            // Issue #157: on vendor stacks (e.g. YunOS) the framework and hybrid adapters
            // may both report disabled while the user already entered the iPhone MAC
            // manually. selectWirelessBluetoothDevice() consumes the manual address itself
            // (getRemoteDevice needs no enabled adapter), so let it run in that case.
            val manualPhoneAddress = config.wirelessBluetoothDeviceAddress
            val hasManualPhoneAddress = !manualPhoneAddress.isNullOrBlank() &&
                BluetoothAdapter.checkBluetoothAddress(manualPhoneAddress)
            val device = try {
                if (adapter != null &&
                    (adapter.isEnabled || hybridAdapter.isEnabled() || hasManualPhoneAddress)
                ) {
                    selectWirelessBluetoothDevice(adapter)
                } else {
                    null
                }
            } catch (error: Throwable) {
                debugLog("wireless Bluetooth device selection not available: ${error.message}; proceeding with Wi-Fi discovery")
                null
            }
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target targetAddress=${device?.address ?: "none"} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            stage = "AirPlay listener startup"
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        additionalBindAddresses = (candidates + hotspotInfo.hostAddresses).filter { it != hostAddress }.distinct(),
                    )
                ) {
                    is CarPlayVpnService.AttachResult.Started -> wirelessChain(11, "wireless AirPlay transport attached, waiting for iPhone TCP")
                    CarPlayVpnService.AttachResult.AlreadyStarted ->
                        throw IOException("Wireless AirPlay transport is already attached")
                    CarPlayVpnService.AttachResult.Reused -> Unit
                    is CarPlayVpnService.AttachResult.Failed ->
                        throw IOException(result.message)
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = HotspotAddressPolicy.offeredAddresses(hostAddress, candidates + hotspotInfo.hostAddresses)
                    .map(::hostAddressText)
                    .filter { it.isNotBlank() }
                    .distinct(),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "addresses=${endpoint.ipAddresses.joinToString(",")} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            stage = "Bonjour discovery startup"
            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                // Some KitKat head units have no working servicediscovery system service.
                // Use bundled JmDNS instead of crashing in NsdManager.getMessenger().
                useInterfaceMdns = com.shilapi.xcertplay.network.useBundledCarPlayMdns(realSdkInt()),
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                // Ported from dagou888/DiPlay-UIS8141E@9741cac: some Android 8.1
                // head units are much more reliable when the Personal Hotspot session
                // stays on one IPv4 interface; don't let JmDNS create an IPv6
                // responder on a second address family during the CarPlay handoff.
                additionalAddresses = hotspotInfo.hostAddresses.filter {
                    it != hostAddress && (
                        realSdkInt() > Build.VERSION_CODES.P ||
                            hotspotInfo.backend != WirelessHotspotBackend.EXISTING_WIFI ||
                            it is java.net.Inet4Address
                        )
                },
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
            }
            startedBonjour = bonjourClient
            diagnostics.start()
            // Issue #293: the NSD registration confirmation inside start()
            // can block for seconds (a consistent ~3 s on a field unit), and
            // the Bluetooth bootstrap does not depend on it — the phone only
            // needs the advertisement once it joins the hotspot Wi-Fi, which
            // the bootstrap itself triggers and which takes seconds on the
            // phone side. Start it concurrently so the wait overlaps the
            // bootstrap instead of preceding it. A start failure surfaces in
            // the log; the first-TCP wait still bounds the run.
            Thread({
                try {
                    bonjourClient.start()
                    wirelessChain(7, "Bonjour/mDNS advertisement started")
                } catch (error: Throwable) {
                    debugLog("wireless bonjour start failed: ${error.message}")
                }
            }, "wireless-bonjour-start").apply { isDaemon = true }.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            // 被动观察手机侧 mDNS 流量：回答"手机的查询/广播到底有没有到达我们"。
            // 与 Bonjour 客户端并行，只收不发；失败只记一行日志，不影响建连。
            MdnsSniffer.start(hotspotInfo.interfaceName) { debugLog(it) }
            if (isStaleWirelessRun(generation)) {
                return
            }

            fun pollLateBluetoothBootstrap(deadline: Long, initialError: Throwable?) {
                var lateStream: BlockingDuplexByteStream? = null
                var lateChannel: Iap2Session? = null
                var lateBootstrapDone = false
                var lastLateAttemptMillis = 0L
                var lateRetryBackoffMillis = 3500L
                while (activeSession == null && !wirelessActiveReported.get() && System.currentTimeMillis() < deadline && !isStaleWirelessRun(generation)) {
                    val now = System.currentTimeMillis()
                    if (!lateBootstrapDone && hybridAdapter.isEnabled() && (now - lastLateAttemptMillis >= lateRetryBackoffMillis)) {
                        val connectedDev = runCatching { hybridAdapter.getConnectedDevice() }.getOrNull()
                        val targetDev = connectedDev ?: run {
                            // Issue #247: If no device is connected yet or connection dropped while iPhone was asleep,
                            // periodically retry connecting to target device (or a paired iPhone) to send Bluetooth paging
                            // packets that wake up iOS power-management Bluetooth background sleep state without requiring
                            // the user to manually toggle Bluetooth off and on on the phone.
                            if (device != null) {
                                com.shilapi.xcertplay.bluetooth.CarPlayBluetoothDevice(
                                    name = runCatching { device.name }.getOrNull() ?: "iPhone (${device.address})",
                                    address = device.address,
                                    isConnected = false,
                                )
                            } else {
                                val paired = runCatching { hybridAdapter.getPairedDevices() }.getOrDefault(emptyList())
                                paired.firstOrNull { it.name.contains("iPhone", ignoreCase = true) }
                                    ?: (if (paired.size == 1) paired.first() else null)
                            }
                        }
                        if (targetDev != null && BluetoothAdapter.checkBluetoothAddress(targetDev.address)) {
                            lastLateAttemptMillis = now
                            debugLog("wireless incoming/paired Bluetooth device detected: ${targetDev.name} (${targetDev.address}); bootstrapping iAP2 over RFCOMM")
                            try {
                                onStatus(CarPlayStatus.ConnectingBluetooth)
                                stage = "Bluetooth RFCOMM connection (inbound/connected)"
                                val s = hybridAdapter.openRfcommStream(targetDev.address, IAP2_IPHONE_UUID)
                                lateStream = s
                                try { Thread.sleep(150L) } catch (_: InterruptedException) { if (isStaleWirelessRun(generation)) break }
                                val ch = Iap2Session.openWireless(
                                    s,
                                    traceContext = "wireless-rfcomm-late",
                                    onTrace = ::debugLog,
                                    onArtwork = ::onArtworkTransfer,
                                )
                                synchronized(wirelessResourceLock) {
                                    if (isStaleWirelessRun(generation)) {
                                        ch.close()
                                        return
                                    }
                                    csm = ch
                                    lateChannel = ch
                                }
                                onStatus(CarPlayStatus.RunningWireless)
                                startedHotspot?.validateReady()
                                Iap2WirelessControlClient(
                                    session = ch,
                                    mfi = Iap2MfiAuthenticationClient(mfi),
                                ).run(
                                    identification = bootstrapIdentification,
                                    endpoint = endpoint,
                                    timeoutMillis = WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS,
                                    keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                                    beforeStartSession = { startedHotspot?.validateReady() },
                                    onStartSessionSent = { watchdog.startSessionSent(it.sentAtNanos) },
                                    onPostTransportWiFiConfigurationSent = {
                                        wirelessPostTransportConfigurationSent.set(true)
                                        wirelessDiagnostic("post-transport Wi-Fi config sent; eligible for USB Bluetooth release after AirPlay TCP")
                                        maybeScheduleWirelessBluetoothBootstrapRelease(generation)
                                    },
                                    onIncoming = ::onRouteFrame,
                                    onProgress = { message ->
                                        diagnostics.controlProgress(message)
                                        debugLog(message)
                                    },
                                )
                                lateBootstrapDone = true
                                lateRetryBackoffMillis = 3500L
                                debugLog("late Bluetooth bootstrap completed successfully for ${targetDev.address}")
                            } catch (e: Throwable) {
                                val isBusy = e.message?.contains("busy", ignoreCase = true) == true
                                lateRetryBackoffMillis = if (isBusy) 5000L else 3500L
                                debugLog("late Bluetooth bootstrap attempt failed (backoff=${lateRetryBackoffMillis}ms)", e)
                                runCatching { lateChannel?.close() }
                                lateChannel = null
                                runCatching { lateStream?.close() }
                                lateStream = null
                            }
                        }
                    }
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            if (device != null) {
                onStatus(CarPlayStatus.ConnectingBluetooth)
                stage = "Bluetooth RFCOMM connection"
                debugLog(
                    "wireless RFCOMM connecting address=${device.address} " +
                        "uuid=$IAP2_IPHONE_UUID",
                )
                logBluetoothConnectionSnapshot(device, "before-connect")
                val bluetoothStarted = System.nanoTime()

                var stream: BlockingDuplexByteStream? = null
                var channel: Iap2Session? = null
                var controlResult: Iap2WirelessControlResult? = null
                var lastBootstrapError: Throwable? = null
                var attempt = 0
                val maxAttempts = 4

                while (attempt < maxAttempts && !isStaleWirelessRun(generation)) {
                    attempt++
                    if (activeSession != null || wirelessActiveReported.get()) {
                        debugLog("AirPlay session active over Wi-Fi; continuing without Bluetooth bootstrap")
                        break
                    }
                    try {
                        onStatus(CarPlayStatus.ConnectingBluetooth)
                        stage = "Bluetooth RFCOMM connection"
                        wirelessChain(3, "RFCOMM connecting to iPhone (attempt $attempt/$maxAttempts)")
                        debugLog("wireless RFCOMM connecting (attempt $attempt/$maxAttempts) address=${device.address} uuid=$IAP2_IPHONE_UUID")
                        logBluetoothConnectionSnapshot(device, "before-connect-$attempt")
                        val currentStream = hybridAdapter.openRfcommStream(device.address, IAP2_IPHONE_UUID)
                        bluetoothStream = currentStream
                        stream = currentStream
                        debugLog("wireless RFCOMM connected address=${device.address}")

                        // Issue #218: GOC and serial-bridged Bluetooth modules on legacy head units
                        // (e.g. Allwinner Astar Y3 / KitKat) need a brief settling period after RFCOMM
                        // socket connect before transmitting data over the UART bridge; writing
                        // immediately causes buffer overflow, packet collision, or peer reset.
                        try {
                            Thread.sleep(150L)
                        } catch (_: InterruptedException) {
                            if (isStaleWirelessRun(generation)) break
                        }

                        val currentChannel = Iap2Session.openWireless(
                            currentStream,
                            traceContext = "wireless-rfcomm",
                            onTrace = ::debugLog,
                            onArtwork = ::onArtworkTransfer,
                        )
                        synchronized(wirelessResourceLock) {
                            if (isStaleWirelessRun(generation)) {
                                currentChannel.close()
                                return
                            }
                            csm = currentChannel
                            channel = currentChannel
                        }
                        wirelessChain(4, "iAP2 CSM channel opened over RFCOMM")
                        debugLog("wireless iAP2 CSM channel opened over RFCOMM")
                        if (isStaleWirelessRun(generation)) return

                        onStatus(CarPlayStatus.RunningWireless)
                        debugLog(
                            "wireless Bluetooth iAP2 bootstrap starting " +
                                "location=false vehicleStatus=false",
                        )
                        startedHotspot?.validateReady()
                        val result = Iap2WirelessControlClient(
                            session = currentChannel,
                            mfi = Iap2MfiAuthenticationClient(mfi),
                        ).run(
                            identification = bootstrapIdentification,
                            endpoint = endpoint,
                            timeoutMillis = WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS,
                            keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                            beforeStartSession = { startedHotspot?.validateReady() },
                            onStartSessionSent = { watchdog.startSessionSent(it.sentAtNanos) },
                            onPostTransportWiFiConfigurationSent = {
                                wirelessPostTransportConfigurationSent.set(true)
                                wirelessDiagnostic("post-transport Wi-Fi config sent; eligible for USB Bluetooth release after AirPlay TCP")
                                maybeScheduleWirelessBluetoothBootstrapRelease(generation)
                            },
                            onIncoming = ::onRouteFrame,
                            onProgress = { message ->
                                diagnostics.controlProgress(message)
                                debugLog(message)
                            },
                        )
                        controlResult = result
                        break
                    } catch (e: Throwable) {
                        lastBootstrapError = e
                        // Issue #293: the A14 log carried only "Could not connect RFCOMM" —
                        // the wrapped socket cause (refused/timeout/permission) was dropped
                        // by the message-only overload. Pass the throwable so the report
                        // keeps the cause chain.
                        debugLog("wireless Bluetooth bootstrap attempt $attempt failed", e)
                        runCatching { channel?.close() }
                        channel = null
                        runCatching { stream?.close() }
                        stream = null
                        bluetoothStream = null
                        if (activeSession != null || wirelessActiveReported.get() || isStaleWirelessRun(generation)) {
                            break
                        }
                        if (attempt < maxAttempts) {
                            val isBusy = lastBootstrapError?.message?.contains("busy", ignoreCase = true) == true
                            val delay = if (isBusy) 3000L else 1500L
                            try {
                                Thread.sleep(delay)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }

                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack(generation = generation)
                    return
                }

                if (controlResult != null) {
                    val result = controlResult
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                            debugLog(
                                "wireless RFCOMM EOF: iap2State=${result.stage} " +
                                    "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                                    "transportIdentifier=${result.transportNotificationSeen} " +
                                    "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                                    "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                                    "handoffRequested=${wirelessHandoffRequested.get()} " +
                                    "tunnelReady=${wirelessTunnelReady.get()} " +
                                    "wirelessActive=${wirelessActiveReported.get()}",
                            )
                            if (!wirelessActiveReported.get()) {
                                val handoffInProgress = isWirelessHandoffInProgress(
                                    handoffRequested = wirelessHandoffRequested.get(),
                                    tunnelActive = wirelessTunnelChannel != null,
                                    sessionActive = activeSession != null,
                                    startSessionSent = result.carPlayStartSessionsSent > 0,
                                )
                                if (!handoffInProgress) {
                                    throw IOException(
                                        "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                                    )
                                }
                                if (result.carPlayStartSessionsSent > 0 && !wirelessHandoffRequested.get()) {
                                    armWirelessHandoffWatchdog(generation)
                                }
                                debugLog(
                                    "wireless Bluetooth bootstrap closed during handoff; " +
                                        "keeping the Wi-Fi AirPlay tunnel alive",
                                )
                            }
                        }
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            if (!wirelessActiveReported.get()) {
                                throw IOException("Wireless iAP2 bootstrap timeout after ${WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS}ms " +
                                    "stage=${result.stage} startSessions=${result.carPlayStartSessionsSent} " +
                                    "wifiConfigs=${result.wifiConfigurationsSent} " +
                                    "transportNotified=${result.transportNotificationSeen}; " +
                                    "check iPhone Bluetooth pairing, Wi-Fi and diagnostic log")
                            }
                    }
                } else {
                    debugLog("wireless Bluetooth bootstrap failed or unavailable (${lastBootstrapError?.message}); falling back to Wi-Fi discovery")
                    pollLateBluetoothBootstrap(System.currentTimeMillis() + 60000L, lastBootstrapError)
                    if (isStaleWirelessRun(generation)) {
                        closeWirelessStack(generation = generation)
                        return
                    }
                    if (activeSession != null || wirelessActiveReported.get()) {
                        debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                        onStatus(CarPlayStatus.WirelessActive)
                        return
                    }
                    throw (lastBootstrapError ?: IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot"))
                }
            } else {
                debugLog("wireless Bluetooth bootstrap unavailable; waiting for AirPlay connection over Wi-Fi")
                pollLateBluetoothBootstrap(System.currentTimeMillis() + 60000L, null)
                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack(generation = generation)
                    return
                }
                if (activeSession != null || wirelessActiveReported.get()) {
                    debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                    onStatus(CarPlayStatus.WirelessActive)
                    return
                }
                throw IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot")
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                fail(error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        wirelessChain(8, "iAP2 tunnel opened (iPhone on hotspot WiFi)")
        val generation = wirelessGeneration.get()
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) {
                    wirelessDiagnostics?.connectionAccepted()
                    wirelessAirPlayRequestsInFlight.set(0)
                    wirelessLastAirPlayActivityMs.set(SystemClock.elapsedRealtime())
                    wirelessTcpAcceptedAtMs.set(SystemClock.elapsedRealtime())
                    wirelessDiagnostic("tcp accepted; bootstrap-release quiet timer armed; inFlight=0")
                    scheduleWirelessBootstrapQuietRelease(generation, WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS)
                    scheduleWirelessSessionRecovery(generation, WIRELESS_SESSION_RECOVERY_INITIAL_MILLIS)
                    maybeScheduleWirelessBluetoothBootstrapRelease(generation)
                }
            }

            override fun onControlRequestStarted(method: String, path: String, cseq: String) {
                if (isStaleWirelessRun(generation)) return
                val inFlight = wirelessAirPlayRequestsInFlight.incrementAndGet()
                wirelessLastAirPlayActivityMs.set(SystemClock.elapsedRealtime())
                wirelessDiagnostic("RTSP request start method=$method path=$path cseq=$cseq inFlight=$inFlight")
            }

            override fun onControlRequestCompleted(method: String, path: String, cseq: String, elapsedMillis: Long) {
                if (isStaleWirelessRun(generation)) return
                val inFlight = wirelessAirPlayRequestsInFlight.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                wirelessLastAirPlayActivityMs.set(SystemClock.elapsedRealtime())
                wirelessDiagnostic("RTSP handler done method=$method path=$path cseq=$cseq elapsedMs=$elapsedMillis inFlight=$inFlight")
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                // Issue #343: a new wireless session starts with the auto-disconnect
                // flag clear, so a previous session whose end never fired cannot
                // suppress this session's disconnect.
                autoBtDisabled.set(false)
                if (!watchdog.sessionEstablished()) return
                wirelessDiagnostic("AirPlay session active; Bluetooth bootstrap retained until tunnel handoff")
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                // Keep wirelessActiveReported false until maybeCompleteWirelessHandoff() closes
                // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                // Issue #343: turn Bluetooth back on if this session disabled it.
                if (autoBtDisabled.compareAndSet(true, false)) {
                    runCatching { BluetoothAdapter.getDefaultAdapter()?.enable() }
                    wirelessDiagnostic("autoBtDisconnect=restored (wireless session ended)")
                }
                wirelessDiagnostic("AirPlay session ended activeSessionPresent=${activeSession != null}")
                if (activeSession == null) {
                    watchdog.onTcpClosedBeforeEstablished()
                }
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    /**
     * Do not release Bluetooth while an AirPlay RTSP handler is still working.
     * Pair-setup and SETUP can take several seconds on this head unit.
     */
    private fun scheduleWirelessBootstrapQuietRelease(generation: Int, delayMillis: Long) {
        startupTimer.schedule({
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                activeSession != null || wirelessActiveReported.get()) return@schedule

            val inFlight = wirelessAirPlayRequestsInFlight.get()
            val quietFor = (SystemClock.elapsedRealtime() - wirelessLastAirPlayActivityMs.get()).coerceAtLeast(0L)
            if (inFlight > 0) {
                wirelessDiagnostic("bootstrap release deferred; RTSP handler still running inFlight=$inFlight quietMs=$quietFor")
                scheduleWirelessBootstrapQuietRelease(generation, WIRELESS_BOOTSTRAP_RELEASE_RECHECK_MILLIS)
                return@schedule
            }
            if (quietFor < WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS) {
                wirelessDiagnostic("bootstrap release deferred; RTSP activity recent quietMs=$quietFor")
                scheduleWirelessBootstrapQuietRelease(
                    generation,
                    (WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS - quietFor).coerceAtLeast(1L),
                )
                return@schedule
            }

            synchronized(wirelessResourceLock) {
                if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                    activeSession != null || wirelessActiveReported.get()) return@synchronized
                val lockedInFlight = wirelessAirPlayRequestsInFlight.get()
                val lockedQuietFor = (SystemClock.elapsedRealtime() - wirelessLastAirPlayActivityMs.get()).coerceAtLeast(0L)
                if (lockedInFlight > 0 || lockedQuietFor < WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS) {
                    wirelessDiagnostic("bootstrap release canceled after state recheck inFlight=$lockedInFlight quietMs=$lockedQuietFor")
                    scheduleWirelessBootstrapQuietRelease(
                        generation,
                        if (lockedInFlight > 0) WIRELESS_BOOTSTRAP_RELEASE_RECHECK_MILLIS
                        else (WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS - lockedQuietFor).coerceAtLeast(1L),
                    )
                    return@synchronized
                }
                wirelessDiagnostic("releasing Bluetooth bootstrap after RTSP quiet period quietMs=$lockedQuietFor")
                shutdownUsbBluetoothForStalledHandoff()
                closeBluetoothBootstrapTransport()
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun shutdownUsbBluetoothForStalledHandoff() {
        val dongle = CarPlayBluetoothManager.get(appContext).usbDongle
        if (dongle == null || !dongle.isSupported()) {
            wirelessDiagnostic("stalled handoff: no USB Bluetooth dongle; leaving other Bluetooth adapters unchanged")
            return
        }
        if (!dongle.isEnabled()) {
            wirelessDiagnostic("stalled handoff: USB Bluetooth dongle is already off")
            return
        }
        wirelessDiagnostic("stalled handoff: orderly shutdown of USB Bluetooth RFCOMM/ACL/scan/controller to release iPhone from BT bootstrap")
        val result = runCatching { dongle.shutdownForWirelessHandoff() }
        val stillEnabled = runCatching { dongle.isEnabled() }.getOrDefault(true)
        wirelessDiagnostic(
            "stalled handoff: USB Bluetooth shutdown ${if (result.isSuccess && !stillEnabled) "complete" else "incomplete"} " +
                "failure=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"} stillEnabled=$stillEnabled " +
                "steps=${result.getOrNull() ?: "unavailable"}",
        )
    }

    private fun maybeScheduleWirelessBluetoothBootstrapRelease(generation: Int) {
        if (!wirelessPostTransportConfigurationSent.get() || wirelessTcpAcceptedAtMs.get() == 0L ||
            !wirelessBootstrapReleaseScheduled.compareAndSet(false, true)) return
        wirelessDiagnostic("post-transport config and AirPlay TCP are both present; USB Bluetooth release scheduled in ${WIRELESS_TCP_BT_RELEASE_DELAY_MILLIS}ms")
        scheduleWirelessBluetoothBootstrapRelease(generation, WIRELESS_TCP_BT_RELEASE_DELAY_MILLIS)
    }

    private fun scheduleWirelessBluetoothBootstrapRelease(generation: Int, delayMillis: Long) {
        startupTimer.schedule({
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                activeSession != null || wirelessActiveReported.get()) {
                wirelessDiagnostic("delayed USB Bluetooth release canceled: wireless state changed")
                return@schedule
            }
            // Issue #316: this fixed delay can fire in the middle of a long
            // RTSP handler — on the reporter's unit pair-setup took 18 s
            // while the release closed the bootstrap transport under it and
            // the control channel died with the request still unanswered.
            // A handler in flight is progress, not a stall: wait for it,
            // like the quiet-release path above does. If the handler truly
            // hangs, the session-recovery deadline still fails the session
            // and the state guard above cancels this release.
            val inFlight = wirelessAirPlayRequestsInFlight.get()
            if (inFlight > 0) {
                wirelessDiagnostic("delayed USB Bluetooth release deferred; RTSP handler still running inFlight=$inFlight")
                scheduleWirelessBluetoothBootstrapRelease(generation, WIRELESS_BOOTSTRAP_RELEASE_RECHECK_MILLIS)
                return@schedule
            }
            wirelessDiagnostic("releasing USB Bluetooth after post-transport AirPlay TCP; RTSP inFlight=${wirelessAirPlayRequestsInFlight.get()}")
            shutdownUsbBluetoothForStalledHandoff()
            closeBluetoothBootstrapTransport()
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun wirelessDiagnostic(message: String) {
        debugLog(message)
    }

    private fun scheduleWirelessSessionRecovery(generation: Int, delayMillis: Long) {
        startupTimer.schedule({
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                activeSession != null || wirelessActiveReported.get()) return@schedule

            val elapsed = (SystemClock.elapsedRealtime() - wirelessTcpAcceptedAtMs.get()).coerceAtLeast(0L)
            val inFlight = wirelessAirPlayRequestsInFlight.get()
            if (inFlight > 0 && elapsed < WIRELESS_SESSION_RECOVERY_MAX_MILLIS) {
                wirelessDiagnostic("session recovery deferred; RTSP handler still running inFlight=$inFlight elapsedSinceTcpMs=$elapsed")
                scheduleWirelessSessionRecovery(generation, WIRELESS_SESSION_RECOVERY_RECHECK_MILLIS)
                return@schedule
            }
            wirelessDiagnostic("session recovery firing elapsedSinceTcpMs=$elapsed inFlight=$inFlight")
            fail(IOException("Wireless CarPlay session timed out waiting for RTSP after TCP accept"), generation)
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun onWirelessTunnelReady(generation: Int) {
        synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                wirelessFailureReported.get()) return
            wirelessTunnelReady.set(true)
        }
        wirelessConnectionProof.authenticated(generation)
        wirelessChain(9, "iAP2 tunnel ready (pairing/auth done)")
        debugLog("wireless iAP2 tunnel ready; handoffRequested=${wirelessHandoffRequested.get()}")
        maybeCompleteWirelessHandoff()
    }

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    private fun maybeCompleteWirelessHandoff() {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || wirelessFailureReported.get() ||
                !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    // A queued completion must not close a replacement generation's bootstrap.
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                        wirelessFailureReported.get() || !wirelessTunnelReady.get()) return@Thread
                    wirelessChain(10, "wireless handoff complete, Bluetooth bootstrap closing")
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    closeBluetoothBootstrapTransport()
                    onStatus(CarPlayStatus.WirelessActive, generation)
                    // Issue #343: the session is now fully on Wi-Fi (tunnel ready,
                    // bootstrap transport closed by the app itself), so dropping the
                    // Bluetooth link cannot break the bootstrap anymore. With the
                    // experimental switch on, turn the head unit Bluetooth off to
                    // drop the phone's native Bluetooth link (the native call
                    // screen rides that link); it is turned back on at session end.
                    if (config.carplayAutoDisconnectBt && !autoBtDisabled.get()) {
                        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
                        if (adapter == null) {
                            debugLog("autoBtDisconnect=skipped (no Bluetooth adapter)")
                        } else if (!adapter.isEnabled) {
                            debugLog("autoBtDisconnect=skipped (adapter already off)")
                        } else {
                            // Issue #343: prefer dropping just the HFP link (the
                            // native call screen rides it) over turning the whole
                            // adapter off; fall back to the adapter-off path when
                            // the HFP release is unavailable or unverified.
                            val hfpDevices = runCatching {
                                connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java)
                            }.getOrDefault(emptySet())
                            debugLog("autoBtDisconnect=hfpDevices=${hfpDevices.size}")
                            val hfpReleased = releaseHfpLinks(adapter, hfpDevices)
                            if (hfpReleased) {
                                debugLog("autoBtDisconnect=hfp-released (native HFP link dropped, Bluetooth stays on)")
                            } else {
                                runCatching { adapter.disable() }
                                autoBtDisabled.set(true)
                                debugLog("autoBtDisconnect=disabled (wireless CarPlay connected, experimental switch)")
                            }
                        }
                    }
                }
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun keepBluetoothControlAlive(generation: Int): Boolean = synchronized(wirelessResourceLock) {
        !closed && phase == Phase.WIRELESS && generation == wirelessGeneration.get() &&
            !wirelessFailureReported.get() && activeSession != null &&
            wirelessConnectionProof.hasRenderedFrame(generation)
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    { handleWirelessHandoffTimeout(generation) },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        // Session establishment alone also occurs on black-screen failures. Only a rendered
        // frame proves that projection is usable when the optional tunneled control is absent.
        if (activeSession != null && wirelessConnectionProof.hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            wirelessHandoffFellBack.set(true)
            // The Bluetooth link is the only iAP2 channel here. Closing it would stop NowPlaying,
            // route guidance and call updates for the rest of the session, so keep it until the
            // tunnel really takes over (see maybeCompleteWirelessHandoff).
            debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control")
            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
        } else {
            closeWirelessStack(generation = generation)
            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
        }
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        if (wiredTeardownPending.get()) return // teardown completion resumes discovery
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            logVisibleUsbDevicesThrottled()
            if (config.directUsbfsMode) {
                tryStartDirectAttempt()
            }
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    /**
     * Issue #266 direct mode: the framework discovery found nothing (on the target
     * ROMs it never will), so try the sysfs/usbfs path. Bring-up is blocking (descriptor
     * reads, a possible 30s re-enumeration wait), so it runs on the executor and posts
     * its outcome back here, mirroring openIap2UsbSessionAsync's callback shape.
     */
    private fun tryStartDirectAttempt() {
        if (closed || wiredTeardownPending.get()) return
        // Issue #266 field report: hammering bring-up attempts on every poll (~2 s)
        // makes the iPhone re-enumerate on every cycle — the node number climbs, the
        // kernel configuration falls back and node permissions reset, so the attempts
        // lock themselves out permanently. Pace direct attempts; a detach (a genuine
        // replug) resets the clock.
        val now = System.nanoTime()
        if (now - lastDirectAttemptStartNanos < DIRECT_ATTEMPT_MIN_INTERVAL_NANOS) return
        if (!directAttemptRunning.compareAndSet(false, true)) return
        lastDirectAttemptStartNanos = now
        val generation = availabilityPollGeneration.get()
        executor.execute {
            var posted = false
            try {
                val candidate = runCatching {
                    com.shilapi.xcertplay.transport.SysfsUsbEnumerator()
                        .findMatching(com.shilapi.xcertplay.transport.IphoneUsbMatcher.appleVendor())
                        .firstOrNull()
                }.getOrNull()
                if (candidate == null) {
                    debugLog("direct usbfs: sysfs scan found no iPhone; will retry on the next poll")
                    return@execute
                }
                debugLog("direct usbfs: sysfs found iPhone at ${candidate.usbfsPath}; starting direct bring-up")
                val host = com.shilapi.xcertplay.transport.DirectUsbHost(onDiagnostic = ::connectionDiagnostic)
                when (val result = host.openSession(candidate)) {
                    is com.shilapi.xcertplay.transport.DirectUsbHost.Result.Connected -> {
                        posted = true
                        mainHandler.post {
                            directAttemptRunning.set(false)
                            onDirectSessionConnected(result, generation)
                        }
                    }
                    is com.shilapi.xcertplay.transport.DirectUsbHost.Result.Failed -> {
                        posted = true
                        mainHandler.post {
                            directAttemptRunning.set(false)
                            if (!closed && !wiredTeardownPending.get() &&
                                generation == availabilityPollGeneration.get() && phase == Phase.IPHONE
                            ) {
                                fail(result.error)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                posted = true
                mainHandler.post {
                    directAttemptRunning.set(false)
                    if (!closed && phase == Phase.IPHONE) fail(error)
                }
            } finally {
                if (!posted) directAttemptRunning.set(false)
            }
        }
    }

    /** Integrates a direct-mode session into the wired state machine, then runs the stack. */
    private fun onDirectSessionConnected(
        result: com.shilapi.xcertplay.transport.DirectUsbHost.Result.Connected,
        generation: Int,
    ) {
        if (closed || wiredTeardownPending.get() ||
            generation != availabilityPollGeneration.get() || phase != Phase.IPHONE
        ) {
            result.session.markAborted()
            result.session.close()
            return
        }
        wiredAborted = false
        directDevice = result.device
        availabilityPollGeneration.incrementAndGet()
        val gen = wiredGeneration.incrementAndGet()
        phase = Phase.DATAPATHS
        wiredUsbSession = result.session
        onStatus(CarPlayStatus.OpeningDataPaths)
        wiredChain(3, "iAP2 USB session opened (direct usbfs fd ready)")
        try {
            val ncm = openNcm(result.device.vendorId, result.session)
            wiredNcm = ncm
            if (closed || wiredAborted || gen != wiredGeneration.get()) {
                ncm.markAborted()
                ncm.close()
                result.session.markAborted()
                result.session.close()
                return
            }
            runStack(result.session, ncm)
            startDirectDetachWatch(gen)
        } catch (error: Throwable) {
            result.session.close()
            if (!closed && !wiredAborted && gen == wiredGeneration.get()) fail(error)
        }
    }

    /**
     * Direct mode has no USB detach broadcast to rely on (the USB service that would
     * send it is the broken component), so poll sysfs while a direct session is live
     * and tear down through the normal path when the phone vanishes.
     */
    private fun startDirectDetachWatch(generation: Int) {
        val poll = object : Runnable {
            override fun run() {
                if (closed || generation != wiredGeneration.get()) return
                val device = directDevice ?: return
                val enumerator = com.shilapi.xcertplay.transport.SysfsUsbEnumerator()
                val stillThere = runCatching {
                    enumerator.enumerate().any {
                        it.busNumber == device.busNumber && it.deviceNumber == device.deviceNumber
                    }
                }.getOrDefault(true) // a sysfs read error must not flap the session
                if (!stillThere) {
                    debugLog("direct usbfs: iPhone vanished from sysfs; tearing down the direct session")
                    directDevice = null
                    beginWiredTeardown()
                    return
                }
                mainHandler.postDelayed(this, DIRECT_DETACH_POLL_MILLIS)
            }
        }
        mainHandler.postDelayed(poll, DIRECT_DETACH_POLL_MILLIS)
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed || wiredTeardownPending.get()) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        if (closed || wiredTeardownPending.get()) return
        try {
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted -> {
                    // The system broadcast and the polling fallback can both observe the grant.
                    if (!permissionGrant.compareAndSet(false, true)) return
                    wiredChain(2, "USB permission granted")
                    debugLog("wired iPhone USB permission granted")
                    permissionPollGeneration++
                    when (phase) {
                        Phase.REENUMERATION, Phase.IPHONE -> {
                            if (realSdkInt() < 21) {
                                val attempts = reenumerationAttempts
                                val permissionGeneration = permissionPollGeneration
                                mainHandler.postDelayed({
                                    if (closed || reenumerationAttempts != attempts ||
                                        permissionPollGeneration != permissionGeneration ||
                                        (phase != Phase.REENUMERATION && phase != Phase.IPHONE)) return@postDelayed
                                    iphoneHost.inspectCarPlayConfigurationAsync(result.device, executor) { configuration ->
                                        mainHandler.post {
                                            try {
                                                if (!closed && reenumerationAttempts == attempts &&
                                                    permissionPollGeneration == permissionGeneration &&
                                                    (phase == Phase.REENUMERATION || phase == Phase.IPHONE)) {
                                                    useIphoneConfiguration(result.device, configuration)
                                                }
                                            } catch (error: Throwable) {
                                                fail(error)
                                            }
                                        }
                                    }
                                }, 400L)
                                return
                            }
                            val configuration = runCatching { IphoneCarPlayConfiguration.find(result.device) }.getOrNull()
                            connectionDiagnostic(
                                "USB configuration ready=${configuration != null} " +
                                    "configurationId=${configuration?.id ?: "none"} " +
                                    "reenumerationAttempts=$reenumerationAttempts " +
                                    "action=${when {
                                        configuration != null -> "reuse-descriptors"
                                        reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                        else -> "reject-missing-configuration"
                                    }}",
                            )
                            if (configuration != null) {
                                if (shouldForceReenumerationBeforeReuse() &&
                                    reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS
                                ) {
                                    forcedReenumerationForAttach = true
                                    connectionDiagnostic(
                                        "Previous wired session completed this run; forcing one vendor " +
                                            "re-enumeration before reusing configuration ${configuration.id} (issue #275)",
                                    )
                                    beginReenumeration(result.device)
                                } else {
                                    openDataPaths(result.device, configuration)
                                }
                            } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                                beginReenumeration(result.device)
                            } else {
                                fail(
                                    IphoneUsbException.Protocol(
                                        "iPhone did not expose a complete CarPlay USB configuration",
                                    ),
                                )
                            }
                        }
                        else -> Unit
                    }
                }
                is IphoneUsbHost.PermissionResult.Denied -> {
                    if (phase != Phase.IPHONE && phase != Phase.REENUMERATION) {
                        connectionDiagnostic("Ignoring stale USB permission denial while phase=$phase")
                        return
                    }
                    val currentGranted = runCatching { requireUsbManager().hasPermission(result.device) }.getOrDefault(false) ||
                        runCatching { iphoneHost.discover().any { requireUsbManager().hasPermission(it) } }.getOrDefault(false)
                    if (currentGranted) {
                        connectionDiagnostic("Ignoring USB permission denial because UsbManager reports granted for matching iPhone")
                        return
                    }
                    if (permissionGrant.get()) {
                        connectionDiagnostic("Ignoring USB permission denial because permission was already granted")
                        return
                    }
                    permissionGrant.set(true)
                    onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                try {
                    if (closed || generation != permissionPollGeneration) return
                    val hasPerm = runCatching { requireUsbManager().hasPermission(device) }.getOrDefault(false)
                    if (hasPerm) {
                        onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                        return
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        if (permissionGrant.compareAndSet(false, true)) {
                            onStatus(
                                CarPlayStatus.Failed(
                                    "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                                ),
                            )
                        }
                        return
                    }
                    mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        val generation = availabilityPollGeneration.incrementAndGet()
        onStatus(CarPlayStatus.SelectingConfiguration)

        // Issue #205/#211/#217/#219:
        // When reconnecting rapidly or after an in-place session restart, an already-trusted iPhone
        // may keep its CarPlay configuration active across USB events, but Android descriptor parsing
        // may need a brief moment to settle. Perform a fast non-blocking probe (up to 800ms).
        // If a valid CarPlay configuration is already present, open data paths directly without an unnecessary
        // vendor re-enumeration request. If probe finishes without a configuration, proceed with the standard
        // Apple vendor re-enumeration request so the iPhone receives the 0x51 control transfer and prompts/switches.
        probeCarPlayConfigurationOrReenumerate(device, generation)
    }

    private fun probeCarPlayConfigurationOrReenumerate(device: UsbDevice, generation: Int) {
        val deadlineNanos = System.nanoTime() + CARPLAY_MODE_DESCRIPTOR_PROBE_TIMEOUT_MILLIS * 1_000_000L
        fun attempt() {
            try {
                if (closed || generation != availabilityPollGeneration.get() || phase != Phase.REENUMERATION) return
                iphoneHost.inspectCarPlayConfigurationAsync(device, executor) { configuration ->
                    mainHandler.post {
                        try {
                            if (closed || generation != availabilityPollGeneration.get() || phase != Phase.REENUMERATION) return@post
                            if (configuration != null && !forcedReenumerationForAttach) {
                                connectionDiagnostic("CarPlay configuration ready from fast probe; skipping vendor request")
                                useIphoneConfiguration(device, configuration)
                            } else if (forcedReenumerationForAttach) {
                                // Issue #335: the #275 forced re-enumeration was defeated
                                // here — the fast probe found the stale configuration and
                                // skipped the vendor request, so the phone never saw the
                                // 0x51 transition and the reconnect reused the dead state.
                                // A forced pass must issue the vendor request even when a
                                // configuration is already visible.
                                connectionDiagnostic("Forced re-enumeration active; requesting vendor re-enumeration despite fast-probe configuration (issue #275)")
                                requestVendorReenumerationWithLegacySettle(device, generation)
                            } else if (System.nanoTime() < deadlineNanos) {
                                mainHandler.postDelayed({ attempt() }, CARPLAY_MODE_DESCRIPTOR_PROBE_INTERVAL_MILLIS)
                            } else {
                                connectionDiagnostic("No pre-existing CarPlay configuration found after probe; requesting vendor re-enumeration")
                                requestVendorReenumerationWithLegacySettle(device, generation)
                            }
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
        attempt()
    }

    private fun requestVendorReenumerationWithLegacySettle(device: UsbDevice, generation: Int) {
        // On legacy Android 4.x / API <= 19 (e.g. Allwinner T3, issues #311, #318),
        // allow the kernel usbfs driver a settle delay after closing the previous connection
        // before issuing vendor control transfer to avoid sw_usb spinlock lockups.
        // Note: MUST use realSdkInt() instead of Build.VERSION.SDK_INT because T3 ROMs fake SDK_INT.
        if (realSdkInt() <= Build.VERSION_CODES.KITKAT) {
            mainHandler.postDelayed({
                if (!closed && generation == availabilityPollGeneration.get() && phase == Phase.REENUMERATION) {
                    requestVendorReenumeration(device, generation)
                }
            }, 250)
        } else {
            requestVendorReenumeration(device, generation)
        }
    }

    private fun requestVendorReenumeration(device: UsbDevice, generation: Int) {
        if (closed || phase != Phase.REENUMERATION || generation != availabilityPollGeneration.get()) return
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    if (closed || phase != Phase.REENUMERATION || generation != availabilityPollGeneration.get()) return@requestCarPlayReenumerationAsync
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    pollReenumeration(device)
                }
                is IphoneUsbHost.TransitionResult.Failed -> {
                    // If vendor request failed (e.g. STALL because the device was already in CarPlay mode
                    // or in transition), perform a fallback descriptor inspection before failing.
                    iphoneHost.inspectCarPlayConfigurationAsync(device, executor) { fallbackConfig ->
                        mainHandler.post {
                            if (closed || phase != Phase.REENUMERATION || generation != availabilityPollGeneration.get()) return@post
                            if (fallbackConfig != null) {
                                connectionDiagnostic("Recovered CarPlay configuration after vendor request stall")
                                useIphoneConfiguration(device, fallbackConfig)
                            } else {
                                fail(transition.error)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Issue #275 (extended by #269): force exactly one vendor re-enumeration per
     * physical attach when a previous session reached activation, because the phone
     * keeps a zombie accessory session after an unclean end and then never answers
     * the new control channel (or never opens TCP). The "a session completed" fact
     * is persisted on purpose: #269's log showed the head unit killing the app
     * process between the successful session and the reconnect, so process-level
     * state forgot it — preferences are the only memory that survives, and a real
     * detach (handled in onIphoneDetached) clears it again.
     */
    private fun shouldForceReenumerationBeforeReuse(): Boolean =
        !forcedReenumerationForAttach && runCatching {
            appContext.getSharedPreferences(WIRED_SESSION_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_WIRED_SESSION_COMPLETED, false)
        }.getOrDefault(false)

    private fun persistWiredSessionCompleted(completed: Boolean) {
        runCatching {
            appContext.getSharedPreferences(WIRED_SESSION_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WIRED_SESSION_COMPLETED, completed).apply()
        }
    }

    private fun useIphoneConfiguration(device: UsbDevice, configuration: com.shilapi.xcertplay.transport.CarPlayUsbConfiguration?) {
        connectionDiagnostic("USB descriptor inspection sdk=${Build.VERSION.SDK_INT} " +
            "configuration=${configuration?.id ?: "none"} transitions=$reenumerationAttempts")
        if (configuration != null) {
            if (shouldForceReenumerationBeforeReuse() &&
                reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS
            ) {
                forcedReenumerationForAttach = true
                connectionDiagnostic(
                    "Previous wired session completed this run; forcing one vendor " +
                        "re-enumeration before reusing configuration ${configuration.id} (issue #275)",
                )
                beginReenumeration(device)
                return
            }
            openDataPaths(device, configuration)
        }
        else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) beginReenumeration(device)
        else fail(IphoneUsbException.Protocol("iPhone did not expose a complete CarPlay USB configuration"))
    }

    /** Old vendor USB services sometimes omit ATTACHED after the phone switches configuration. */
    private fun pollReenumeration(previous: UsbDevice) {
        val generation = availabilityPollGeneration.incrementAndGet()
        val started = System.nanoTime()
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (closed || phase != Phase.REENUMERATION || generation != availabilityPollGeneration.get()) return
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val found = runCatching { iphoneHost.discover().firstOrNull() }.getOrNull()
                    val hasCarPlayConfig = found != null && runCatching { IphoneCarPlayConfiguration.find(found) != null }.getOrDefault(false)
                    val isNewDevice = found != null && (found.deviceId != previous.deviceId || found.deviceName != previous.deviceName)
                    if (found != null && (hasCarPlayConfig || isNewDevice || elapsed >= 800)) {
                        availabilityPollGeneration.incrementAndGet()
                        connectionDiagnostic("USB transition polling found device after ${elapsed}ms (hasCarPlayConfig=$hasCarPlayConfig isNewDevice=$isNewDevice)")
                        requestIphonePermission(found)
                        return
                    }
                    if (elapsed >= 30000) {
                        fail(IphoneUsbException.DeviceUnavailable("iPhone USB re-enumeration timed out after 30 seconds"))
                        return
                    }
                    mainHandler.postDelayed(this, 250)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
        mainHandler.postDelayed(poll, 250)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        wiredChain(1, "USB iPhone attached vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}")
        if (closed || wiredTeardownPending.get()) return
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            Phase.DATAPATHS, Phase.CONTROL -> {
                debugLog("wired iPhone attached while in $phase; aborting stale session and requesting permission")
                phase = Phase.IPHONE
                beginWiredTeardown()
            }
            Phase.WIRELESS, Phase.IDLE -> {
                debugLog("wired iPhone attached while in $phase; transport=${config.transport}")
                if (config.transport == CarPlayTransport.WIRED) {
                    phase = Phase.IPHONE
                    availabilityPollGeneration.incrementAndGet()
                    requestIphonePermission(device)
                }
            }
            else -> Unit
        }
    }

    private fun onIphoneDetached(device: UsbDevice) {
        debugLog(
            "wired USB detached: vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} dev=${device.deviceName}",
        )
        if (closed) return
        when (phase) {
            Phase.REENUMERATION -> {
                debugLog("wired iPhone detached during expected re-enumeration transition")
            }
            Phase.IPHONE, Phase.DATAPATHS, Phase.CONTROL -> {
                debugLog("wired iPhone detached; resetting to waiting for iPhone")
                // A real detach ends the attach episode: the next plug may need its
                // own forced re-enumeration after a completed session (issue #275).
                forcedReenumerationForAttach = false
                persistWiredSessionCompleted(false)
                // A replug is a fresh start, not a retry: direct pacing restarts too.
                lastDirectAttemptStartNanos = 0L
                phase = Phase.IPHONE
                beginWiredTeardown()
                mainHandler.post {
                    if (!closed) {
                        onStatus(CarPlayStatus.WaitingForIphone)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun beginWiredTeardown() {
        if (!wiredTeardownPending.compareAndSet(false, true)) return
        wiredAborted = true
        wiredGeneration.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        permissionPollGeneration++
        permissionGrant.set(false)
        wiredUsbSession?.markAborted()
        wiredNcm?.markAborted()
        mux?.markAborted()
        vpnService?.markWiredAborted()

        val watchdog = Runnable {
            if (wiredTeardownPending.compareAndSet(true, false)) {
                debugLog("wired teardown barrier timed out after 2500ms; forcing reset")
                if (!closed) {
                    phase = Phase.IPHONE
                    checkIphoneAvailability()
                }
            }
        }
        mainHandler.postDelayed(watchdog, 2500L)

        // The single control executor is running the long-lived iAP2 loop.
        // Enqueuing teardown there waits behind the loop it must stop.
        Thread({
            abortActiveWiredSession()
            try {
                // Barrier: the old runStack must exit before a new USB open.
                executor.execute {
                    mainHandler.removeCallbacks(watchdog)
                    mainHandler.post {
                        wiredTeardownPending.set(false)
                        if (!closed) {
                            phase = Phase.IPHONE
                            checkIphoneAvailability()
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                mainHandler.removeCallbacks(watchdog)
                wiredTeardownPending.set(false)
            }
        }, "wired-detach-teardown").apply { isDaemon = true }.start()
    }

    private fun abortActiveWiredSession() {
        wiredAborted = true
        debugLog("wired abortActiveWiredSession: closing active wired session, csm, mux, and vpn")
        closeBestEffort("activeSession") {
            val session = activeSession
            if (session != null) sessionListener.onSessionEnded(session)
            session?.close()
        }
        closeBestEffort("CSM") {
            val session = csm
            csm = null
            session?.close()
        }
        closeBestEffort("USBMUX") {
            val muxHost = mux
            mux = null
            muxHost?.markAborted()
            muxHost?.close()
        }
        if (config.transport == CarPlayTransport.WIRED) {
            closeBestEffort("VPN/NCM") {
                vpnService?.detach(isAborted = true)
            }
        }
        wiredNcm?.let { closeBestEffort("detached NCM") { it.close() } }
        wiredNcm = null
        wiredUsbSession?.let { closeBestEffort("detached USB") { it.close() } }
        wiredUsbSession = null
    }

    /**
     * Throttled diagnostic for the "iPhone not found" wait loop: records what the OS actually
     * sees on the USB bus, so a future report instantly distinguishes "the iPhone never
     * enumerated" (cable / port / iPhone USB-accessories locked) from "it enumerated but was
     * filtered out". VID/PID only; no serials or identities.
     */
    private fun logVisibleUsbDevicesThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoIphoneDevicesLogNanos < NO_IPHONE_DEVICES_LOG_INTERVAL_MILLIS) return
        lastNoIphoneDevicesLogNanos = now
        val devices = runCatching { usbManager?.deviceList?.values?.toList().orEmpty() }.getOrDefault(emptyList())
        val summary = devices.joinToString(" | ") { dev ->
            val isApple = dev.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
            val typeHint = when {
                isApple -> "Apple/iPhone"
                dev.deviceClass == 0x0e || (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == 0x0e } -> "Dashcam/Video"
                dev.deviceClass == 0x08 || (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == 0x08 } -> "MassStorage/UDisk"
                else -> "Peripheral"
            }
            "vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)} ($typeHint dev=${dev.deviceName})"
        }
        val hasNonApplePeripheral = devices.any { it.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID }
        if (hasNonApplePeripheral) {
            debugLog("wired waiting for iPhone on available USB port; non-Apple peripheral(s) detected: [$summary]")
        } else {
            debugLog("wired waiting for iPhone; visible USB devices: [$summary]")
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice, knownConfiguration: com.shilapi.xcertplay.transport.CarPlayUsbConfiguration? = null) {
        if (closed || wiredTeardownPending.get()) return
        wiredAborted = false
        val generation = wiredGeneration.incrementAndGet()
        phase = Phase.DATAPATHS
        availabilityPollGeneration.incrementAndGet()
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor, knownConfiguration) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    wiredUsbSession = result.session
                    if (closed || wiredAborted || generation != wiredGeneration.get()) {
                        result.session.markAborted()
                        result.session.close()
                        return@openIap2UsbSessionAsync
                    }
                    wiredChain(3, "iAP2 USB session opened (USBMUX fd ready)")
                    try {
                        val ncm = openNcm(device.vendorId, result.session)
                        wiredNcm = ncm
                        if (closed || wiredAborted || generation != wiredGeneration.get()) {
                            ncm.markAborted()
                            ncm.close()
                            result.session.markAborted()
                            result.session.close()
                            return@openIap2UsbSessionAsync
                        }
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        if (!closed && !wiredAborted && generation == wiredGeneration.get()) fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> {
                    if (!closed && !wiredAborted && generation == wiredGeneration.get()) fail(result.error)
                }
            }
        }
    }

    private fun openNcm(vendorId: Int, usbSession: Iap2UsbSession): NcmUsbBridge {
        // Sessions are only built after a CarPlay configuration was resolved (framework
        // inspector or direct-mode descriptor read), so the configuration is always
        // carried by the session itself.
        val configuration = usbSession.carPlayConfiguration
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        wiredChain(4, "NCM config=${configuration.id} function found")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${IphoneCarPlayConfiguration.alternateSetting(function.control)}" +
                " data=${function.data.id}/${IphoneCarPlayConfiguration.alternateSetting(function.data)}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        // Keep USBMUX and NCM on the same authorized usbfs fd. Several Android 7/T3
        // kernels reject interface claims made through a second openDevice() fd even
        // though the CarPlay configuration is active; usbfs then reports ENOENT.
        val sharedConnection = usbSession.retainConnectionForNcm()
        debugLog("ncm sharing USBMUX UsbDeviceConnection")
        // The async UsbRequest path needs the platform requestWait(timeout) overload or
        // the API 24/25 worker. Android 4.x (Dalvik) runs synchronous bulk reads, exactly
        // like the vendor stack: its UsbRequest JNI queues a global ref per queue() and
        // frees requests through REAPURB, which aborts the process on any lifecycle slip.
        val bridge = NcmUsbBridge.open(
            sharedConnection = sharedConnection,
            function = function,
            expectedConfiguration = configuration.id,
            vendorId = vendorId,
            useAsyncRead = realSdkInt() >= ASYNC_USB_READ_MIN_SDK,
            onDiagnostic = ::connectionDiagnostic,
        )
        wiredChain(5, "NCM bridge opened (bulk endpoints claimed)")
        return bridge
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed || wiredAborted) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            wiredChain(6, "USBMUX host opened")
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            wiredChain(7, if (savedPairRecord != null) "Lockdown pairing ok (saved record)" else "Lockdown pairing ok (new record)")
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            // Keep the lab-only syslog relay disabled in production builds. Opening an additional
            // USBMUX service on legacy head units increases USB-host concurrency during bring-up.
            if (ENABLE_PHONE_DIAGNOSTIC_CAPTURE) try {
                val relay = carKitClient.openService(pairRecord, config.label, "com.apple.syslog_relay")
                Thread({
                    try {
                        relay.use {
                            val deadline = System.nanoTime() + 120_000_000_000L
                            val pending = StringBuilder()
                            val relevant = Regex(" (accessoryd|ACCCarPlayService|iap2d|CarPlay)([\\[(])", RegexOption.IGNORE_CASE)
                            while (!closed && System.nanoTime() < deadline) {
                                val bytes = relay.recv(8192, 1000) ?: continue
                                if (bytes.isEmpty()) break
                                pending.append(bytes.toString(Charsets.UTF_8).replace('\u0000', '\n'))
                                while (true) {
                                    val end = pending.indexOf("\n")
                                    if (end < 0) break
                                    val line = pending.substring(0, end)
                                    pending.delete(0, end + 1)
                                    if (relevant.containsMatchIn(line)) debugLog("PHONE ${line.take(2000)}")
                                }
                                if (pending.length > 65536) pending.clear()
                            }
                        }
                        debugLog("phone authentication diagnostic capture ended")
                    } catch (error: Exception) {
                        debugLog("phone authentication diagnostic capture ended: ${error.javaClass.simpleName}")
                    }
                }, "carplay-lab-phone-diagnostics").apply { isDaemon = true; start() }
                debugLog("phone authentication diagnostic capture started")
            } catch (error: Exception) {
                debugLog("phone authentication diagnostics unavailable: ${error.message}")
            }
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord != null && rejection != null) {
                    debugLog("saved Lockdown pair record rejected by iPhone ($rejection); clearing and pairing again")
                    clearPairRecord()
                    pairRecord = pairNewRecord(pairingClient)
                    carKitClient.open(pairRecord, config.label)
                } else {
                    throw error
                }
            }
            wiredChain(8, "CarKit service opened")
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        debugLog("wired link TX begin ${wireSummary(data)}")
                        // Bound each TLS write while diagnosing the stalled certificate transfer.
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            wiredChain(9, "iAP2 CSM channel opened")
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            if (closed || wiredAborted) return
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            val vpnAttachError = attachVpn(ncm, ncmHostMac)
            if (vpnAttachError != null) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport: $vpnAttachError")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            // Issue #137: capture the attached service for the NO_AIRPLAY_TCP watchdog.
            // The vpnService field can be cleared by teardown before the watchdog fires,
            // and `?.let` was silently dropping the TCP snapshot that distinguishes
            // SYN-ACK loss from a broken loopback relay.
            val watchdogSnapshotService = vpnService
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            wiredChain(12, "MFi coprocessor client ready")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = vpnService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    debugLog("wired $message")
                    // Issue #187: match the exact send event only. The watchdog's own
                    // timeout message ("... after carplay-start-session") must not re-arm
                    // the chain, re-send the NA, or clear wiredTcpArrived.
                    if (message == "iap2 tx=0x4301 carplay-start-session") {
                        wiredChain(14, "iAP2 StartSession (0x4301) sent; waiting for iPhone mDNS/TCP")
                        vpnService?.sendUnsolicitedNeighborAdvertisement(force = true)
                        // Issue #95: arm the first-TCP watchdog 鈥?a new StartSession means any
                        // previous session no longer counts.
                        // Issue #208: do NOT reset if a session is already active (e.g. iPhone
                        // re-sent availability after screen-off); the old TCP is still alive and
                        // resetting here causes a false-positive watchdog reset.
                        if (activeSession == null) wiredTcpArrived.set(false)
                    }
                },
                airPlayTcpArrived = { wiredTcpArrived.get() },
                // Issue #134: the peer-silence reset must not tear down a session whose media is still
                // arriving; the NTDA3's iPhone stops sending iAP2 frames for minutes at a time.
                airPlayDataRecent = { AirPlayMediaActivity.recent() },
            )
            if (wiredAborted || closed || phase == Phase.IPHONE) {
                debugLog("wired control loop finished after abort/detach")
                return
            }
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT ->
                        CarPlayStatus.Failed("Wired CarPlay control timed out at ${result.stage}; StartSession sent=${result.carPlayStartSessionsSent}")
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                    // Issue #84/#61: the iPhone went quiet long after StartSession — the old
                    // code spun here forever on "控制通道运行中". Fail loudly so the existing
                    // reconnect path rebuilds the link instead.
                    Iap2WiredControlTerminal.PEER_SILENT ->
                        CarPlayStatus.Failed("Wired CarPlay peer silent; resetting link")
                    // Issue #95: iPhone alive on iAP2 but never opened the AirPlay TCP
                    // connection — fail fast instead of idling until USB wedges.
                    // Issue #155/#123: capture the kernel TCP-table snapshot here so the
                    // report shows whether injected SYNs ever reached the TCP stack.
                    Iap2WiredControlTerminal.NO_AIRPLAY_TCP -> {
                        val service = watchdogSnapshotService ?: vpnService
                        if (service != null) {
                            debugLog("wired ${service.tunTcpSnapshot()}")
                        } else {
                            debugLog("wired NO_AIRPLAY_TCP: transport snapshot unavailable (vpn service reference lost)")
                        }
                        CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession; resetting link")
                    }
                },
            )
        } catch (error: Throwable) {
            if (wiredAborted || closed || phase == Phase.IPHONE) {
                debugLog("wired bring-up aborted due to detach: ${error.message}")
                if (!ncmOwnedLocally) vpnService?.detach()
                return
            }
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // Use realSdkInt(): T3 fakes SDK_INT=27, so the raw value would
        // disagree with the realSdkInt() check below (P1-5).
        if (config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P && realSdkInt() < 28) {
            return startLegacyWifiDirectWithFallback(generation)
        }
        // Within the first minutes after a cold boot the car hotspot may simply
        // not exist yet (the vendor service brings it up well after apps start),
        // so both the auto-enable step and the readiness observation below get
        // the longer boot budget instead of failing attempts against a hotspot
        // that is still starting. Manual connects later in the session keep the
        // regular budget.
        val bootGrace = SystemClock.elapsedRealtime() < WirelessStartupPolicy.BOOT_GRACE_MILLIS
        val readyMillis = if (bootGrace) {
            WirelessStartupPolicy.BOOT_HOTSPOT_READY_MILLIS
        } else {
            WirelessStartupPolicy.HOTSPOT_READY_MILLIS
        }
        if (bootGrace) {
            debugLog("generation=$generation hotspot readiness on boot budget ${readyMillis}ms")
        }
        val readyDeadline = System.nanoTime() + readyMillis * 1_000_000
        val hotspotMode = config.wirelessHotspotMode
        if (com.shilapi.xcertplay.network.CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            val result = com.shilapi.xcertplay.network.CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !com.shilapi.xcertplay.network.CarHotspotSettings.enabled(appContext) },
                timeoutMillis = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting hotspot interface") },
            )
            // Issue #271: in manual mode the driver runs the hotspot themselves, so a
            // failed auto-enable must not hard-block the attempt — some vendor ROMs
            // run the AP outside the framework and report it off while phones are
            // connected to it. The manual manager is the readiness authority there:
            // it samples the interfaces itself and times out precisely if the AP is
            // genuinely absent.
            val knownOff = com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false
            val blockedByConfig = knownOff && (
                result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.UNSUPPORTED ||
                result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.PERMISSION_REQUIRED
            )
            val manualFallback = hotspotMode == WirelessHotspotMode.MANUAL &&
                result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.READY &&
                result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.CANCELLED &&
                !blockedByConfig
            if (manualFallback) {
                debugLog("car hotspot auto-enable ended with $result; continuing with the manual hotspot manager (issue #271)")
            }
            if (result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                        result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (realSdkInt() > 28 && hotspotMode == WirelessHotspotMode.MANUAL) {
            // Port of upstream #789: respect the driver's choice to trust the hotspot over Android's report.
            val state = com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext)
            if (com.shilapi.xcertplay.network.CarHotspotSettings.reportedOff(appContext, state)) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "The car hotspot is off. Turn it on in the car settings and connect again.")
            }
            if (state == false) debugLog("car hotspot: Android reports it off; connecting anyway, as the driver chose")
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> {
                check(realSdkInt() >= 28)
                WifiP2pGroupManager(appContext, ::debugLog, preferredChannel = config.wifiP2pPreferredChannel)
            }
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> {
                check(realSdkInt() >= 26) { "Local hotspot requires Android 8; choose Wi-Fi Direct or Car hotspot" }
                LocalOnlyHotspotManager(appContext, ::debugLog)
            }
            WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                appContext, config.existingWifiSsid, config.existingWifiPassphrase, ::debugLog,
                onNetworkChanged = { if (!isStaleWirelessRun(generation)) restartWireless() },
            )
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = { debugLog("generation=$generation $it") },
                isCancelled = { isStaleWirelessRun(generation) },
            )
            WirelessHotspotMode.HOSTAPD -> HostapdHotspotManager(
                context = appContext,
                ssid = config.hostapdSsid
                    ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Hostapd hotspot SSID is not configured"),
                passphrase = config.hostapdPassphrase.orEmpty(),
                channel = config.hostapdChannel,
                appleVendorIe = config.hostapdAppleVendorIe,
                onDiagnostic = { debugLog("generation=$generation $it") },
                isCancelled = { isStaleWirelessRun(generation) },
            )
        }
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            hotspot = manager
        }
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.HOSTAPD) {
            // The root backend sequences several root shell steps (stale
            // process cleanup, radio bring-up, hostapd + dnsmasq start, and a
            // Bluetooth stack reset); give it more room than the framework
            // backends before the outer retry policy takes over.
            HOSTAPD_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            // Issue #284: the observation budget used to be whatever remained of the
            // shared 15 s after the auto-enable step ate its share. On slow head
            // units (Changan CS75 Plus) enabling takes most of it, the interface is
            // then never observed stable in time, and the attempt fails even though
            // the hotspot came up seconds later — retries only succeed when the two
            // phases happen to interleave favourably (~90 s in the report). Give the
            // observation phase its own full readiness budget; the outer retry cap
            // still bounds the total. On the boot budget both phases get the longer
            // window (see bootGrace above).
            readyMillis
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun startLegacyWifiDirectWithFallback(generation: Int): WirelessHotspotInfo {
        var primary: WirelessHotspotManager? = null
        try {
            primary = LegacyWifiP2pGroupManager(appContext, ::debugLog)
            hotspot = primary
            debugLog("wireless legacy Wi-Fi Direct starting")
            return primary.start(LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS)
        } catch (primaryFailure: Exception) {
            if (hotspot === primary) hotspot = null
            closeBestEffort("legacy-wifi-p2p") { primary?.close() }
            if (isStaleWirelessRun(generation)) throw primaryFailure
            if (realSdkInt() < 26) {
                val causeMessage = primaryFailure.message ?: primaryFailure.javaClass.simpleName
                // Issue #331: keep an already-actionable unsupported message
                // (from checkPrerequisites) instead of overwriting it with
                // the car-hotspot hint, and never blame "Android 7" on a
                // device running another version.
                if (causeMessage.contains("is not supported on this head unit")) throw primaryFailure
                if (causeMessage.contains("code=1")) throw IOException(
                    "Wi-Fi Direct is not supported on this head unit (createGroup code=1); " +
                        "use Existing Wi-Fi / Same LAN or a wired connection instead: $causeMessage",
                    primaryFailure,
                )
                throw IOException(
                    "Wi-Fi Direct could not start on this device; use the car hotspot: $causeMessage", primaryFailure,
                )
            }
            debugLog(
                "legacy Wi-Fi Direct failed; falling back to LocalOnlyHotspot: " +
                    (primaryFailure.message ?: primaryFailure.javaClass.simpleName),
            )

            val fallback = LocalOnlyHotspotManager(appContext, ::debugLog)
            hotspot = fallback
            try {
                return fallback.start(HOTSPOT_START_TIMEOUT_MILLIS)
            } catch (fallbackFailure: Exception) {
                if (hotspot === fallback) hotspot = null
                closeBestEffort("legacy-local-hotspot-fallback") { fallback.close() }
                if (isStaleWirelessRun(generation)) throw fallbackFailure
                fallbackFailure.addSuppressed(primaryFailure)
                throw IOException(
                    "Wi-Fi Direct failed (${primaryFailure.message ?: primaryFailure.javaClass.simpleName}); " +
                        "local hotspot fallback also failed " +
                        "(${fallbackFailure.message ?: fallbackFailure.javaClass.simpleName})",
                    fallbackFailure,
                )
            }
        }
    }
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun createWifiP2pManager(): WirelessHotspotManager = WifiP2pGroupManager(appContext, ::debugLog)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun createLocalOnlyHotspotManager(): WirelessHotspotManager = LocalOnlyHotspotManager(appContext, ::debugLog)

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(this) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val hybridBt = CarPlayBluetoothManager.get(appContext)
        val usbDongle = hybridBt.usbDongle
        val dongleActive = usbDongle != null && usbDongle.isEnabled()

        if (dongleActive) {
            val connected = usbDongle.getConnectedDevice()
            if (connected != null && BluetoothAdapter.checkBluetoothAddress(connected.address)) {
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "Using connected device from USB dongle: ${connected.name} (${connected.address})",
                )
                return adapter.getRemoteDevice(connected.address)
            }
            val paired = usbDongle.getPairedDevices()
            config.wirelessBluetoothDeviceAddress?.let { selected ->
                paired.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.let {
                    return adapter.getRemoteDevice(it.address)
                }
            }
            if (paired.isNotEmpty()) {
                val pairedIPhones = paired.filter { it.name.contains("iPhone", ignoreCase = true) }
                if (pairedIPhones.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedIPhones.single().address)) {
                    return adapter.getRemoteDevice(pairedIPhones.single().address)
                }
                if (paired.size == 1 && BluetoothAdapter.checkBluetoothAddress(paired.first().address)) {
                    return adapter.getRemoteDevice(paired.first().address)
                }
            }
            // USB dongle is active and waiting for initial pairing/inbound connection from iPhone.
            // Do not pick an unrelated audio-only device from Android native Bluetooth, which would
            // cause continuous 5s Page Timeouts and block the USB dongle from answering inquiry scans.
            throw IOException("USB Bluetooth dongle active; waiting for initial iPhone pairing to ${usbDongle.getStatus().localBdAddr ?: "dongle"}")
        }

        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.let { return it }
            if (BluetoothAdapter.checkBluetoothAddress(selected)) {
                runCatching { adapter.getRemoteDevice(selected) }.getOrNull()?.let { return it }
            }
            debugLog("saved wireless iPhone is no longer paired; falling back to current bonded devices")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        val connectedDevice = hybridBt.getConnectedDevice()
        if (connectedDevice != null && BluetoothAdapter.checkBluetoothAddress(connectedDevice.address)) {
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "Using connected device from ${hybridBt.getAdapterName()}: " +
                    "${connectedDevice.name} (${connectedDevice.address})",
            )
            return adapter.getRemoteDevice(connectedDevice.address)
        }
        val pairedDevices = hybridBt.getPairedDevices()
        val pairedIPhones = pairedDevices.filter { it.name.contains("iPhone", ignoreCase = true) }
        if (pairedIPhones.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedIPhones.single().address)) {
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "Using single paired iPhone from ${hybridBt.getAdapterName()}: " +
                    "${pairedIPhones.single().name} (${pairedIPhones.single().address})",
            )
            return adapter.getRemoteDevice(pairedIPhones.single().address)
        }
        if (pairedDevices.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedDevices.single().address)) {
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "Using single paired device from ${hybridBt.getAdapterName()}: " +
                    "${pairedDevices.single().name} (${pairedDevices.single().address})",
            )
            return adapter.getRemoteDevice(pairedDevices.single().address)
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        runCatching { bluetoothAdapter?.cancelDiscovery() }
            .onFailure { debugLog("wireless Bluetooth discovery cancel failed: ${it.javaClass.simpleName}") }
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val bondState = device.bondState
            val adapterState = runCatching { bluetoothAdapter?.state }.getOrNull()?.let { state ->
                when (state) {
                    BluetoothAdapter.STATE_OFF -> "OFF"
                    BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                    BluetoothAdapter.STATE_ON -> "ON"
                    BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                    else -> "UNKNOWN($state)"
                }
            } ?: "unknown"
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "adapterState=$adapterState " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService, generation: Int? = null) =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            val diagnostics = wirelessDiagnostics
            wirelessDiagnostics = null
            diagnostics?.close()
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            val activeTunnel = wirelessTunnelChannel
            wirelessTunnelChannel = null
            if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

            closeBluetoothBootstrapTransport()

            val activeBonjour = bonjour
            bonjour = null
            if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }
            closeBestEffort("mdns sniff") { MdnsSniffer.stop() }

            val activeHotspot = hotspot
            hotspot = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessPublishedAddress = null
            wirelessAlternateAddress = null
            wirelessAddressRecovery = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)

            if (service != null && owner != null) closeBestEffort("AirPlay service") { service.detachWireless(owner) }
        }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: Exception) {
        // ReflectiveOperationException is API 19 and Android 4.2's verifier rejects the
        // whole class when a catch type cannot be resolved, so keep this at Exception.
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    /**
     * Issue #343: try to drop just the phone's native HFP link instead of
     * turning the whole head-unit Bluetooth off. There is no public API for
     * this — BluetoothHeadset.disconnect is hidden — so this is best-effort:
     * invoke it by reflection, verify the link is actually gone, and fall
     * back to the whole-adapter-off path on any failure. Returns true only
     * on a verified release.
     */
    private fun releaseHfpLinks(adapter: BluetoothAdapter, targets: Set<BluetoothDevice>): Boolean {
        if (targets.isEmpty()) return false
        val latch = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (proxy is BluetoothHeadset) {
                        for (device in targets) {
                            val invoked = runCatching {
                                val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                method.invoke(proxy, device) as? Boolean == true
                            }.getOrDefault(false)
                            if (!invoked) continue
                            var gone = false
                            for (attempt in 0 until 3) {
                                if (proxy.connectedDevices.orEmpty().none { it.address == device.address }) {
                                    gone = true
                                    break
                                }
                                try {
                                    Thread.sleep(300)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                            }
                            if (gone) released.set(true)
                        }
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not release HFP link", error)
                } finally {
                    runCatching { adapter.closeProfileProxy(profileId, proxy) }
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        val ok = runCatching { adapter.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET) }.getOrDefault(false)
        if (!ok) return false
        latch.await(4, TimeUnit.SECONDS)
        return released.get()
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter?): String {
        val managerAddress = try {
            CarPlayBluetoothManager.get(appContext).localAddress(appContext)
        } catch (_: Throwable) {
            null
        }
        val address = managerAddress ?: BluetoothAddressResolver.resolve(appContext)
        return address ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    /** Issue #293 (upstream port): after a first-TCP timeout, advertise the other address family next time. */
    private fun flipWirelessAddressFamily(generation: Int) = synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return@synchronized
        val published = wirelessPublishedAddress
        val next = published?.let { wirelessAddressRecovery?.nextFamily(it) }
        val from = published?.let {
            if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
        }
        if (isStaleWirelessRun(generation)) return@synchronized
        if (next == null || from == null) {
            debugLog(
                "wireless address family flip skipped published=${from ?: "none"} " +
                    "alternate=${next != null}",
            )
            return@synchronized
        }
        appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOTSPOT_FAMILY_PREFERENCE_KEY, next)
            .apply()
        debugLog("wireless address family flip published=$from next=$next")
    }

    private fun hotspotCandidates(info: WirelessHotspotInfo, fallbackToSnapshot: Boolean = true): List<InetAddress> {
        val name = info.interfaceName ?: return info.hostAddresses
        val scanned = try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
        } catch (error: Exception) {
            debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
            emptyList()
        }
        return if (scanned.isEmpty() && fallbackToSnapshot) info.hostAddresses else scanned
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        var timedOut = false
        // Issue #281: a close can block indefinitely on a dead channel (e.g. the
        // wireless stack closing an RFCOMM channel whose peer is gone). Teardown ran
        // every close inline on one thread, so a single stuck resource wedged the
        // whole teardown: no "teardown end" line, the session executor never shut
        // down, and the app could not start again. Bound each close; a stuck one is
        // abandoned on its daemon thread as a zombie and teardown moves on.
        val done = CountDownLatch(1)
        val worker = Thread(
            {
                try {
                    close()
                    completed.set(true)
                } catch (error: Throwable) {
                    debugLog("$name teardown failed", error)
                } finally {
                    done.countDown()
                }
            },
            "xcertplay-teardown-close",
        )
        worker.isDaemon = true
        worker.start()
        try {
            if (!done.await(TEARDOWN_RESOURCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                timedOut = true
                debugLog("$name teardown timed out; abandoning the stuck close")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            connectionDiagnostic(
                "teardown resource=$name completed=${completed.get()} timedOut=$timedOut " +
                    "elapsedMs=${elapsedMillis(started)}",
            )
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        // Identification/authentication remain bounded. The bootstrap loop can extend this
        // deadline only once this generation has actually rendered a CarPlay frame.
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): String? {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return "VPN service bind timed out or was unavailable"
        }
        wiredChain(10, "VPN service bound")
        debugLog("wired network attach; userspaceNetworkEnabled=${config.userspaceNetworkEnabled}")
        val result = try {
            if (config.userspaceNetworkEnabled) {
                service.attachUserspace(
                    ncm = ncm,
                    linkLocal = config.linkLocal,
                    hostMac = hostMac,
                    config = airPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfiSession?.client,
                    listener = sessionListener,
                    media = media,
                )
            } else {
                service.attach(
                    ncm = ncm,
                    linkLocal = config.linkLocal,
                    hostMac = hostMac,
                    config = airPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfiSession?.client,
                    listener = sessionListener,
                    media = media,
                )
            }
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return "VPN service attach raised ${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
        }
        return when (result) {
            is CarPlayVpnService.AttachResult.Started -> {
                wiredChain(11, "VPN/NCM transport attached (TUN up)")
                debugLog("wired VPN/NCM transport attach result=started")
                null
            }
            CarPlayVpnService.AttachResult.Reused -> {
                // TUN reused, NCM bridge swapped. The old NCM is closed by the
                // service when the bridge was swapped; do not close the new one.
                wiredChain(11, "VPN/NCM transport attached (TUN reused)")
                debugLog("wired VPN/NCM transport attach result=reused (TUN kept, fwmark preserved)")
                null
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                "VPN/NCM attachment was already started"
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                wiredChain(11, "VPN/NCM transport attach FAILED: ${result.message}")
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                service.lastAttachFailure?.let { debugLog("wired VPN/NCM root failure $it") }
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                result.message
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        // A failed earlier bind may have counted down the previous latch. Reset it
        // before each new bind or a reconnect can observe a stale completed latch.
        vpnLatch = CountDownLatch(1)
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, detachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        detachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun requireUsbManager(): UsbManager =
        usbManager ?: throw IOException("USB service is unavailable on this device")

    private fun hasRequiredUsbService(): Boolean {
        if (usbManager != null ||
            config.transport != CarPlayTransport.WIRED && config.mfiTarget != MfiTarget.USB_CH341) return true
        fail(IOException("USB service is unavailable on this device"))
        return false
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val seenCauses = HashSet<Throwable>()
            val causes = generateSequence(error) { it.cause }
                .takeWhile { seenCauses.add(it) }
                .toList()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                causes.any { it is com.shilapi.xcertplay.network.P2pResetRequiredException },
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason),
                if (wireless) wirelessGeneration.get() else null)
        }
    }

    /**
     * T3 有线全链路打点：从 USB 到 AirPlay 会话共 16 步，统一前缀方便 grep。
     * 哪一步没出现，下一步就不会来，直接定位断点。
     */
    private fun wiredChain(step: Int, message: String) {
        debugLog("WIRED-CHAIN [$step/16] $message")
    }

    /**
     * 无线全链路打点：从蓝牙到 AirPlay 会话共 12 步，统一前缀方便 grep。
     */
    private fun wirelessChain(step: Int, message: String) {
        debugLog("WIRELESS-CHAIN [$step/12] $message")
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        val safeTrace = runCatching { error.safeStackTrace(maxCauses = 4, maxFramesPerCause = 30) }.getOrNull()
        if (safeTrace != null) {
            Log.w(IphoneCarPlayConfiguration.TAG, "$message: ${describeThrowable(error)}\n$safeTrace")
        } else {
            Log.w(IphoneCarPlayConfiguration.TAG, "$message: ${describeThrowable(error)}")
        }
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ap-network=[configured] band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val ENABLE_PHONE_DIAGNOSTIC_CAPTURE = false
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        // Android 4.x uses synchronous USB bulk reads; see openNcm().
        private const val ASYNC_USB_READ_MIN_SDK = Build.VERSION_CODES.N
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val HOSTAPD_START_TIMEOUT_MILLIS = 90_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS = 25_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS = 90_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        /** Issue #205/#211/#217/#219: fast descriptor settle probe before sending vendor re-enumeration request. */
        private const val CARPLAY_MODE_DESCRIPTOR_PROBE_INTERVAL_MILLIS = 200L
        private const val CARPLAY_MODE_DESCRIPTOR_PROBE_TIMEOUT_MILLIS = 800L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        /** Issue #266: sysfs detach-watch cadence while a direct-mode session is live. */
        private const val DIRECT_DETACH_POLL_MILLIS = 2_000L
        private const val NO_IPHONE_DEVICES_LOG_INTERVAL_MILLIS = 10_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val WIRELESS_BOOTSTRAP_RELEASE_QUIET_MILLIS = 2_500L
        private const val WIRELESS_BOOTSTRAP_RELEASE_RECHECK_MILLIS = 1_000L
        private const val WIRELESS_TCP_BT_RELEASE_DELAY_MILLIS = 1_000L
        private const val WIRELESS_SESSION_RECOVERY_INITIAL_MILLIS = 15_000L
        private const val WIRELESS_SESSION_RECOVERY_RECHECK_MILLIS = 5_000L
        private const val WIRELESS_SESSION_RECOVERY_MAX_MILLIS = 90_000L
        /** 无线建连持续观察设备入网的时长（port 自 ding2548-ui/DiPlay@main）。 */
        private const val WIRELESS_CLIENT_WATCH_MILLIS = 120_000L
        private const val WIRELESS_CLIENT_WATCH_POLL_MILLIS = 2_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        /** Issue #266: minimum spacing between direct usbfs bring-up attempts. */
        private const val DIRECT_ATTEMPT_MIN_INTERVAL_NANOS = 10_000_000_000L
        /** Issues #275/#269: persisted "a wired session completed" marker + its store. */
        private const val WIRED_SESSION_PREFS = "wired_session_state"
        private const val WIRELESS_PREFS = "diplay_wireless"
        private const val HOTSPOT_FAMILY_PREFERENCE_KEY = "hotspot_address_family"
        private const val KEY_WIRED_SESSION_COMPLETED = "wired_session_completed"
        /** Issues #275/#269: the one forced re-enumeration latch for this attach episode. */
        @Volatile private var forcedReenumerationForAttach = false
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        /** Issue #281: per-resource bound for teardown closes; a stuck close is abandoned. */
        private const val TEARDOWN_RESOURCE_TIMEOUT_MILLIS = 3_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}






