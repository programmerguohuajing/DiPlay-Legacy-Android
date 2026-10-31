package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    /** 0 uses usage-based routing; 1–20 select stream types supported by the head unit. */
    val AUDIO_CHANNELS = 0..20
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_AUDIO_FOCUS_AUTO_YIELD = "audio_focus_auto_yield"
    private const val KEY_MEDIA_FOCUS_EXCLUSIVE = "media_focus_exclusive"
    private const val KEY_LEGACY_REVERSE_COLD_START = "legacy_reverse_cold_start"
    private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
    private const val KEY_MEDIA_VOLUME_PERCENT = "media_volume_percent"
    private const val KEY_NAVIGATION_VOLUME_PERCENT = "navigation_volume_percent"
    /** Issues #253/#263: selectable per-stream volume steps, in percent. */
    val STREAM_VOLUME_PERCENTS = listOf(25, 50, 75, 100, 125, 150)
    private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    /** Issue #290: consecutive wireless link-establishment failures (iPhone never opens AirPlay TCP). */
    private const val KEY_WIRELESS_LINK_FAILURES = "wireless_link_failures"
    /** Issue #290: whether the forget-and-repair prompt was already shown for the current streak. */
    private const val KEY_WIRELESS_REPAIR_PROMPT_SHOWN = "wireless_repair_prompt_shown"
    private const val KEY_USB_BT_AUTO_START = "usb_bt_auto_start"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_WIFI_P2P_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_ROOT_HOTSPOT_ENABLED = "root_hotspot_enabled"
    private const val KEY_HOSTAPD_SSID = "hostapd_ssid"
    private const val KEY_HOSTAPD_PASSPHRASE = "hostapd_passphrase"
    private const val KEY_HOSTAPD_CHANNEL = "hostapd_channel"
    private const val KEY_HOSTAPD_APPLE_VENDOR_IE = "hostapd_apple_vendor_ie"
    private const val KEY_CARPLAY_AUTO_DISCONNECT_BT = "carplay_auto_disconnect_bt"
    private const val KEY_TELEPHONY_DOWNLINK_VIA_MUSIC = "telephony_downlink_via_music"
    private const val KEY_ANNOUNCEMENT_DUCK_MEDIA = "announcement_duck_media"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_CARPLAY_NIGHT_MODE = "carplay_night_mode"
    private const val KEY_CARPLAY_NIGHT_START = "carplay_night_start_minute"
    private const val KEY_CARPLAY_NIGHT_END = "carplay_night_end_minute"
    private const val KEY_AMBIENT_LUX_THRESHOLD = "ambient_lux_threshold"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_MAIN_BUFFERED_AUDIO = "main_buffered_audio"
    private const val KEY_CALL_ECHO_CANCELLATION = "call_echo_cancellation"
    private const val KEY_CALL_VOICE_FILTER = "call_voice_filter"
    private const val KEY_CALL_MIC_COMPATIBILITY = "call_mic_compatibility"
    private const val KEY_SMOOTH_VIDEO = "smooth_video"
    private const val KEY_CAR_BLUETOOTH_AUDIO = "car_bluetooth_audio"
    private const val KEY_DIRECT_SURFACE_VIEW = "direct_surface_view"
    private const val KEY_DIRECT_USBFS_MODE = "direct_usbfs_mode"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_ADB_CLUSTER_ACTIVITY = "adb_cluster_activity_enabled"
    private const val KEY_CENTER_MAP_OVERLAY = "center_map_overlay"
    private const val KEY_FLOATING_DOT = "floating_dot"
    private const val KEY_CENTER_MAP_AUTO_HIDE = "center_map_auto_hide"
    private const val KEY_LAUNCHER_MAP_SHARING = "launcher_map_sharing"
    private const val KEY_CLUSTER_MAP_SCALE = "cluster_map_scale_percent"
    private const val KEY_CLUSTER_CONTENT = "cluster_content"
    private const val KEY_CLUSTER_MARKER_X = "cluster_marker_horizontal_step"
    private const val KEY_CLUSTER_MARKER_Y = "cluster_marker_vertical_step"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION = "cluster_turn_card_overlay_position"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE = "cluster_turn_card_overlay_size"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_X = "cluster_turn_card_overlay_x_percent"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_Y = "cluster_turn_card_overlay_y_percent"
    private const val KEY_CENTER_MAP_FOLLOWS_DASHBOARD = "center_map_follows_dashboard"
    private const val KEY_SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_DOCK_AUTO_HIDE = "dock_auto_hide"
    private const val KEY_FORCE_SQUARE_CORNERS = "force_square_corners"
    private const val KEY_HARMAN_FULLSCREEN = "harman_fullscreen"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_ADAPT_PIP_RESOLUTION = "adapt_pip_resolution"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val KEY_CUSTOM_BLUETOOTH_MAC = "custom_bluetooth_mac"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_OEM_LABEL = "BYD"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadAmbientDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("ambient_delay_seconds", 2).coerceIn(0, 60)

    fun saveAmbientDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ambient_delay_seconds", seconds.coerceIn(0, 60)).apply()
    }

    fun loadDisplayScalePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("display_scale_percent", loadDisplayScaleTenths(context) * 10)
            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)

    fun saveDisplayScalePercent(context: Context, percent: Int) {
        // Committed rather than applied: saving the settings menu reconnects CarPlay at once,
        // and on the legacy ROMs that tear the process down during that reconnect an
        // asynchronous apply() was lost, so the driver's resolution was gone on next start.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                "display_scale_percent",
                percent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT),
            ).commit()
    }
    /** Applied by the CarPlay host so overlay position/size updates without reconnecting. */
    @Volatile var overlaySettingsListener: (() -> Unit)? = null

    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT = "cluster_turn_card_overlay_size_percent"
    private const val KEY_CLUSTER_TURN_CARD_OPACITY = "cluster_turn_card_opacity_percent"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int {
        val defaultStream = if (CarHotspotSetup.isBydHeadUnit(context)) 14 else android.media.AudioManager.STREAM_MUSIC
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_STREAM_TYPE, defaultStream)
        return if (!CarHotspotSetup.isBydHeadUnit(context) && saved == 14) {
            android.media.AudioManager.STREAM_MUSIC
        } else {
            saved
        }
    }

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    fun loadCustomBluetoothMac(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_BLUETOOTH_MAC, null)?.takeIf { it.isNotBlank() }

    fun saveCustomBluetoothMac(context: Context, mac: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (mac.isNullOrBlank()) {
            editor.remove(KEY_CUSTOM_BLUETOOTH_MAC)
        } else {
            editor.putString(KEY_CUSTOM_BLUETOOTH_MAC, mac.trim())
        }
        editor.apply()
    }

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, false)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, true)

    /** Issue #315: legacy-chip reverse cold start; off by default, takes effect on the next reverse. */
    fun loadLegacyReverseColdStart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_REVERSE_COLD_START, false)

    fun saveLegacyReverseColdStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LEGACY_REVERSE_COLD_START, enabled)
            .apply()
    }

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, enabled)
            .apply()
    }

    /** Issue #290: media focus exclusive mode; off by default, takes effect on the next connection. */
    fun loadMediaFocusExclusive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_FOCUS_EXCLUSIVE, false)

    fun saveMediaFocusExclusive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MEDIA_FOCUS_EXCLUSIVE, enabled)
            .apply()
    }

    /** Issue #290: consecutive wireless link-establishment failures; 0 when no streak is active. */
    fun loadWirelessLinkFailures(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIRELESS_LINK_FAILURES, 0)

    fun saveWirelessLinkFailures(context: Context, failures: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIRELESS_LINK_FAILURES, failures)
            .apply()
    }

    /** Issue #290: whether the forget-and-repair prompt was already shown for the current streak. */
    fun loadWirelessRepairPromptShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_REPAIR_PROMPT_SHOWN, false)

    fun saveWirelessRepairPromptShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_REPAIR_PROMPT_SHOWN, shown)
            .apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, 0)
            .takeIf { it in AUDIO_CHANNELS } ?: 0

    // Issues #253/#263: per-stream volume. Head units that merge every stream into
    // one system volume leave drivers unable to balance navigation prompts against
    // music; the renderer scales each stream's PCM by these percents instead.
    fun loadMediaVolumePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_VOLUME_PERCENT, 100)
            .takeIf { it in STREAM_VOLUME_PERCENTS } ?: 100

    fun saveMediaVolumePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_VOLUME_PERCENT, percent.takeIf { it in STREAM_VOLUME_PERCENTS } ?: 100)
            .apply()
    }

    fun loadNavigationVolumePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_VOLUME_PERCENT, 100)
            .takeIf { it in STREAM_VOLUME_PERCENTS } ?: 100

    fun saveNavigationVolumePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_VOLUME_PERCENT, percent.takeIf { it in STREAM_VOLUME_PERCENTS } ?: 100)
            .apply()
    }

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Inherit the legacy value only when the new key is absent; preserve fresh-install and explicit 0 defaults.
        return prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, prefs.getInt(KEY_NAVIGATION_STREAM_TYPE, 0))
            .takeIf { it in AUDIO_CHANNELS } ?: 0
    }

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    /**
     * Issue #281: whether the user has started the USB Bluetooth dongle. The settings
     * card's start/stop used to be runtime-only, so after every process restart the
     * dongle looked "switched off" even though the user had turned it on.
     */
    /**
     * Issue #281 & #321: whether the user has started the USB Bluetooth dongle.
     * Defaults to true so an attached dongle is automatically loaded on app launch
     * without forcing manual navigation to settings.
     */
    fun loadUsbBtAutoStart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USB_BT_AUTO_START, true)

    fun saveUsbBtAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_USB_BT_AUTO_START, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        // Keep the user's Wi-Fi Direct selection on Android 8/9. The runtime
        // controller uses a system-generated P2P group, with LocalOnlyHotspot as a fallback,
        // because the API 29 configurable group APIs are unavailable. Converting it to MANUAL made
        // the UI appear to ignore the Wi-Fi option entirely.
        // The experimental root hostapd mode is only selectable while its
        // Advanced opt-in is on; a stored HOSTAPD value with the opt-in off
        // resolves to MANUAL, the same treatment as LOCAL_ONLY_HOTSPOT.
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) {
            WirelessHotspotMode.MANUAL
        } else if (mode == WirelessHotspotMode.HOSTAPD && !loadRootHotspotEnabled(context)) {
            WirelessHotspotMode.MANUAL
        } else {
            mode
        }
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    fun loadWifiP2pPreferredChannel(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
            .takeIf(WifiP2pChannels::isValid) ?: WifiP2pChannels.AUTO
    }.getOrDefault(WifiP2pChannels.AUTO)

    fun saveWifiP2pPreferredChannel(context: Context, channel: Int) {
        require(WifiP2pChannels.isValid(channel))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, channel).apply()
    }

    fun loadExistingWifiSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_ssid", "").orEmpty()

    fun loadExistingWifiPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_passphrase", "").orEmpty()

    fun saveExistingWifiCredentials(context: Context, ssid: String, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("existing_wifi_ssid", ssid)
            .putString("existing_wifi_passphrase", passphrase).apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    /**
     * Session log output switch (issue #186). Defaults to true so existing behavior is
     * unchanged: the key existed before but was never read. [LoggingGate] caches it per
     * process, so toggling takes effect after restarting the app.
     */
    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, true)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadAmbientLightThreshold(context: Context): AmbientLightThreshold = AmbientLightThreshold.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AMBIENT_LUX_THRESHOLD, AmbientLightThreshold.DEFAULT_LUX),
    )

    fun saveAmbientLightThreshold(context: Context, threshold: AmbientLightThreshold) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AMBIENT_LUX_THRESHOLD, threshold.lux).apply()
    }

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode = CarPlayNightMode.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CARPLAY_NIGHT_MODE, null),
    )

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadCarPlayNightSchedule(context: Context): CarPlayNightSchedule {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = CarPlayNightSchedule()
        val start = preferences.getInt(KEY_CARPLAY_NIGHT_START, defaults.startMinute)
        val end = preferences.getInt(KEY_CARPLAY_NIGHT_END, defaults.endMinute)
        return CarPlayNightSchedule(
            start.takeIf { it in 0 until 24 * 60 } ?: defaults.startMinute,
            end.takeIf { it in 0 until 24 * 60 } ?: defaults.endMinute,
        )
    }

    fun saveCarPlayNightSchedule(context: Context, schedule: CarPlayNightSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CARPLAY_NIGHT_START, schedule.startMinute)
            .putInt(KEY_CARPLAY_NIGHT_END, schedule.endMinute)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    /** CarPlay's buffered music (Apple Music sends ahead over TCP); off by default, applies at reconnect. */
    fun loadMainBufferedAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAIN_BUFFERED_AUDIO, false)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    /** Offer no CarPlay audio, so the iPhone keeps audio on its Bluetooth link with the car; applies at reconnect. */
    fun loadCarBluetoothAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAR_BLUETOOTH_AUDIO, false)

    fun saveCarBluetoothAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAR_BLUETOOTH_AUDIO, enabled).apply()
    }

    fun loadSmoothVideo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMOOTH_VIDEO, false)

    fun saveSmoothVideo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMOOTH_VIDEO, enabled).apply()
    }

    /**
     * Direct SurfaceView hardware rendering: defaults to true on older/low-end Android (API <= 23)
     * to eliminate TextureView GPU composition bottlenecks on chips like MT3561 and Allwinner T3.
     */
    fun loadDirectSurfaceView(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultMode = realSdkInt() <= android.os.Build.VERSION_CODES.M
        return prefs.getBoolean(KEY_DIRECT_SURFACE_VIEW, defaultMode)
    }

    fun saveDirectSurfaceView(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRECT_SURFACE_VIEW, enabled).apply()
    }

    /** Issue #266: direct usbfs mode for ROMs whose UsbManager never lists the iPhone. */
    fun loadDirectUsbfsMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DIRECT_USBFS_MODE, false)

    fun saveDirectUsbfsMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRECT_USBFS_MODE, enabled).apply()
    }

    /** Issues #294/#299: experimental root hostapd hotspot opt-in (Advanced). */
    fun loadRootHotspotEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROOT_HOTSPOT_ENABLED, false)

    fun saveRootHotspotEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROOT_HOTSPOT_ENABLED, enabled).apply()
    }

    /** Issue #344: Apple vendor IE on the root hostapd beacon (Advanced, experimental). */
    fun loadHostapdAppleVendorIe(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOSTAPD_APPLE_VENDOR_IE, false)

    fun saveHostapdAppleVendorIe(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOSTAPD_APPLE_VENDOR_IE, enabled).apply()
    }

    /** Issue #343: auto-disconnect phone Bluetooth after wireless CarPlay connects (Advanced, experimental). */
    fun loadCarplayAutoDisconnectBt(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CARPLAY_AUTO_DISCONNECT_BT, false)

    fun saveCarplayAutoDisconnectBt(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CARPLAY_AUTO_DISCONNECT_BT, enabled).apply()
    }

    /** Issue #343-Q1: route the call downlink through the music stream (Advanced, experimental). */
    fun loadTelephonyDownlinkViaMusic(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TELEPHONY_DOWNLINK_VIA_MUSIC, false)

    fun saveTelephonyDownlinkViaMusic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TELEPHONY_DOWNLINK_VIA_MUSIC, enabled).apply()
    }

    /** Issue #364: duck media while announcement audio flows (Advanced, experimental). */
    fun loadAnnouncementDuckMedia(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ANNOUNCEMENT_DUCK_MEDIA, false)

    fun saveAnnouncementDuckMedia(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANNOUNCEMENT_DUCK_MEDIA, enabled).apply()
    }

    fun loadHostapdSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSTAPD_SSID, null)?.takeIf { it.isNotBlank() } ?: "DiPlay"

    fun saveHostapdSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOSTAPD_SSID, ssid)
            .apply()
    }

    fun loadHostapdPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSTAPD_PASSPHRASE, null)
            .orEmpty()

    fun saveHostapdPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOSTAPD_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadHostapdChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HOSTAPD_CHANNEL, com.shilapi.xcertplay.network.HostapdConfig.DEFAULT_CHANNEL)
            .takeIf { it in com.shilapi.xcertplay.network.HostapdConfig.MIN_CHANNEL..com.shilapi.xcertplay.network.HostapdConfig.MAX_CHANNEL }
            ?: com.shilapi.xcertplay.network.HostapdConfig.DEFAULT_CHANNEL

    fun saveHostapdChannel(context: Context, channel: Int) {
        require(channel in com.shilapi.xcertplay.network.HostapdConfig.MIN_CHANNEL..com.shilapi.xcertplay.network.HostapdConfig.MAX_CHANNEL)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HOSTAPD_CHANNEL, channel).apply()
    }

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    /** DiPlay's own experimental echo canceller on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallEchoCancellation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_ECHO_CANCELLATION, false)

    fun saveCallEchoCancellation(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_ECHO_CANCELLATION, enabled).apply()
    }

    /** Experimental bass cut on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallVoiceFilter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_VOICE_FILTER, false)

    fun saveCallVoiceFilter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_VOICE_FILTER, enabled).apply()
    }

    fun isAllwinnerHeadUnit(): Boolean {
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.US)
        val board = Build.BOARD.lowercase(java.util.Locale.US)
        val brand = Build.BRAND.lowercase(java.util.Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.US)
        return hardware.contains("sun8i") || hardware.contains("allwinner") ||
            board.contains("exdroid") || brand.contains("allwinner") || manufacturer.contains("allwinner")
    }

    /** Compatibility microphone mode for calls on Allwinner / custom ROM head units; applies at reconnect. */
    fun loadCallMicCompatibility(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CALL_MIC_COMPATIBILITY)) {
            return prefs.getBoolean(KEY_CALL_MIC_COMPATIBILITY, false)
        }
        return isAllwinnerHeadUnit()
    }

    fun saveCallMicCompatibility(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_MIC_COMPATIBILITY, enabled).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)

    fun loadAdbClusterEnabled(context: Context): Boolean = loadClusterMapEnabled(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADB_CLUSTER_ACTIVITY, false)

    fun saveAdbClusterEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADB_CLUSTER_ACTIVITY, enabled)
        if (enabled) edit.putBoolean(KEY_CLUSTER_MAP, true)
        edit.apply()
    }

    fun saveClusterMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MAP, enabled).apply()
    }

    /** The dashboard map as a card on the centre screen while DiPlay is in the background. */
    fun loadCenterMapOverlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_OVERLAY, false)

    /** Automatically hide the floating card when non-launcher apps are in the foreground. */
    fun loadCenterMapAutoHide(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_AUTO_HIDE, true)

    fun saveCenterMapAutoHide(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_AUTO_HIDE, enabled).apply()
    }

    /** Other launchers may show the live dashboard map in their own screen (MapEmbedService). */
    fun loadLauncherMapSharing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAUNCHER_MAP_SHARING, false)

    fun saveLauncherMapSharing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAUNCHER_MAP_SHARING, enabled).apply()
    }

    /** Observe consent changes for already attached launcher maps; call the returned function to unregister. */
    internal fun observeLauncherMapSharing(context: Context, changed: (Boolean) -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAUNCHER_MAP_SHARING) changed(loadLauncherMapSharing(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCenterMapOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_OVERLAY, enabled).apply()
    }

    fun loadFloatingDot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOATING_DOT, false)

    fun saveFloatingDot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_DOT, enabled).apply()
    }

    /** Whether to renegotiate resolution when entering/exiting freeform floating windows or launcher PiP. */
    fun loadAdaptPipResolution(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADAPT_PIP_RESOLUTION, false)

    fun saveAdaptPipResolution(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADAPT_PIP_RESOLUTION, enabled).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: if (AdbClusterRouter.enabled(context)) CarPlayClusterDisplay.Content.INSTRUMENTS else CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
        overlaySettingsListener?.invoke()
    }

    /** Fingers for the swipe-down that opens settings; some head units reserve three. */
    fun loadSettingsGestureFingers(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_GESTURE_FINGERS, 3).coerceIn(2, 4)

    fun saveSettingsGestureFingers(context: Context, fingers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_GESTURE_FINGERS, fingers.coerceIn(2, 4)).apply()
    }

    fun loadCenterMapFollowsDashboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, true)

    fun saveCenterMapFollowsDashboard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, enabled).apply()
    }

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT),
                ClusterTurnCardOverlay.sizePercents,
            )
        }
        return when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)) {
            "SMALL" -> 40
            "LARGE" -> 70
            else -> ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        }
    }

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)
            .coerceIn(20, 100)

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X)) {
            prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_X, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
        } else when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION, null)) {
            "LEFT" -> 20
            "CENTER" -> 50
            else -> ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        }
        return ClusterTurnCardOverlay.snap(raw, ClusterTurnCardOverlay.xPercents)
    }

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_X,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.xPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_Y, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT),
                ClusterTurnCardOverlay.yPercents,
            )
        }
        return ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    }

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.yPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    // Cluster mapping has its own key; never reuse the main display mapping at the same resolution.
    fun loadClusterSafeAreaRect(context: Context): SafeAreaRect? =
        SafeAreaCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cluster_safe_area_1920x720", null))?.clampTo(1920, 720)

    fun saveClusterSafeAreaRect(context: Context, rect: SafeAreaRect) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cluster_safe_area_1920x720", SafeAreaCodec.encode(rect.clampTo(1920, 720))).apply()
    }

    fun clearClusterSafeAreaRect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cluster_safe_area_1920x720").apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadHarmanFullscreen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HARMAN_FULLSCREEN, false)

    fun saveHarmanFullscreen(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HARMAN_FULLSCREEN, enabled)
            .apply()
    }

    fun loadDockAutoHide(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOCK_AUTO_HIDE, false)

    fun saveDockAutoHide(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DOCK_AUTO_HIDE, enabled)
            .apply()
    }

    fun loadForceSquareCorners(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_SQUARE_CORNERS, false)

    fun saveForceSquareCorners(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FORCE_SQUARE_CORNERS, enabled)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun loadCustomAirPlayIconBytes(context: Context): ByteArray? = try {
        loadCustomAirPlayIconFile(context)?.readBytes()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0 && iconExceedsLimits(raw.size, bounds.outWidth, bounds.outHeight)) {
                val normalized = normalizeCustomAirPlayIconBytes(raw)
                runCatching {
                    loadCustomAirPlayIconFile(context)?.writeBytes(normalized)
                }
                normalized
            } else {
                raw
            }
        }
    } catch (_: Throwable) {
        null
    }

    fun loadCustomAirPlayIconBitmap(context: Context): Bitmap? = try {
        loadCustomAirPlayIconBytes(context)?.let { bytes ->
            // Sampled decode for the settings preview: the saved pick can be
            // a 4096x4096 photo whose full decode (~64MB) fails on low-memory
            // head units and made the preview fall back to the default icon.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= 512 &&
                bounds.outHeight / (sampleSize * 2) >= 512
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * The stored-icon size limits. The icon bytes travel inline in the
     * session's /info payload, so an oversized custom image breaks session
     * setup on the phone side (issue #263); the built-in brand presets are
     * 192x192 and a few KB. Custom uploads are normalized to these limits
     * when saved — at most [ICON_MAX_EDGE] px on the long edge and at most
     * [ICON_MAX_BYTES] encoded — so what is stored is already compliant.
     */
    const val ICON_MAX_EDGE = 192
    const val ICON_MAX_BYTES = 32_000

    /** Progressive downscale targets for oversized icons, largest first. */
    fun iconScaleLadder(): List<Int> = listOf(192, 160, 128, 96, 64)

    /** True when an encoded icon of [byteCount] bytes and [width]x[height] exceeds the limits. */
    fun iconExceedsLimits(byteCount: Int, width: Int, height: Int): Boolean =
        byteCount > ICON_MAX_BYTES || width > ICON_MAX_EDGE || height > ICON_MAX_EDGE

    /**
     * Shrinks an oversized icon into the stored limits; returns the input
     * unchanged when it already complies or cannot be decoded (the load
     * path then falls back to the default icon, as before). The decode is
     * sampled: a full-size decode of a 4096x4096 pick needs ~64MB and
     * fails on low-memory head units, which must not skip the shrink.
     */
    fun normalizeCustomAirPlayIconBytes(encoded: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return encoded
        if (!iconExceedsLimits(encoded.size, bounds.outWidth, bounds.outHeight)) return encoded
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= 1024 &&
            bounds.outHeight / (sampleSize * 2) >= 1024
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return encoded
        var result = encoded
        var current: Bitmap? = null
        for (target in iconScaleLadder()) {
            val source = current ?: decoded
            val longEdge = max(source.width, source.height)
            val scaled = if (longEdge > target) {
                val ratio = target.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * ratio).roundToInt().coerceAtLeast(1),
                    (source.height * ratio).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                source
            }
            val output = ByteArrayOutputStream()
            val encodedOk = scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
            if (scaled !== source) {
                if (current != null && source === current) current.recycle()
                current = scaled
            }
            if (!encodedOk) break
            result = output.toByteArray()
            if (result.size <= ICON_MAX_BYTES &&
                max(scaled.width, scaled.height) <= ICON_MAX_EDGE
            ) {
                break
            }
        }
        if (current != null && current !== decoded) current.recycle()
        decoded.recycle()
        Log.i(
            "AirPlayPersistence",
            "Custom AirPlay icon payload ${encoded.size} -> ${result.size} bytes " +
                "(limits ${ICON_MAX_EDGE}px / $ICON_MAX_BYTES bytes)",
        )
        return result
    }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        // Enforce the stored-icon size limits at save time, so every entry
        // point (brand presets, the crop flow, a restored baseline) stores
        // a compliant icon instead of relying on the load path to fix it up.
        val normalized = normalizeCustomAirPlayIconBytes(encodedImage)
        val file = File(context.filesDir, CUSTOM_ICON_FILE)
        FileOutputStream(file).use { output ->
            output.write(normalized)
            output.flush()
            output.fd.sync()
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val restored = runCatching {
            val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
            val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
            val pairingId = prefs.getString(KEY_PAIRING_ID, null)
            if (privateKey == null || publicKey == null || pairingId.isNullOrBlank()) {
                null
            } else {
                val privateBytes = privateKey.decodeHex()
                val publicBytes = publicKey.decodeHex()
                require(privateBytes.size == 32) { "AirPlay private key must be 32 bytes" }
                require(publicBytes.size == 32) { "AirPlay public key must be 32 bytes" }
                AirPlayIdentity(privateBytes, publicBytes, pairingId)
            }
        }.getOrNull()
        if (restored != null) return restored

        // A damaged/old identity makes every saved controller pairing invalid as well.
        val identifiers = runCatching {
            prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toSet()
        }.getOrDefault(emptySet())
        val editor = prefs.edit()
            .remove(KEY_IDENT_PRIVATE)
            .remove(KEY_IDENT_PUBLIC)
            .remove(KEY_PAIRING_ID)
            .remove(KEY_PAIRING_IDS)
        identifiers.forEach { editor.remove("pairing.$it") }

        return AirPlayIdentity.generate().also { identity ->
            editor
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        val identifiers = runCatching {
            prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toSet()
        }.getOrDefault(emptySet())
        val invalid = mutableSetOf<String>()
        for (identifier in identifiers) {
            val key = runCatching {
                prefs.getString("pairing.$identifier", null)?.decodeHex()
            }.getOrNull()
            if (key == null || key.size != 32) {
                invalid += identifier
            } else {
                store.save(identifier, key)
            }
        }
        if (invalid.isNotEmpty()) {
            val retained = identifiers - invalid
            val editor = prefs.edit().putStringSet(KEY_PAIRING_IDS, retained)
            invalid.forEach { editor.remove("pairing.$it") }
            editor.apply()
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
