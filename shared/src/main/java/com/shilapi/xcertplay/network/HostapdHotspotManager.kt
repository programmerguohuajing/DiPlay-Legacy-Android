package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Experimental root hotspot backend (issues #294/#299): for head units where
 * no framework path can open an AP (framework Wi-Fi off, no Wi-Fi Direct,
 * LocalOnlyHotspot unavailable), drive the unit's own hostapd and dnsmasq as
 * root, in the arrangement proven on those units:
 *
 *  1. kill stale hostapd/dnsmasq and stale lease state;
 *  2. force the framework Wi-Fi radio on (`svc wifi enable`, falling back to
 *     the privileged app_process helper when svc leaves the radio off —
 *     issue #301(a)) so wlan0 exists;
 *  3. generate hostapd.conf from the configured SSID/password/channel, copy
 *     it to the standard /data/misc/wifi/hostapd.conf, and start the system's
 *     /system/bin/hostapd;
 *  4. address wlan0 as 192.168.42.1/24 and start the system dnsmasq (DHCP);
 *  5. reset the Bluetooth stack (best effort) — on the target units the
 *     stack wedges once the AP is up, and the RFCOMM bootstrap that follows
 *     hotspot-ready only succeeds after a reset.
 *
 * Everything runs through [RootShell]; when root or a required system binary
 * is missing, [start] fails with the reason instead of half-starting.
 */
class HostapdHotspotManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val channel: Int,
    private val appleVendorIe: Boolean = false,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) : WirelessHotspotManager {
    private val appContext = context.applicationContext

    @Volatile
    private var closed = false
    private var started = false
    private var wifiWasOn = true
    private var confirmedClientCount: Int? = null
    // Issue #313: the hotspot-ready Bluetooth reset can fail (neither root
    // nor adapter.disable works) while the phone only joins later. Remember
    // that so the first observed lease can retry it once. Units whose
    // initial reset worked never see this path.
    private var btResetFailed = false
    private var btResetRetried = false
    // Issue #337: the foreground probe runs once per manager, on the first
    // launch failure only — every retry fails identically on the affected
    // units, and each probe costs ~2s of foreground daemon time.
    private var foregroundProbeDone = false

    /**
     * Set once [start] has changed anything on the head unit (killed stale
     * processes, switched Wi-Fi, written the config). [teardown] undoes system
     * state only when this is set, so closing a manager that never started does
     * not kill the unit's own processes or take wlan0 down.
     */
    @Volatile
    private var touchedSystem = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo = try {
        startHotspot(timeoutMillis)
    } catch (error: Throwable) {
        // A failed or cancelled start must not leave hostapd/dnsmasq running,
        // the passphrase-bearing config on disk or Wi-Fi switched on behind.
        teardown()
        throw error
    }

    private fun startHotspot(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "HostapdHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        HostapdConfig.error(ssid, passphrase, channel)?.let { throw IOException(it) }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        fun remainingMillis(): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        fun checkActive() {
            if (closed || isCancelled()) throw InterruptedIOException("Hostapd hotspot startup cancelled")
            if (System.nanoTime() > deadline) {
                throw IOException("hostapd: startup did not complete within ${timeoutMillis}ms")
            }
        }

        onDiagnostic("hostapd: starting hotspot ssid='$ssid' channel=$channel")
        if (!RootShell.isRootAvailable()) {
            throw IOException(
                "hostapd: root (su) is not available on this head unit; " +
                    "the root hotspot mode needs a rooted unit",
            )
        }
        // Probe for the binaries instead of assuming /system/bin: firmware
        // variants ship them in different directories, and the first field
        // report (issue #301) failed this check on a unit whose binaries
        // exist elsewhere. The raw probe output goes to the diagnostic log
        // either way, so a "missing" verdict carries its own evidence.
        val probe = RootShell.run(HostapdConfig.binaryProbeScript(), 5_000)
        val probeText = probe.output.trim().replace('\n', ' ').take(300)
        onDiagnostic("hostapd: binary probe: $probeText")
        val binaries = HostapdConfig.parseBinaryProbe(probe.output)
        val hostapdPath = binaries["hostapd"]
        val dnsmasqPath = binaries["dnsmasq"]
        if (hostapdPath == null || dnsmasqPath == null) {
            throw IOException(
                "hostapd: hostapd or dnsmasq not found in " +
                    HostapdConfig.BINARY_DIRS.joinToString(" ") + " (probe: $probeText)",
            )
        }

        touchedSystem = true
        RootShell.run(HostapdConfig.killStaleScript(), 10_000)
        checkActive()

        wifiWasOn = readFrameworkWifiOn()
        if (!wifiWasOn) {
            onDiagnostic("hostapd: framework Wi-Fi is off; enabling the radio as root")
            RootShell.run("svc wifi enable", 10_000)
            awaitInterface(deadlineMillis(deadline, 15_000)) { interfaceExists() }
            if (closed || isCancelled()) throw InterruptedIOException("Hostapd hotspot startup cancelled")
            var helperEvidence = ""
            if (!interfaceExists()) {
                // Issue #301(a): on some firmware (Civic 4.2.2) `svc` is a hollow
                // stub — it exits 0 but never powers the radio. Fall back to the
                // privileged app_process helper, which enables the radio through
                // the framework API directly; hostapd still owns the AP itself.
                // The helper only runs after svc already failed, so it cannot
                // change the outcome on units where svc works.
                val helper = WifiRadioHelper.run(appContext)
                helperEvidence = "; helper ok=${helper.ok} (${helper.raw})"
                onDiagnostic("hostapd: svc left ${HostapdConfig.INTERFACE_NAME} absent; helper ok=${helper.ok} (${helper.raw})")
                awaitInterface(deadlineMillis(deadline, 15_000)) { interfaceExists() }
                if (closed || isCancelled()) throw InterruptedIOException("Hostapd hotspot startup cancelled")
            }
            if (!interfaceExists()) {
                val netdevs = RootShell.run("ls /sys/class/net 2>/dev/null", 3_000)
                    .output.trim().replace('\n', ' ').take(200)
                throw IOException(
                    "hostapd: ${HostapdConfig.INTERFACE_NAME} did not appear after enabling Wi-Fi " +
                        "(netdevs: $netdevs$helperEvidence)",
                )
            }
        }

        val conf = HostapdConfig.renderConf(ssid, passphrase, channel, appleVendorIe)
        // Deploy by staging the file in app-private storage and copying it
        // into place as root — the recipe proven on-device (issue #301). The
        // earlier heredoc write depends on the root shell's temp directory,
        // which does not exist on some firmware, and on shell printf quirks;
        // a plain file write plus `cp` avoids both.
        val staged = File(appContext.filesDir, "hostapd_full.conf")
        try {
            staged.writeText(conf)
        } catch (error: Exception) {
            staged.delete() // do not leave a partly written copy of the passphrase behind
            throw IOException("hostapd: hostapd_full.conf deploy failed: could not stage the config: ${error.message}")
        }
        val deployed = try {
            RootShell.run(
                "mkdir -p /data/misc/wifi; cp ${staged.absolutePath} ${HostapdConfig.TMP_CONF_PATH}" +
                    " && cp ${HostapdConfig.TMP_CONF_PATH} ${HostapdConfig.CONF_PATH}",
                10_000,
            )
        } finally {
            // The app-private staging copy holds the WPA passphrase: drop it as
            // soon as it has been handed over (or the hand-over failed).
            staged.delete()
        }
        // The intermediate copy in /data/local/tmp is readable by the adb shell
        // user and only exists for the copy above; hostapd reads CONF_PATH.
        // A separate command keeps the deploy exit status from being masked.
        RootShell.run(HostapdConfig.removeStagedConfScript(), 5_000)
        if (!deployed.success) {
            throw IOException("hostapd: hostapd_full.conf deploy failed: ${deployed.output.trim().take(200)}")
        }
        checkActive()

        // Release any half-up state before hostapd claims the interface
        // (the proven on-device recipe does the same).
        RootShell.run("ifconfig ${HostapdConfig.INTERFACE_NAME} down 2>/dev/null", 5_000)
        // Issue #337: echo the daemon's pid (`echo $!`) — the hostapd
        // invocation itself is byte-identical to the proven recipe, but the
        // pid lets a later failure tell whether THIS hostapd died, was
        // killed, or never started. The shell's exit code is sleep's, so it
        // says nothing about hostapd; the pid is the real witness.
        val launchCmd =
            "$hostapdPath -B ${HostapdConfig.CONF_PATH} >${HostapdConfig.HOSTAPD_LOG_PATH} 2>&1 & echo \$!; sleep 1"
        val launched = RootShell.run(launchCmd, 15_000)
        val launchedPid = HostapdConfig.parseLaunchedPid(launched.output)
        var running = false
        val checkDeadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < checkDeadline) {
            if (hostapdProcessCount() >= 1) {
                running = true
                break
            }
            Thread.sleep(300)
        }
        if (!launched.success || !running) {
            // Issue #337: the old failure line only carried hapd.log, which
            // is empty on the reporter's unit — so "never started", "died
            // instantly", "killed" and "misjudged by ps parsing" were
            // indistinguishable. Gather the evidence that splits them: the
            // launch command, the echoed pid, whether that pid still exists
            // (via /proc, which does not depend on `ps` dialects), the
            // pid's `ps` line, the launch shell's own words, and whether
            // hapd.log is missing, empty, or has content. NOTE: a -B
            // daemon's exit code/signal cannot be recovered after the fact
            // (init reaps it), so pid-alive is the substitute witness — no
            // exit code is claimed here.
            val psLine = launchedPid?.let { pid ->
                HostapdConfig.psLineForPid(RootShell.run("ps", 5_000).output, pid)
            }
            val procCheck = launchedPid?.let { pid ->
                RootShell.run("ls -d /proc/$pid 2>/dev/null", 3_000)
            }
            val pidAlive: Boolean? = when {
                procCheck == null -> null
                procCheck.exitCode == null -> null
                else -> procCheck.success
            }
            val logStat = RootShell.run("ls -l ${HostapdConfig.HOSTAPD_LOG_PATH} 2>/dev/null", 3_000)
            val logBytes = logStat.output.trim().split(Regex("\\s+")).getOrNull(4)?.toLongOrNull()
            // `tail` does not exist on minimal ROMs (issue #308): read the
            // whole log (it is truncated fresh at every launch) and keep its
            // end, where hostapd puts the failure.
            val log = RootShell.run("cat ${HostapdConfig.HOSTAPD_LOG_PATH} 2>/dev/null", 5_000)
            // Issue #337: on the reporter's unit every launch dies within 1s
            // AND hapd.log is missing (not empty) — the daemon never got far
            // enough to write a byte, so the -B evidence cannot say why.
            // Run the same binary once in the foreground and capture its own
            // words and exit code: a config error, a driver error, or an unwritable log
            // directory all print here. Bounded to ~2s, killed afterwards,
            // and the next start's killStale cleans up if the kill misses.
            // Once per manager: later retries fail identically.
            // The probe asks the daemon to write its OWN log file (-f) at
            // debug verbosity (-dd) instead of relying on shell redirection:
            // on the affected Honda units both redirected logs (hapd.log and
            // the earlier probe log) came back empty on every failure while
            // the daemon demonstrably started and died — its messages never
            // reached stdout/stderr there.
            var fgProbe = ""
            if (!foregroundProbeDone) {
                foregroundProbeDone = true
                val probeCmd =
                    "rm -f ${HostapdConfig.FG_PROBE_LOG_PATH}; " +
                    "$hostapdPath -dd -f ${HostapdConfig.FG_PROBE_LOG_PATH} ${HostapdConfig.CONF_PATH} & FG=\$!; sleep 2; " +
                        "if kill -0 \$FG 2>/dev/null; then echo FG_ALIVE; kill \$FG; sleep 1; else wait \$FG 2>/dev/null; echo FG_DEAD FG_EXIT=\$?; fi; " +
                        "ls -ld /data/local/tmp 2>/dev/null; cat ${HostapdConfig.FG_PROBE_LOG_PATH} 2>/dev/null"
                val probeResult = runCatching { RootShell.run(probeCmd, 10_000) }.getOrNull()
                fgProbe = probeResult?.output?.trim()?.replace('\n', ' ')?.take(600) ?: "unavailable"
            }
            val evidence = buildString {
                append("cmd=").append(launchCmd)
                append("; pid=").append(launchedPid ?: "unknown")
                append("; pidAlive=").append(pidAlive?.toString() ?: "unknown")
                append("; psLine=").append(psLine?.take(160) ?: if (launchedPid == null) "unknown" else "none")
                append("; launchOk=").append(launched.success)
                append("; launchExit=").append(launched.exitCode ?: "none")
                append("; launchOut=").append(launched.output.trim().replace('\n', ' ').take(200))
                append("; hapdBytes=").append(logBytes ?: "missing")
                append("; hapd.log=").append(log.output.trim().replace('\n', ' ').takeLast(300))
                if (fgProbe.isNotEmpty()) append("; fgProbe=").append(fgProbe)
            }
            onDiagnostic("hostapd: launch failed evidence: $evidence")
            throw IOException("hostapd: hostapd did not stay up; $evidence")
        }
        onDiagnostic("hostapd: hostapd is up on ${HostapdConfig.INTERFACE_NAME} pid=${launchedPid ?: "unknown"}")

        var addressed = false
        var lastSetOutput = ""
        repeat(3) {
            val setResult = RootShell.run(
                "ifconfig ${HostapdConfig.INTERFACE_NAME} ${HostapdConfig.HOST_ADDRESS} " +
                    "netmask ${HostapdConfig.NETMASK} up",
                10_000,
            )
            lastSetOutput = setResult.output.trim().replace('\n', ' ').take(200)
            if (interfaceHasHostAddress()) {
                addressed = true
                return@repeat
            }
            Thread.sleep(500)
            checkActive()
        }
        if (!addressed) {
            // Keep the set command's own words in the error (issue #308):
            // without them the failure is indistinguishable from a probe
            // misread on the next firmware whose `ifconfig` dialect differs.
            val probe = RootShell.run(
                "ifconfig ${HostapdConfig.INTERFACE_NAME} 2>/dev/null", 5_000,
            ).output.trim().replace('\n', ' ').take(200)
            throw IOException(
                "hostapd: ${HostapdConfig.HOST_ADDRESS} is not assignable on " +
                    "${HostapdConfig.INTERFACE_NAME}; ifconfig set said: $lastSetOutput; probe: $probe",
            )
        }

        RootShell.run(HostapdConfig.dnsmasqCommand(dnsmasqPath), 10_000)
        started = true
        onDiagnostic(
            "hostapd: hotspot ready iface=${HostapdConfig.INTERFACE_NAME} addr=${HostapdConfig.HOST_ADDRESS}",
        )
        BluetoothStackReset.reset(
            tag = "hostapd",
            isCancelled = { closed || isCancelled() },
            onDiagnostic = { msg ->
                if (msg.contains("bluetooth reset unavailable")) btResetFailed = true
                onDiagnostic(msg)
            },
        )

        val hostAddress = InetAddress.getByName(HostapdConfig.HOST_ADDRESS)
        val mac = runCatching {
            NetworkInterface.getByName(HostapdConfig.INTERFACE_NAME)?.hardwareAddress
                ?.joinToString(":") { "%02x".format(it) }
        }.getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = channel,
            frequencyMHz = wifiChannelToFrequencyMhz(channel, band = 1),
            bssid = mac,
            interfaceName = HostapdConfig.INTERFACE_NAME,
            hostAddress = hostAddress,
            hostAddresses = listOf(hostAddress),
            bandLabel = "2.4 GHz",
            backend = WirelessHotspotBackend.HOSTAPD,
        )
    }

    override fun validateReady() {
        if (!started || closed) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready")
        }
        if (hostapdProcessCount() < 1 || !interfaceHasHostAddress()) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "hostapd or the ${HostapdConfig.INTERFACE_NAME} address went away before publication",
            )
        }
    }

    override fun joinedClientCount(): Int? {
        if (!started || closed) return confirmedClientCount
        val previous = confirmedClientCount
        val leases = RootShell.run("cat ${HostapdConfig.LEASES_PATH} 2>/dev/null", 5_000)
        if (!leases.success && leases.output.isBlank()) return confirmedClientCount
        val count = HostapdConfig.activeLeases(leases.output, System.currentTimeMillis() / 1_000).size
        confirmedClientCount = count
        // Issue #313: the phone joining (leases 0/null -> positive) is the
        // first moment a retry can help — the initial reset failed before
        // the phone was even on the network. One shot only, so a healthy
        // unit never gets a second Bluetooth flap mid-connect.
        if (!btResetRetried && btResetFailed && (previous == null || previous == 0) && count > 0) {
            btResetRetried = true
            onDiagnostic("hostapd: phone joined after a failed bluetooth reset; retrying the reset once")
            BluetoothStackReset.reset(
                tag = "hostapd-lease",
                isCancelled = { closed || isCancelled() },
                onDiagnostic = onDiagnostic,
            )
        }
        return count
    }

    override fun connectionDiagnosticSnapshot(): String {
        val count = joinedClientCount()
        return "hostapd leases=${count ?: "unknown"} iface=${HostapdConfig.INTERFACE_NAME}"
    }

    override fun close() {
        if (closed) return
        closed = true
        teardown()
    }

    /**
     * Stops what [start] began and removes what it wrote: hostapd/dnsmasq, the
     * generated configuration (it carries the WPA passphrase), logs and lease
     * state, and Wi-Fi again if this backend had to switch it on. Safe to call
     * more than once, and a no-op when [start] never changed anything.
     */
    private fun teardown() {
        if (!touchedSystem) return
        touchedSystem = false
        runCatching {
            RootShell.run(
                HostapdConfig.killStaleScript() + "; ifconfig ${HostapdConfig.INTERFACE_NAME} down 2>/dev/null" +
                    "; " + HostapdConfig.removeGeneratedFilesScript(),
                10_000,
            )
            if (!wifiWasOn) {
                RootShell.run("svc wifi disable", 10_000)
            }
        }
        started = false
    }

    private fun deadlineMillis(deadline: Long, capMillis: Long): Long =
        minOf(capMillis, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))

    private fun interfaceExists(): Boolean =
        RootShell.run("ls -d /sys/class/net/${HostapdConfig.INTERFACE_NAME} 2>/dev/null", 3_000)
            .output.contains(HostapdConfig.INTERFACE_NAME)

    /**
     * Live hostapd processes. Counted here instead of with `wc -l` or `grep` in the
     * shell: minimal ROMs (e.g. Android 4.2 toolbox) ship neither `wc`, `tail` nor `grep`,
     * and a shell pipeline silently fails on those units (issues #308, #337).
     */
    private fun hostapdProcessCount(): Int {
        val result = RootShell.run("ps", 5_000)
        return HostapdConfig.countHostapdProcesses(result.output)
    }

    /** True when the AP address is on the interface; parsed here, not with a shell pipeline. */
    private fun interfaceHasHostAddress(): Boolean {
        val ifconfig = RootShell.run(
            "ifconfig ${HostapdConfig.INTERFACE_NAME} 2>/dev/null", 5_000,
        )
        if (HostapdConfig.outputShowsHostAddress(ifconfig.output)) return true
        // Some firmware lacks a usable `ifconfig` listing; `ip addr` prints
        // the same inet line in the modern dialect the matcher accepts.
        val ipAddr = RootShell.run(
            "ip addr show ${HostapdConfig.INTERFACE_NAME} 2>/dev/null", 5_000,
        )
        return HostapdConfig.outputShowsHostAddress(ipAddr.output)
    }

    private fun awaitInterface(timeoutMillis: Long, probe: () -> Boolean) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < end) {
            if (closed || isCancelled()) return
            if (probe()) return
            Thread.sleep(500)
        }
    }

    private fun readFrameworkWifiOn(): Boolean {
        val result = RootShell.run("settings get global wifi_on", 5_000)
        return result.output.trim().lines().lastOrNull()?.trim() == "1"
    }

    /**
     * Best effort, mirroring the modified builds on the target units: once
     * the AP is up their Bluetooth stack no longer answers, and the RFCOMM
     * bootstrap that follows hotspot-ready needs a fresh stack. Any failure
     * here is logged and the connection attempt continues.
     */
}
