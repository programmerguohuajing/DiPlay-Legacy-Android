# DiPlay 0.3.11 — 2026-11-01

- Do not rebuild picture on unconfirmed reverse exit (#315, #363): add a 600ms focus-confirmation delay and cancellation on re-engagement, preventing flapped CAN broadcasts from displacing the camera view mid-manoeuvre.
- Duck media while announcement audio flows (Advanced, experimental) (#364): duck media renderer gain when PCM frames write to navigation, assistant, or phone streams, restoring ~1.5s after speech ends.
- Re-claim NCM data interface when bulk-OUT wedges at zero completions (#75): release and force-reclaim data interface and alternate setting at failure 12, reviving wedged endpoint transfers without a full bus reset.
- One MainAudio Untake per session; dedup concurrent USB permission requests (#305, #293): send at most one Untake per wired session to prevent picture freeze, and de-duplicate concurrent permission requests within 12ms.
- Keep failure teardown off interface and radio (#330, #348): avoid taking down wlan0 or disabling Wi-Fi radio on failed hostapd startup, preventing driver lockups on affected Honda head units.

See [v0.3.11 release notes](docs/RELEASE-NOTES-0.3.11.md) for details.

# DiPlay 0.3.10 — 2026-10-31

- Key the transitional-shrink guard by View instance (#363): record the baseline size when a fresh SurfaceView is created, even if its initial bounds match the previous surface, preventing latched settle shrinks and black borders.
- Add IMMERSIVE_STICKY and delayed fullscreen reassertion (#363): include `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` and re-assert fullscreen after regaining window focus, suppressing lingering OEM system bars after reverse camera cycles.
- Capture hostapd log file directly via -dd -f in foreground probe (#330, #348): output daemon log directly to file during foreground failure probes instead of relying on shell redirection, capturing actual root causes on affected Honda units.

See [v0.3.10 release notes](docs/RELEASE-NOTES-0.3.10.md) for details.

# DiPlay 0.3.9 — 2026-10-30

- Arm the transitional-shrink guard at surface establishment (#363): record the baseline size upon initial SurfaceView bounds establishment so transitional shrinks after Activity resume are ignored inside the 3s settle window, preventing latched black borders.
- Do not release Bluetooth bootstrap while an RTSP handler is in flight (#316): defer post-transport USB Bluetooth teardown while `inFlight > 0`, preventing dropped bootstrap transport during multi-second pair-setup requests.

See [v0.3.9 release notes](docs/RELEASE-NOTES-0.3.9.md) for details.

# DiPlay 0.3.8 — 2026-10-30

- Give 32000 Hz microphone requests fallback capture rates (#360): add 48000 Hz (with 3:2 downsample) and 16000 Hz (with 1:2 upsample) capture fallbacks for head units whose audio drivers return -1 on 32000 Hz minimum-buffer queries, fixing telephony uplink silence on RK3188 and similar platforms.
- Stop the wired retry loop when the iPhone's USB state wedges on STALL (#359): count consecutive failures carrying the all-attempts-stalled hint and stop after 3, prompting the user to unplug, reboot the iPhone, and reconnect across all 7 locales.

See [v0.3.8 release notes](docs/RELEASE-NOTES-0.3.8.md) for details.

# DiPlay 0.3.7 — 2026-10-29

- Post fallback surface re-add out of layout pass (#290): defer the removal and re-addition of the fallback SurfaceView to after the layout traversal (`fallback.post`), eliminating the null pointer dereference on Android 5.1.1 during startup size oscillations.
- Extract call volume boost from communication mode (#290): decouple `STREAM_VOICE_CALL` volume boosting into an independent helper so that vehicles with mic compatibility enabled still receive boosted call volume and clean restoration.
- Skip communication mode under telephony downlink music switch (#343): completely bypass `MODE_IN_COMMUNICATION` when routing telephony audio through the music stream, preventing firmware audio routing takeovers from silencing in-call downlink sound.

See [v0.3.7 release notes](docs/RELEASE-NOTES-0.3.7.md) for details.

# DiPlay 0.3.6 — 2026-10-29

- Ignore transitional shrinks inside the settle window after a surface re-add (#290): filter out intermediate smaller bounds within the 3-second settle window after a grow re-add, keeping the restored full-size Surface instead of collapsing back down.
- Skip MainAudio untake on wireless CarPlay sessions (#293): retain negotiated MainAudio ownership on wireless links instead of issuing Untake changeModes, preventing the iPhone from aborting the screen stream and closing the session.

See [v0.3.6 release notes](docs/RELEASE-NOTES-0.3.6.md) for details.

# DiPlay 0.3.5 — 2026-10-29

- Recover video before retiring the control session; prevent cancelled recovery work from rearming a stale session timeout (#327/#350).
- Preserve MediaCodec Surface reuse by removing CPU Canvas clearing during decoder release; cover consecutive H.264 decode sessions on Android 4.4 (#316).
- Drop stale video backlog while output remains live and re-add the fallback surface after reverse return (#351/#290).
- Bound repeated USB-busy retries, retain the single EBUSY configuration retry, and record NCM queue-versus-wire counters (#305/#352).
- Restore call volume and speakerphone state; offer an opt-in music-stream telephony downlink, and prefer HFP release over disabling Bluetooth (#343/#290).
- Request Android 12+ Bluetooth scan permission and capture the hostapd foreground probe exit code (#293/#337).

See [v0.3.5 release notes](docs/RELEASE-NOTES-0.3.5.md) for details.

# DiPlay 0.3.4 — 2026-10-28

- Rebuild video decoder after repeated output stalls (#346): force-rebuild the decoder and cold-start with a fresh IDR frame when output stalls exceed threshold, fixing frozen/corrupt CarPlay video that cannot self-recover after extended driving.
- Auto-disconnect phone Bluetooth after CarPlay connects (experimental) (#343): new opt-in toggle to automatically disconnect iPhone Bluetooth from the head unit once wireless CarPlay is established, eliminating audio channel conflicts and noise.
- Apply earpiece loudspeaker remedy on pre-API 24 head units (#343): correctly apply earpiece loudspeaker volume correction on devices below API 24, fixing low or silent audio on affected models.
- Run hostapd once in the foreground on launch failure (#337): capture real hostapd error output by running it synchronously in the foreground on failure, enabling precise diagnosis on non-rooted head units.
- Fall back to app_process radio helper when svc leaves wlan0 absent (#301): automatically fall back to the app_process radio path when `svc wifi enable` fails to create wlan0, improving wireless hotspot compatibility on some AOSP head units.
- Apple vendor IE toggle for root hostapd backend (experimental) (#344): new experimental option to control Apple vendor IE broadcast in root hostapd mode, resolving iPhone association rejections on strict firmware.
- Keep RFCOMM cause chain in bootstrap failure logs (#293): preserve the full RFCOMM failure cause chain rather than truncating to a generic error, improving wireless connection diagnostic accuracy.
- Drop apostrophe breaking AAPT string compilation (#343): remove unescaped apostrophe from i18n strings that caused AAPT build failures in affected locales.

See [v0.3.4 release notes](docs/RELEASE-NOTES-0.3.4.md) for details.

# DiPlay 0.3.3 — 2026-10-28

- Wireless link failure forget-and-repair prompt after 4 consecutive failures (#290): surface a targeted dialog guiding users to forget the car hotspot and re-pair after four consecutive link failures, resolving persistent wireless CarPlay connection failures caused by hotspot IP drift or stale credentials.
- Cap IPv6 address wait at a short grace once IPv4 is usable (#343): compress the remaining IPv6 wait to a short grace period once IPv4 is ready, reducing hotspot setup latency significantly.
- Force fallback surface recreation on reverse-return height growth (#290): trigger surface rebuild when CarPlay display height grows anomalously during reverse-return, fixing black/frozen screen regressions after exiting reverse camera.
- Capture hostapd launch evidence on hotspot bring-up failure (#337): save hostapd startup log evidence to diagnostic report on bring-up failure, aiding root-cause analysis on non-rooted head units.
- Log PCM peak in audio stats (#309): record per-buffer PCM peak (dBFS) in audio statistics alongside ducking diagnostics to help diagnose volume anomalies and silence issues.
- Best-effort sync on USB diagnostic report copy (#341): fsync after USB report file copy to reduce diagnostic package corruption on low-end head-unit filesystems.

See [v0.3.3 release notes](docs/RELEASE-NOTES-0.3.3.md) for details.

# DiPlay 0.3.2 — 2026-10-27

- Hostapd process check without grep on minimal ROMs (#337): parse `ps` output in pure Kotlin without shell grep/wc/tail pipelines, resolving repeated hostapd kill-and-retry loops on Android 4.2 / MY16ADA.
- Drop non-primary IPv6 link-local addresses from 0x5703 payload (#290, #339): eliminate 30-second TCP timeouts on wireless CarPlay caused by iPhones preferring unreachable unscoped IPv6 link-locals over working IPv4.
- Wired CarPlay forced re-enumeration vendor request (#335): ensure forced re-enumeration bypasses stale fast-probe configurations and always issues the 0x51 vendor request with KitKat settle delay.
- Restore wireless CarPlay MainAudio Untake (#312, #316): allow `takeMainAudio()` to untake audio on wireless sessions when returning from car radio/Bluetooth, resolving silent audio regressions.
- Audio ducking diagnostics (#309): log volume and duration values for duckAudio/unduckAudio AirPlay commands.

See [v0.3.2 release notes](docs/RELEASE-NOTES-0.3.2.md) for details.

# DiPlay 0.3.1 — 2026-10-27

- Media focus exclusive switch (#290): add an opt-in toggle in Advanced audio-focus controls requesting AUDIOFOCUS_GAIN for media streams, resolving permanent ducking to silence on vendor stacks where MAY_DUCK fails.
- Wireless CarPlay background handoff & session preservation (#316): preserve wireless session and data channels across app backgrounding and resume transitions, reducing reconnection latency.
- Layout settle listening through settle window (#290): maintain size change recomputation throughout the 3s settle window, eliminating stuck viewport margins and bottom bars after reverse camera exit.
- Wi-Fi Direct fast failure & diagnostic polish (#331, #285): fail fast with actionable guidance to Same LAN or wired mode when Wi-Fi Direct is unsupported on hardware/system; record root USB grant failure details in diagnostic log.
- Bluetooth RFCOMM/DLC eviction & hotspot retry (#165, #313): evict wedged RFCOMM layers on DLC open failures; retry Bluetooth stack reset once when the first client joins hostapd.
- Resume lifecycle guard (#310): prevent RejectedExecutionException on resume when the dark-mode executor is shut down during teardown.

See [v0.3.1 release notes](docs/RELEASE-NOTES-0.3.1.md) for details.

# DiPlay 0.3.0 — 2026-10-27

- Huawei HiSilicon hardware AVC decoder native port sizing (#324): use unconstrained native port sizing (omit KEY_MAX_INPUT_SIZE override) on OMX.k3.video.decoder.avc, avoiding the fatal ACodec SIGABRT without suffering KitKat software decoder -2 Main/High CABAC black screens.
- Legacy-chip reverse-exit delayed cold start (#315): introduce an experimental opt-in setting in Advanced display; fully release video decoder during reverse gear and cold-start with fresh IDR keyframe after a 2.5s settling delay, avoiding driver deadlocks on MTK 8227L.
- Boot-time hotspot self-healing broadcast (#315): trigger background hotspot auto-enable on BOOT_COMPLETED when opted in, eliminating manual system settings setup on units that never bring AP up at boot.
- Diagnostic export crash & in-app updater timeout polish (#332, #333): guard external files dir lookups on API 17 disguised as 4.4; improve updater mirror failover diagnostics.
- Existing Wi-Fi multi-IP broadcast & Bluetooth stack auto-reset (#330): filter multi-NIC interfaces for local existing Wi-Fi announcements; add automated reset for stuck Bluetooth stacks on legacy head units.

See [v0.3.0 release notes](docs/RELEASE-NOTES-0.3.0.md) for details.

# DiPlay 0.2.99 — 2026-10-27

- Microphone 24kHz hardware failure & software resampling fallback (#329): detect and gracefully fall back to native supported sample rates (16k/44.1k/48k) when legacy hardware AudioRecord rejects 24kHz with code=-2; resample in real-time to ensure Siri and voice inputs function properly.
- Custom car brand icon RTSP handshake timeout fix (#328): resolve RTSP SETUP timeouts and dropped connections caused by synchronous custom icon serialization during wireless CarPlay bootstrapping.
- SoftAVC decoder -2 crash & dynamic map stream decoder thrashing avoidance (#324, #326, #327): harden OMX.google.h264.decoder lifecycle; prevent rapid destruction loops on dynamic map stream switches.
- SurfaceView viewport border padding elimination (#290): refine aspect ratio and layout bounds recomputation upon reverse-gear exit and multi-window resizing.
- Wireless CarPlay start handoff & USB Bluetooth robustness (#316, #321): strengthen early protocol exchange and background connection transitions.
- Root hostapd hotspot & MapEmbedService security hardening: sanitize SSID/passphrases against control characters, enforce strict ATTACH caller restrictions, and clean up staged config files on teardown.

See [v0.2.99 release notes](docs/RELEASE-NOTES-0.2.99.md) for details.

# DiPlay 0.2.98 — 2026-10-26

- Huawei HiSilicon hardware AVC decoder crash workaround (#324): work around fatal Stagefright ACodec SIGABRT assertion `CHECK(def.nBufferSize >= size)` on Huawei Ascend P6/P7 (hi6620oem / Android 4.4.2); automatically prioritize stable software AVC decoder (OMX.google.h264.decoder) when OMX.k3.video.decoder.avc is detected.
- ECARX Android 5.1 stock hotspot auto-start & permission declaration (#322 / PR #323): declare CHANGE_NETWORK_STATE to satisfy legacy ConnectivityManager.enforceTetherChangePermission; add direct opt-in stock hotspot toggle for xea406m / ecarx.settings head units without local ADB requirement.
- In-call audio silence & short Opus frame drop elimination (#271, #272): resolve one-way/two-way inaudible CarPlay telephony calls; refine Opus audio packet decoding and stream routing.
- Reverse camera exit viewport border padding elimination (#290): fix black border padding and viewport offset regression after returning from reverse camera; recalculate aspect bounds and flush view layout immediately upon reverse exit.
- Legacy head unit stock hotspot & USB Bluetooth cold boot sequence enhancement (#321): optimize cold boot device enumeration and permission gates for automated wireless connection.

See [v0.2.98 release notes](docs/RELEASE-NOTES-0.2.98.md) for details.

# DiPlay 0.2.97 — 2026-10-26

- Car Bluetooth audio resume & Untake protocol fix (#312): fix silent audio when switching back from car Bluetooth to CarPlay; correct audio reclaim transferType from Take to Untake so iOS resumes streaming media audio over Wi-Fi.
- External USB Bluetooth dongle boot auto-start & default grant persistence (#321): add dedicated UsbBtDeviceAttachedActivity with static usb_bt_device_filter.xml matching Bluetooth controllers (Class 0xE0/1/1 and ATS2851/Haylou/Realtek/CSR) to persist default permission across reboots without popups; remove permission gate during cold boot to allow silent root grants.
- Wireless CarPlay control channel stall & USB Bluetooth handoff deadlock elimination (#316): eliminate deadlock where the phone waits for Bluetooth release and the car waits for RTSP; send standard RFCOMM_DISC frames on close, issue HCI OP_DISCONNECT when ACL refcount reaches zero, proactively release Bluetooth bootstrap 2.5s after TCP accept, and add 15s session timeout recovery.
- Reverse-camera return navigation black-screen lockup elimination (#320 / #315): fix 100% black-screen freeze when returning from reverse gear on dynamic map streams (AutoNavi/Amap); reset consecutive unrendered frame counters and output timestamps on surface recreate, grant a 3.5s pipeline warm-up grace period, flush stale queued frames, and forbid destructive MediaCodec releases on output stall.
- Non-root Bluetooth stack reset fallback & RFCOMM contention avoidance (#312): wait asynchronously for discovery cancellation and add 100ms settling delay to prevent "Device or resource busy" on Bluedroid; support BluetoothAdapter.disable()/enable() fallback for non-root recovery.

See [v0.2.97 release notes](docs/RELEASE-NOTES-0.2.97.md) for details.

# DiPlay 0.2.96 — 2026-10-26

- Reverse-camera return freeze & dynamic video crash elimination (#320 / #315): fix degenerate pipeline detection storm (187 recover calls/sec) when returning from reverse gear with active navigation streams; debounce pipeline checks, drop stale frames on surface reconnect, request IDR keyframes instead of destroying MediaCodec, and register missing vendor reverse exit intents.
- Legacy head unit API 17 diagnostic export crash & touch response fix (#319): safely invoke Context.getExternalFilesDirs via reflection with Throwable catch-all on API 17 (Android 4.2 masquerading as 4.4.4); eliminate initial connect decoder thrashing to restore smooth touch and display response.
- Allwinner T3 fake SDK version bypass & usbfs spinlock lockup fix (#318): use realSdkInt() to penetrate faked Android 8.1 Build properties on Allwinner T3 ROMs; widen USB re-enumeration settle delay to 250ms to prevent Linux 3.4 sw_usb spinlock lockups, watchdog hard resets, and RTC date resets.
- External USB Bluetooth adapter (UGREEN CM390) wireless stall optimization (#316): add 12s silent peer timeout on Bluetooth iAP2 channel to quickly drop unresponsive RFCOMM fallback and allow ready Wi-Fi control channels to take over.
- Car Bluetooth audio resume & early handshake fix (#312): fix audio route restoration and prevent handshake deadlocks when connecting to original car Bluetooth before CarPlay.

See [v0.2.96 release notes](docs/RELEASE-NOTES-0.2.96.md) for details.

# DiPlay 0.2.95 — 2026-10-25

- Video decoder smooth backlog recovery (#310 / #314): decouple transient frame queue backlog from decoder failure; drop stale buffered frames and request IDR keyframes instead of destroying MediaCodec, eliminating 0.2fps freezing on navigation apps (AutoNavi/Amap).
- Reverse-camera return blanking & low-framerate stall self-healing: fix blank screen regression when switching back from reverse gear; auto-heal frozen video pipelines on low/empty frame rates.
- Allwinner T3 USB system prompt elimination & kernel spinlock fix (#311): remove static USB_DEVICE_ATTACHED intent-filter to eliminate intrusive system popup dialogs; add 150ms settling delay on API <= 19 to prevent usbfs spinlock lockups and watchdog reboots.
- Concurrent media and navigation audio focus preservation (#310): track navigation streams during media playback; never abandon audio focus lease while guidance is active, preventing car hardware amplifier mutes.

See [v0.2.95 release notes](docs/RELEASE-NOTES-0.2.95.md) for details.

# DiPlay 0.2.94 — 2026-10-24

- Redesigned three-finger quick settings panel: adopt a modern two-column card layout (category navigation + setting cards) tailored for car displays, and fix instant brand logo updates in-session.
- Video decoder backpressure & surface retention: smart queue backoff to prevent CPU thrashing on legacy chips (T3, MTK); retain video surface across window focus changes to eliminate blanking during volume adjustments.
- Wired replug watchdog & USB Bluetooth stale stream cleanup: clean USB endpoints upon bulk-out watchdog timeout; prevent stale RFCOMM stream reuse during Bluetooth pairing.

See [v0.2.94 release notes](docs/RELEASE-NOTES-0.2.94.md) for details.

# DiPlay 0.2.93 — 2026-10-24

- Continuous wireless invitation re-transmissions (#293): keep re-inviting the iPhone after transport notification until TCP connects, eliminating handshake stalls during Wi-Fi transitions.
- Hostapd ifconfig dual-format compatibility & error surfacing (#308): parse both "inet addr:" and "inet " formats in ifconfig output, and surface IP assignment failures explicitly instead of silent timeouts.

See [v0.2.93 release notes](docs/RELEASE-NOTES-0.2.93.md) for details.

# DiPlay 0.2.92 — 2026-10-24

- Concurrent Bonjour & Bluetooth bootstrap (#293): start Bonjour advertisement concurrently with Bluetooth handshake instead of serializing, drastically cutting wireless connection times.
- CarPlayStartSession retry on silent iPhone (#293): re-send initial session request while the phone stays silent, preventing handshake timeouts.
- Hostapd liveness check without shell pipelines (#308): eliminate wc/tail shell pipelines in hostapd watchdog, accommodating minimal busybox/toybox environments.

See [v0.2.92 release notes](docs/RELEASE-NOTES-0.2.92.md) for details.

# DiPlay 0.2.91 — 2026-10-24

- Root hostapd staged config deployment (#301 / #306): deploy hostapd configuration via staged file copy instead of root shell heredocs, resolving empty config files on customized/busybox shells (Honda Civic).
- Custom icon save-time limits enforcement (512px / 256KB) (#263): sample-decode and scale custom brand logos at save time, protecting AirPlay session payloads and memory.
- Fake-version ROM diagnostic export resilience (#300): gate secondary-volume scans behind verified runtime capabilities so diagnostic reports export safely on ROMs with spoofed build.prop versions.
- NCM diagnostic NTB OUT offset fix (#307): read NTB OUT parameters at the correct struct offsets during link-state probing.

See [v0.2.91 release notes](docs/RELEASE-NOTES-0.2.91.md) for details.

# DiPlay 0.2.90 — 2026-10-23

- Root hostapd multi-path probe (#301): probe for hostapd and dnsmasq across /system/bin, /system/xbin, /vendor/bin rather than hardcoding /system/bin, accommodating Honda Civic and customized ROMs.
- Custom icon sample-decode & payload shrink (#263): sample-decode oversized custom brand logos before shrinking the AirPlay session info payload, avoiding OOMs and preserving sharpness.
- USB Bluetooth dongle permission grant diagnostics (#291): log each stage of USB permission requests to diagnose silent prompt rejections on custom head units.
- NCM link-state diagnostic safe routing (#137): route NCM alternate-setting diagnostics through API-guarded helpers for legacy Android compatibility.
- Multi-window & reverse-camera surface yield (#300): reliably yield video surface while another window owns display to eliminate screen thrashing.

See [v0.2.90 release notes](docs/RELEASE-NOTES-0.2.90.md) for details.

# DiPlay 0.2.89 — 2026-10-23

- Native ChaCha20-Poly1305 screen frame decryptor (#249): bundle native C decryptor with JNI bridge across ARMv7, ARM64, and x86, drastically reducing CPU load and frame drops on legacy head units.
- Experimental Root hostapd hotspot backend (#294 / #299): add root hostapd option in Advanced settings for custom ROMs lacking framework hotspot APIs.
- Audio focus lifecycle & channel isolation: release permanent STREAM_MUSIC focus hold; isolate focus lease return to media channels only.
- API 17-18 compatibility hardening: eliminate API 19+ exception classes in catch clauses to prevent Dalvik verification failures on Android 4.2/4.3.
- Multi-window surface yield & wired NCM diagnostics (#300): yield video surface while another window owns display to prevent split-screen blanking; report bulk-out errno and NCM state; dark waiting screen in day mode.
- Security enhancements: eliminate hardcoded keystore passwords; restrict remote MFi HTTP to local network; unexport BootReceiver.

See [v0.2.89 release notes](docs/RELEASE-NOTES-0.2.89.md) for details.

# DiPlay 0.2.88 — 2026-10-23

- Wireless P2P group reuse & frequency adaptation (#293 / #582): reuse retained P2P groups with existing clients to cut reconnection times; skip 5 GHz attempts on 2.4 GHz-only radios; advertise alternative address families upon first-TCP timeouts.
- Wired bulk-OUT watchdog (#137): watchdog timer for hanging bulk-OUT transfers on legacy USB controllers (Allwinner T3), terminating unresponsive pipes cleanly.
- Video decoder thrashing mitigation & frame-loss notification (#251 / #288 / #290): debounce keyframe-wait recovery; notify decoder upon upstream frame loss; two-stage video layout reapply upon Surface rebuilds and reverse camera exits.
- Bluetooth Dongle persistence & structured AEC diagnostics (#272 / #281 / #289): remember USB Bluetooth dongle choice across replugs; structured AEC status in audio diagnostics; resilient export across empty ARP and clipboard NPEs.
- Silver-white Volkswagen and Toyota logos & custom icon payload cap.

See [v0.2.88 release notes](docs/RELEASE-NOTES-0.2.88.md) for details.

# DiPlay 0.2.87 — 2026-10-23

- Boot-time car hotspot readiness budget: grant a 60-second readiness budget for both auto-enable and interface observation during the first 3 minutes after system boot, accommodating slow head units (Allwinner T3) where vendor AP services start well after DiPlay autostart.
- iAP2 control message drop during teardown (#292): drop late-arriving control messages once link/CSM teardown begins instead of rejecting them as queue-full failures, ensuring clean session termination without false completed=false teardown errors.
- In-session settings menu style overhaul: align the three-finger overlay menu with the main settings card theme, using custom self-drawn drawables for radios, switches, and sliders to achieve uniform visuals on API 19+ head units.

See [v0.2.87 release notes](docs/RELEASE-NOTES-0.2.87.md) for details.

# DiPlay 0.2.86 — 2026-10-22

- Video decrypt queue slot wait & backpressure (#288): wait up to 120ms for slot availability instead of discarding frames when decrypt queue fills on slow decoders, replacing reference-breaking drops with TCP backpressure to avoid severe artifacts and 0fps freezes.
- Brand logo styling overhaul (#263): recolor Nissan and Audi brand logos with silver-white lines, ensuring clear visibility on dark CarPlay app tile backgrounds.
- Persistent session completion flag for wired reconnect (#269): persist completed session state to SharedPreferences so forced re-enumeration survives head-unit process kills on replug.
- Independent observation budget for manual hotspot (#284): dedicate a full readiness observation budget instead of sharing a 15-second deadline with auto-enable, resolving false unready loops on Chang'an CS75 Plus and customized ROMs.
- Call microphone diagnostic & error isolation (#272): log echo cancellation state triad (AEC setting, compatibility mode, echo reference) upon call mic start, protected against diagnostic callback exceptions.

See [v0.2.86 release notes](docs/RELEASE-NOTES-0.2.86.md) for details.

# DiPlay 0.2.85 — 2026-10-22

- Wired reconnect anti-deadlock & USB retry (#286 / #275 / #269 / #256): resolve kernel D-state freeze on Allwinner T3 (Android 4.4.2) upon physical cable replug by lifting usbfs release delay to 400ms and skipping lock waits on aborted/detached connections; retry USBMUX claim with configuration reselection on ENOENT; promote re-enumeration latch to process scope.
- Native software Opus telephony decoder (#272): bundle built-in libopus native software decoder fallback for platforms lacking system Opus MediaCodec, resolving inaudible CarPlay phone calls on legacy Android ROMs.
- Independent media & navigation volume (#253 / #263): add independent media and navigation stream volume settings with digital gain scaling in the audio sink.
- Wireless hotspot & Bluetooth teardown hardening (#271 / #281): bypass auto-enable hard failure in manual hotspot mode to accommodate ROMs whose AP runs outside Android framework; force disconnect and purge stale ACLs upon repeated L2CAP 0x4 rejection; enforce 3-second per-resource deadline during teardown to avoid stalled disconnects.
- Display, touch & updater improvements (#216 / #251 / #282 / #283): escalate keyframe wait timeout (>5s) to full recovery request; refresh frame on window focus gain; handle video layer container in sharp-corner mode; record touch scaling coordinates; strip UTF-8 BOM in update client.

See [v0.2.85 release notes](docs/RELEASE-NOTES-0.2.85.md) for details.

# DiPlay 0.2.84 — 2026-10-21

- Wired NCM bulk-out failure probing & interface reselect (#279): probe OUT endpoint status word via GET_STATUS upon first bulk-out failure to identify halt/STALL conditions; trigger SET_INTERFACE to reset data toggle and clear halt upon 5 consecutive transmit failures to recover Wedged host queues.

See [v0.2.84 release notes](docs/RELEASE-NOTES-0.2.84.md) for details.

# DiPlay 0.2.83 — 2026-10-21

- Direct USB usbfs mode (#266): add direct usbfs discovery and raw connection fallback via sysfs device enumeration and root permission escalation for customized ROMs with broken Android USB host framework; add direct-mode setting in Advanced.
- Wired reconnect lifecycle & retry guard (#275 / #270 / #243): proactively trigger vendor re-enumeration on reconnect after completed sessions to prevent iPhone syn=0; halt futile reconnect loops and prompt driver to re-plug after consecutive TCP timeouts; log stale-session dataPort traffic and unmatched mDNS queries.
- Telephony Opus decoder fallback (#272): automatically fall back to software decoding and retry in-stream when MediaCodec hardware decoder initialization fails, eliminating silent voice calls.
- USB Bluetooth stream reuse & handshake fallback (#274): purge closed dead RFCOMM streams before reusing cached endpoints; fall back to default iAP2 channel 1 when iOS rejects or times out on SDP queries; tolerate delayed reader thread exits on legacy Android kernels.
- Compatibility & diagnostic improvements: support versatile UsbInterface hidden constructor shapes across Android versions; record detailed failure reasons in wireless stop diagnostics.

See [v0.2.83 release notes](docs/RELEASE-NOTES-0.2.83.md) for verification and device-testing limits.

# DiPlay 0.2.82 — 2026-10-21

- Fix CM390 SDP UUID search encoding, L2CAP configuration channel IDs and handshake completion, and RFCOMM session direction/C/R bits; retain explicit discovery and peer-refusal errors.
- Serialize Bluetooth authentication per ACL and allow time for driver confirmation; keep dongle data on its own adapter rather than falling back to T3 GOC audio sockets.
- Localize USB pairing dialogs in all seven locales and unify Bluetooth diagnostics, restoring the external adapter service after hardware checks.
- Skip system VPN consent for the existing userspace wired network path.
- Stop detached wired resources outside the busy control executor, gate reconnect until the old run exits, release aborted network bridges, and avoid USB interface teardown during in-flight I/O or after detach.
- Ignore stale session-end callbacks after a replacement session starts.

See [v0.2.82 release notes](docs/RELEASE-NOTES-0.2.82.md) for verification and hardware-testing boundaries.

# DiPlay 0.2.81 — 2026-10-20

- Car Bluetooth audio bypass mode (#261): add option to withhold CarPlay audio declaration so audio, navigation, and telephony route entirely through the iPhone's Bluetooth connection with the vehicle; add detection warning when an external USB Bluetooth dongle is connected without A2DP capability.
- Call microphone compatibility mode & WeChat silent call fix: add toggle for call microphone compatibility (auto-enabled on Allwinner/custom ROMs) switching telephony uplink from VOICE_COMMUNICATION to standard MIC; refine silence detection threshold and automatically persist compatibility mode upon detecting continuous silence.
- Asynchronous video decrypt pipeline & nonce alignment (#249): decouple ChaCha20 frame decryption into a dedicated worker thread with reception-time nonce binding to prevent cipher desynchronization and consecutive auth failure crashes under buffer pressure.
- Reverse camera return display refresh (#250): request picture refresh (refreshPicture) upon TextureView surface reconstruction to eliminate black screens after reversing.
- USBMUX interface claim retry on slow kernels (#256) & USB Bluetooth background pairing auto-confirm fallback.
- Wired mDNS diagnostics (#262): distinguish responder calls, answered queries, transmitted/enqueued frames, dropped frames and unmatched query names.

See [v0.2.81 release notes](docs/RELEASE-NOTES-0.2.81.md) for validation and device-testing limits.

# DiPlay 0.2.80 — 2026-10-20

- External USB Bluetooth (CM390/Actions/Realtek) pairing authentication & self-test recovery: treat OP_AUTHENTICATION_REQUESTED and OP_SET_CONNECTION_ENCRYPTION as async link commands in HciEventDispatcher to resolve pairing authentication timeouts on iPhone connections; safely disable and re-enable USB dongle across hardware self-test; optimize Inquiry Scan duty cycle to 25% (640ms interval, 160ms window) and Page Scan to 80ms window with standard scan fallback for older chips.
- Wired NCM USB reconnect hang & system freeze prevention: implement fast abort (markAborted) across NCM USB bridge, clear TX queue, and abort in-flight bulk transfers upon detach; keep controller waiting for iPhone on wired detach without tearing down the entire host session, eliminating vehicle ANR and kernel deadlocks on legacy Allwinner T3 head units.

See [v0.2.80 release notes](docs/RELEASE-NOTES-0.2.80.md) for validation and device-testing limits.

# DiPlay 0.2.79 — 2026-10-19

- External USB Bluetooth (CM390/Actions/Realtek) iPhone discovery & EIR broadcast fix: add 16-bit audio service UUIDs (Tag 2: 0x111E Handsfree + 0x1203 Generic Audio) to Extended Inquiry Response (EIR) to prevent iOS Bluetooth Settings filter from hiding the adapter; configure HCI inquiry/page scan activity (1.28s interval, 40ms window) for responsive scan matching; avoid continuous 5.12s paging timeout to unrelated native Bluetooth devices on initial pairing; align broadcast local name with UI display; auto-restore advertising after hardware self-test (#246).
- Direct straight-angle display & margin elimination: adjust AirPlay viewport safeArea declarations to eliminate corner rounding and black borders on custom vehicle screens.
- Legacy system version guard (realSdkInt): unify version checks across audio sinks, location provider, crypto suites, and hotspot monitors using realSdkInt to prevent misreported Android OS versions on Allwinner T3 from triggering NoSuchMethod exceptions.

See [v0.2.79 release notes](docs/RELEASE-NOTES-0.2.79.md) for validation and device-testing limits.

# DiPlay 0.2.78 — 2026-10-19

- Wireless CarPlay Opus audio format declaration fix: unconditionally advertise Opus audio format (0x70000000) in AirPlay InfoPlist to prevent iOS from disabling wireless audio and dropping the CarPlay audio route option on Android 4.2/4.4 devices (#244).
- Allwinner T3 wired CarPlay re-enumeration deadlock elimination: increase settle delay to 400ms after USB permission grant on Android KitKat/JellyBean to eliminate kernel khubd descriptor race and D-state driver freeze (#243).
- NCM network stack asynchronous queueing & TCP handshake enhancement: decouple all mDNS answers, Router Advertisements, and Neighbor Advertisements through the async TX queue, proactively broadcasting RA on inbound frames to ensure immediate iOS IPv6 default route setup and TCP SYN initiation (#243).

See [v0.2.78 release notes](docs/RELEASE-NOTES-0.2.78.md) for validation and device-testing limits.

# DiPlay 0.2.77 — 2026-10-19

- Lone navigation audio focus & silent guidance fix: allow lone navigation tracks to acquire transient audio focus so head units that keep outputs muted without active audio focus open their physical audio paths, while continuing to overlay without ducking once media or call streams become active (#214).
- External USB Bluetooth RFCOMM flow control & SSP pairing enhancements: introduce FreeAuto-inspired credit-based flow control and credit grant negotiation on RFCOMM DLCs; improve SSP (Secure Simple Pairing) pairing agent compatibility for external USB Bluetooth dongles; add automatic USB device default grant helper.

See [v0.2.77 release notes](docs/RELEASE-NOTES-0.2.77.md) for validation and device-testing limits.

# DiPlay 0.2.76 — 2026-10-18

- Wired CarPlay secondary open deadlock & descriptor reuse fix: add kernel settle delay before reopening USB handle and reuse probed CarPlay configurations, bypassing redundant EP0 descriptor transfers to eliminate Linux kernel D-state deadlocks on legacy Allwinner T3 head units (#221, #227).
- Suppressed resetDevice ioctl on detach: prevent sending USBDEVFS_RESET ioctl to disconnected devices when write transfers fail during cable unplug, avoiding kernel panics and watchdog reboots (#227).
- Suppressed periodic USB Bluetooth log spam: check adapter active state before disable/shutdown to eliminate repeating log messages when no external USB Bluetooth dongle is plugged in (#227, #229).
- Android 4.2 (API 17) wired TLS KeyManagerFactory fallback & Bluetooth state filter: implement dynamic KeyManagerFactory algorithm fallback (default/X509/PKIX/SunX509) to prevent NoSuchAlgorithmException in Lockdown pairing, explicitly enable TLSv1.2 on legacy SSLEngine, and bypass disabled system Bluetooth in openRfcommStream to avoid masking external dongle errors (#234, #235).
- In-app updater IPv4 DNS priority & domestic mirror fallback: prioritize IPv4 (A records) over unroutable IPv6 (AAAA) on legacy car head units, enable OkHttp connection retries, and add gh-proxy.com mirror fallback to eliminate 12s connection timeouts during update downloads (#236).

See [v0.2.76 release notes](docs/RELEASE-NOTES-0.2.76.md) for validation and device-testing limits.

# DiPlay 0.2.75 — 2026-10-18

- External USB Bluetooth adapter (CM390/Actions/Realtek) pairing & connection fix: implement inbound RFCOMM (PSM 0x0003) L2CAP service factory and auto-session negotiation; immediate broadcast name initialization to eliminate 2-minute latency; disable forced PIN auth on SSP; remove unhandled HFP record to eliminate iOS pairing rejection.
- Hotplug & device filter expansion: register standard USB Bluetooth class (224/1/1) and broad VID matches to retain permissions across hotplugs.
- Wired mode USB bus deadlock & watchdog reboot prevention: safely disable USB Bluetooth dongle and pump threads when switching to wired mode or disabling wireless, eliminating kernel mutex deadlocks on legacy Allwinner T3 single-controller USB hubs.
- Cold startup performance optimization: eliminate redundant triplicate ECDSA verification loops during asset staging, slashing cold boot duration on low-end hardware from 10-15s to under 1s.

See [v0.2.75 release notes](docs/RELEASE-NOTES-0.2.75.md) for validation and device-testing limits.

# DiPlay 0.2.74 — 2026-10-18

- Wired CarPlay USB re-enumeration & descriptor probe fix: fix wired CarPlay disconnection and infinite retry loop on legacy head units (e.g. Allwinner T3) caused by erroneously skipping the Apple vendor re-enumeration request (0x52) on devices with standard iPhone PID 0x12a8; implement fast non-blocking descriptor probe with graceful fallback to standard vendor handshake and STALL tolerance (#217, #219).
- Accurate USB STALL diagnostic hint: remove misleading "emulation box" warning for real iPhones and provide clear user guidance to unlock screen and grant trust.

See [v0.2.74 release notes](docs/RELEASE-NOTES-0.2.74.md) for validation and device-testing limits.

# DiPlay 0.2.73 — 2026-10-18

- Legacy USB configuration fallback & reconnection fix: support graceful USB configuration fallback and robust descriptor parsing on legacy Android head units (e.g., Allwinner T3) when upgrading to v0.2.72 (#217).
- Wireless Bluetooth RFCOMM connection stability: strengthen RFCOMM retry budget, link state tracking, and error recovery for Android 4.4.2 head units (#218).
- Android 6.0 PackageInstaller crash fix: prioritize FileProvider `content://` URIs with granted read permissions on Android 6.0+ (API 23+) during update installation, eliminating "Package installer has stopped" crashes on Marshmallow OEM head units (#215).
- Test execution heap & cache optimization: increase test worker heap to 2 GB to ensure stable execution of the extensive 1,000+ unit test suite without memory exhaustion or Kryo binary corruption.

See [v0.2.73 release notes](docs/RELEASE-NOTES-0.2.73.md) for validation and device-testing limits.

# DiPlay 0.2.72 — 2026-10-17

- Navigation Opus audio & reverse camera black screen fix: fix silent turn-by-turn navigation on legacy head units and prevent black screen when switching back from reverse camera (#214).
- Wired connection deadlock & pairing timing fix: resolve UI freeze, channel starvation, and pairing trust race conditions during wired CarPlay negotiation (#211).
- Android 4.2 / API 17 USB bulkTransfer compatibility: fallback to 4-parameter `bulkTransfer` on API 17 to prevent NoSuchMethodError during USB transport (#213).
- AirPlay TCP snapshot preservation: preserve attached VPN service reference for NO_AIRPLAY_TCP watchdog diagnostics, preventing lost TCP states (#137).
- Re-enumeration optimization: skip redundant re-enumeration requests when trusted iPhone already enumerates in CarPlay mode (#205, #211).
- External USB Bluetooth adapter hardening: fix iAP2 service SDP queries, resolve link authentication & encryption timeout, and strengthen L2CAP frame handling.
- T3 low-end head unit media & input fix: resolve screen touch freeze, hotspot setting misdirection, aftermarket steering wheel track switching, and audio sink fallback.
- Turn off Wi-Fi before opening hotspot: prevent hardware conflict on single-chip Wi-Fi/AP head units (#172).
- Dynamic AAC software decoder fallback: automatically fallback to software decoding when platform AAC hardware decoder output degrades (#170).

See [v0.2.72 release notes](docs/RELEASE-NOTES-0.2.72.md) for validation and device-testing limits.

# DiPlay 0.2.71 — 2026-10-17

- External USB Bluetooth adapter diagnostics: log SDP iAP2 RFCOMM channel lookup results and ACL connection establishment/reuse events into user-exportable diagnostic logs (`UsbBtLog`) for field troubleshooting (#206).

See [v0.2.71 release notes](docs/RELEASE-NOTES-0.2.71.md) for validation and device-testing limits.

# DiPlay 0.2.70 — 2026-10-17

- Fast cold start: add fast-path asset matching in bootstrap, skipping redundant extraction and multiple BouncyCastle cryptographic validations on UI thread (cold start reduced from 10-15s to <0.5s).
- External USB Bluetooth adapter pairing & hotplug: support `EVT_LINK_KEY_REQUEST` & `EVT_PIN_CODE_REQUEST` negative replies for smooth SSP pairing fallback; preserve USB receivers during dongle disconnect; broadcast "DiPlay" name on adapter init.
- Fix wired freeze & watchdog reboot: eliminate unbounded spin loop in `IphoneUsbHost.readSynchronously` with strict deadline & open checks; resolve `LwipNcmBridge.close` interface release race with RX thread.
- Tolerant mDNS query parsing: resolve iPhone stuck in "Connecting to CarPlay" by parsing actual arrived UDP payload length, tolerating NCM tail truncation (#199, #204).
- Direct hotspot settings navigation on legacy ROMs: resolve `com.android.settings.TetherSettings` & `WifiApSettings` so Android 4.2 / 4.4 head units jump directly to portable hotspot settings instead of Wi-Fi list (#200).
- Smooth wireless/wired mode toggle: reset previous controller session on onResume when transport mode changes to prevent port 7000 & mDNS state conflict.

See [v0.2.70 release notes](docs/RELEASE-NOTES-0.2.70.md) for validation and device-testing limits.

# DiPlay 0.2.69 — 2026-10-16

- Fix Android 4.2 / API 17 music playback crash: adapt `registerMediaButtonEventReceiver` and `unregisterMediaButtonEventReceiver` to use `ComponentName` on API < 18, eliminating `NoSuchMethodError` when launching music or Now Playing (#197).
- Legacy audio media key resilience: wrap media-key registration, RemoteControlClient setup, and playback state updates in defensive exception handlers to avoid aborting on broken custom ROMs.
- Bundle Realtek USB Bluetooth firmware: package official firmware for 16 Realtek controller families (RTL8723A/B/D, RTL8761A/B/C, RTL8821A/C, RTL8822B/C, RTL8851B, RTL8852A/B/C, RTL8922A) for plug-and-play external dongle support on legacy head units.
- Android 4.4 steering-wheel media key forwarding: fix missing key delivery when media session is null on older platforms.

See [v0.2.69 release notes](docs/RELEASE-NOTES-0.2.69.md) for validation and device-testing limits.

# DiPlay 0.2.68 — 2026-10-16

- Dedicated thread for lwIP transmit: move lwIP network frame output to a dedicated sender thread, eliminating native lock contention and boosting throughput (#187).
- lwIP heap concurrency protection: acquire mutex before allocating input pbuf to prevent memory corruption and SIGABRT crashes.
- StartSession event precision matching: avoid watchdog-triggered handshake cancellations during wired setup (#187).
- Local VPN permission retry dialog: prompt driver to re-grant VPN consent when dismissed, eliminating dead-end connection attempts (#188).
- AirPlay TIME_WAIT & dual-stack socket binding: robust port reuse and dual-stack fallback for faster wireless reconnection.
- Android 4.2 video playback fix: guard MediaCodec.getName() behind API 21 check, resolving NoSuchMethodError on legacy runtimes.
- Legacy ROM Cipher.updateAAD fallback: dynamically detect updateAAD availability and smoothly fallback to BouncyCastle on API 17 / pseudo-4.4 units (#189).
- Upstream feature ports: WheelSiriKey double-click detection (#813), car hotspot retry loop avoidance (#789), and phone call earphone-to-speaker forced routing (#797).
- Automated release verification: ensure published APK strictly corresponds to current git tree with 0 diffs.

See [v0.2.68 release notes](docs/RELEASE-NOTES-0.2.68.md) for validation and device-testing limits.

# DiPlay 0.2.67 — 2026-10-16

- Fix Android 4.x startup crash (NoClassDefFoundError): include LoggingGate and FloatingDotOverlay in multidex main DEX keep rules and eliminate kotlin.Result from cold-start Application.onCreate.

See [v0.2.67 release notes](docs/RELEASE-NOTES-0.2.67.md) for validation and device-testing limits.

# DiPlay 0.2.66 — 2026-10-16

- Fix Android 4.2 SETUP loop failure & connection stall: DatagramSocket does not implement Closeable on API < 19; eliminate ClassCastException in socket binding to allow reliable handshake.
- Diagnostic logging toggle: add option in Settings → Diagnostics to disable session logging, significantly reducing CPU & disk I/O overhead on older head units.

See [v0.2.66 release notes](docs/RELEASE-NOTES-0.2.66.md) for validation and device-testing limits.

# DiPlay 0.2.65 — 2026-10-16

- Fullscreen app view & dock auto-hide gesture: optional toggle under Display settings to hide the CarPlay dock for full-screen navigation, with edge-swipe gesture to reveal.
- Instant wired reconnect on unplug & replug: eliminate 1-2 minute reconnect delay after physical USB cable replug.
- TCP packet loss & link stability improvements: optimize userspace lwIP packet processing and IPv6 neighbor solicitations.
- Desktop focus & touch responsiveness fix: eliminate unresponsiveness on head unit launcher desktop after lingering.
- Floating dot presentation polish: show floating dot only when leaving CarPlay and automatically sync its icon with current app.

See [v0.2.65 release notes](docs/RELEASE-NOTES-0.2.65.md) for validation and device-testing limits.

# DiPlay 0.2.64 — 2026-10-15

- Multi-USB port coexistence & peripheral isolation: automatically distinguish iPhone and USB Bluetooth dongles from dashcams (UVC) and storage drives (UDisk) on multi-USB head units (e.g. Allwinner T3).
- Android 4.2/4.3 compatibility fixes: guard against missing Cipher.updateAAD (API 19+) by falling back to bundled BouncyCastle ChaCha20-Poly1305, and replace HandlerThread.quitSafely (API 18+) with quit() on legacy platforms (#170, #178).
- Userspace lwIP TCP/IP stack for wired CarPlay: direct NCM USB frame relaying via userspace lwIP, bypassing system network stack limitations on legacy Android head units (Allwinner T3, MTK, etc.).
- Native JNI NCM relay (`lwip_ncm_relay.c`): zero-copy network buffer forwarding between USB host endpoints and CarPlay TCP/UDP services.
- In-app updater fix for Android 4.x/5.x: ensure PackageInstaller receives accessible file:// URIs with world-readable permissions and multi-directory fallback across diverse legacy storage layouts.

See [v0.2.64 release notes](docs/RELEASE-NOTES-0.2.64.md) for validation and device-testing limits.

# DiPlay 0.2.63 — 2026-10-15

- Automatic hotspot retry: retry starting the built-in car hotspot once after a 60-second delay if the initial boot-up attempt fails.
- Floating dot desktop navigation: route floating dot clicks through the system launcher entry, resolving black-screen lockups when tapped without an active CarPlay session.
- Media session protection: prevent premature link resets when the iAP2 control channel is silent but active AirPlay media is streaming.
- Compatibility crash fix: handle custom ROM Socket-to-Closeable casting gracefully without crashing during session shutdown.
- Wireless diagnostics: distinguish DHCP network failures from phone-side mDNS silence using passive mDNS sniffing and join probes.

See [v0.2.63 release notes](docs/RELEASE-NOTES-0.2.63.md) for validation and device-testing limits.

# DiPlay 0.2.62 — 2026-10-15

- Preserve the VPN-owned TUN when closing a wired NCM bridge so a subsequent connection attempt can reuse it without an Already closed failure.
- Keep NCM cleanup per attachment and release the retained TUN when the VPN service is destroyed.

See [v0.2.62 release notes](docs/RELEASE-NOTES-0.2.62.md) for validation and device-testing limits.

# DiPlay 0.2.61 — 2026-10-15

- Remove the direct API 21 AEADBadTagException reference so AirPlay crypto verifies on Android 4.2, preserving authentication-tag rejection.
- Use a window-token attachment check on Android 4.2/4.3 to avoid an API 19 method call when opening the connection screen.
- Include in-band SPS/PPS with the first H.264 frame on Android 4.2 for decoders that ignore separate codec-format parameters.
- Try enabling the hybrid Bluetooth adapter before wireless startup, with a bounded wait and the existing failure message if it stays off.
- Add wired and wireless connection-stage diagnostics from USB/Bluetooth through AirPlay session startup.
- Add direct diagnostic-log viewing with a loading state, without requiring a saved report first.
- Add legacy Android instrumentation coverage for authenticated decryption and tampered-ciphertext rejection.

See [v0.2.61 release notes](docs/RELEASE-NOTES-0.2.61.md) for validation and device-testing limits.

# DiPlay 0.2.60 — 2026-10-14

- Send an additional multicast mDNS response for non-QU wired queries while preserving the unicast path and unicast-only QU responses.
- Bound crash-trace cause traversal to prevent cyclic or deep cause chains from breaking crash reporting.
- Add regression coverage for wired mDNS response routing/checksums and bounded crash traces.

See [v0.2.60 release notes](docs/RELEASE-NOTES-0.2.60.md) for validation and device-testing limits.

# DiPlay 0.2.59 — 2026-10-14

- Select real Android API branches on legacy head units for USB, VPN, Bluetooth, media, settings and APK installation.
- Correct wired AirPlay IPv6 interface-scope lookup, answer mDNS service enumeration, and reuse the VPN TUN during retries to avoid stale Android 4.4 fwmark rules.
- Try bundled Conscrypt frame decryption on Android 4.4 while retaining the existing fallback.
- Repair legacy APK installation and settings rebuild crashes; add a diagnostic-report copy button and preserve redacted crash traces line by line.
- Fix release compilation and handling of the reused wired transport state.

See [v0.2.59 release notes](docs/RELEASE-NOTES-0.2.59.md) for package details and validation limits.

# DiPlay 0.2.46 — 2026-10-13

- Bring in the latest AirPlay wired listener interface-scoped binding and kernel LISTEN diagnosis.
- Use genuine platform API levels for USB/VPN compatibility on vendor ROMs that report misleading SDK levels.
- Improve Bluetooth diagnosis for HSAE external adapters and clarify iPhone wireless pairing instructions.
- Retain the Android 4.4 TLS 1.2 update fix and verified signed release installation from v0.2.45.

See [v0.2.46 release notes](docs/RELEASE-NOTES-0.2.46.md).

# DiPlay 0.2.45 — 2026-10-12

- Fix old Android head-unit update checks that negotiate only obsolete SSLv3 protocols; use a bundled TLS 1.2 HTTPS stack for Android 4.4/5.0 and a narrowly scoped fallback for affected newer vendor ROMs.
- Preserve certificate and host validation, approved GitHub redirect targets and APK SHA-256/signature verification. Improve localized failure guidance.
- Add TLS downgrade regression tests and live GitHub update-check instrumentation on Android 4.4 and Android 11.
- Include the latest upstream NCM TCP diagnostic budget/SYN observability improvements.

See [v0.2.45 release notes](docs/RELEASE-NOTES-0.2.45.md).

# DiPlay 0.2.44 — 2026-10-12

- Rebuild software-update dialogs in the navy/cyan car-display style: release notes, version banner, progress stages, and post-download installation confirmation.
- Show live downloaded bytes and transfer speed; allow downloads to continue in the background without cancelling their worker.
- Keep SHA-256 and signing-certificate checks plus explicit Android installation consent; improve Chinese/English update copy and Android 4.4-compatible drawing.
- Add UI regression tests for the update prompt, download-stage transitions, background button, and installation confirmation.

See [v0.2.44 release notes](docs/RELEASE-NOTES-0.2.44.md) for details and device-validation limits.

# DiPlay 0.2.14 — 2026-10-07

- Group Settings by driver goal with search, adaptive layouts, quick controls and reconnect notices (#369).
- Add an independent Interface size control, preserving CarPlay geometry and following real density/window changes (#378).
- Add a default automatic-connection choice, scheduled day/night mode and launcher return to active CarPlay (#373, #365, #376).
- Improve custom car-button image selection and clarify the main app's source-build instructions (#364, #377, #374).
- Repair the captured USBMUX diagnostic trailer, wake drained Bluetooth receive queues and retry explicitly rejected large USB reads once at 16 KiB; improve stream diagnostics (#362).
- Select compatible video output without window hardware acceleration; use Android video decryption when available and retain a fallback (#362, #332).
- Add default-off experimental Smooth video pacing with explicit latency/picture-adjustment limits (#367).
- Preserve usable hotspot interface addresses and authenticated Bluetooth control on the observed rendered-session handoff fallback (#394, #396).
- Add configurable steering-wheel Siri, bounded microphone-source fallback and optional audio-focus handling (#360, #371, #339).
- Repair BYD call-watcher/audio-status behavior and add opt-in, default-off experimental software call echo cancellation and voice filtering (#370).
- Preserve artwork proportions, correct system-bar-aware split/rotation geometry and recognize the specifically observed 1280×480 DiLink 3 cluster surface (#375, #389, #388).

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) for all 20 contribution links, credits and limitations. Android 9/API 28 remains required. See [validation](docs/VALIDATION.md) for measured checks; complete-release vehicle acceptance is not claimed.

# DiPlay 0.2.13 — 2026-10-06

- Enable the legacy Android 9 Wi-Fi Direct group path with generated credentials and serialized ownership/cleanup; requested frequency remains unverified on Android 9 (#282).
- Prefer current IPv4 hotspot endpoints, preserve scoped IPv6 fallback, and refine Auto channel priorities beside a 5 GHz station without guaranteeing a band (#283, #309).
- Avoid unused Android NSD and USB-service dependencies for wireless startup; retain only rendered sessions on the guarded handoff fallback (#313, #300, #258).
- Add explicit experimental hotspot join Check/Apply/Restore on eligible Android 13+, user-configured 5 GHz hotspots, with complete private recovery state and no automatic mutation (#251).
- Switch wireless to USB within the host activity, verify requested permissions independently, preserve other accessibility services and cancel stale permission work (#268).
- Preserve validated USBMUX payload replies with narrow four-byte trailer recovery (#298).
- Add optional live dock/split-screen areas, square-canvas screen rotation and the selected-decoder square check; add a default-off experimental side panel (#246, #277, #284, #245).
- Preserve DiLink 4 native casting mode, offer pre-connection calibration and apply live cluster picture adjustments (#260, #265).
- Retain a recent dashboard turn card only across wireless replacement within its stale window; add a Smaller map choice (125%) and the checked DiLink 3 full-then-half projection sequence with compensation (#304, #306, #296).
- Restore battery reads when only sys.car.protocol is populated and recover eligible unbound wheel services using already-authorized ADB (#285, #297).
- Add independent default-off experimental DiLink 3 call keys and dashboard calls, with initialized watcher readiness, unique ownership, pristine-safe cancellation and retryable dirty cleanup; target-car acceptance remains requested (#243).
- Correct the observed 24 kHz Siri microphone RTP clock while retaining 48 kHz for telephony/unobserved formats; add bounded, default-off experimental AAC-LC buffered music and single-session renderer ownership (#295, #308).
- Set TCP_NODELAY on the touch event channel; contributor latency observations remain device-specific (#311).
- Improve full-size multi-window home/settings appearance, ambient-setting visibility and shared menu persistence; add main-settings car-button customization (#252, #239, #281, #302).
- Add light waiting/cluster placeholders and a 300 ms cluster fade; the main waiting screen follows CarPlay day/night mode (#305, #317).
- Add Traditional Chinese (Taiwan) as the seventh app language, preserve explicit script selection and correct Simplified Chinese hotspot wording (#314, #286). The release website also gains a Traditional Chinese edition.
- Retain the multilingual website groundwork, add the smooth-wireless guide and make buffered-ownership tests deterministic without runtime/API changes (#240, #310, #312).

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) for all 35 contribution links, credits, experimental settings, compatibility limits and diagnostic export steps. Final exact-release validation is recorded in [VALIDATION](docs/VALIDATION.md). This remains a public preview; no fresh complete-release vehicle test is claimed.

# DiPlay 0.2.12 — 2026-10-04

- Add Existing Wi-Fi / Same LAN wireless CarPlay with scoped IPv4/IPv6 discovery and network-change cleanup (#223).
- Wait for a stable car-hotspot interface and recover bounded wireless attempts when no AirPlay TCP follows StartSession (#229); add observed-state, authorized-ADB hotspot fallback on firmware exposing supported commands (#235).
- Improve Apple USB attach matching and narrowly scoped optional USB-prompt assistance (#170, #224).
- Pause Android 10 station scans during eligible hotspot/P2P sessions, preserving Same LAN, with controller leases and durable retryable restoration (#225).
- Improve split-screen, launcher cards, short-screen preparation and virtual cluster/floating-map geometry (#171, #172, #181).
- Add independent system-bar controls and correct in-session save/cancel and Local/USB-CH341 authentication selection (#191, #194).
- Add system, light-sensor, day and night CarPlay appearance modes, richer custom turn cards, and live main-video picture controls (#178, #193, #211).
- Offer custom integer resolution from 30% to 160%, with shared limits, correct 30%/160% labels and decoder/canvas capability fallback; refresh connection settings on resume (#179, #230, #196).
- Reconcile opt-in DiLink 4 cluster routing/calibration into one decoder owner, retain verified HUD gates, and journal exact stock-map holds and recovery (#213, #187).
- Add DiLink 3 guidance text and projection-display support with committed recovery before mutation, partial-setup compensation and retryable stock restoration (#182).
- Add opt-in wheel map zoom and main-screen joystick while preserving press/release and call behavior; reject stale queued work across phone/screen changes (#214, #231).
- Switch supported dashboard contents live using actual delivery and safely retained paused choices; preserve selection across stream/phone replacement (#232).
- Add a five-second dashboard-song-on-change window with timer invalidation, and retain album art while the next transfer is pending (#215, #228).
- Export reports through Downloads, document picker, app-external or private fallback storage, with explicit View/Share actions (#185, #219).

See [0.2.12 release notes](docs/RELEASE-NOTES-0.2.12.md) for the complete corrections, hardware evidence and issue-reporting steps. This remains a public preview; no fresh end-to-end vehicle test of the complete repaired release is claimed.

# DiPlay 0.2.11 — 2026-10-03

- Add preferred Wi-Fi Direct channel selection for the next connection; Auto remains the default, and manual channel rejection/mismatch reports an error (#175).
- Add a movable custom dashboard turn card with 2% position steps; leave unknown arrows blank and clear expired guidance (#155).
- Offer two-, three- or four-finger settings swipes, keeping three as the default (#156).
- Add opt-in read-only legacy vehicle-data detection while preserving default DiLink 5.0 mode; reject unaccepted/stale probe publication and fix accepted-battery lock ordering (#158, integrated through #173).
- Keep wireless location/vehicle data on its runtime Wi-Fi link and defer parked-video decisions until SETUP/event readiness (#157).
- Add optional automatic car-hotspot startup, off by default, with verified own-package authorization and unified vehicle settings (#164/#173).
- Support Android TV/remote controls while preserving head-unit touchscreen Back and absolute knob X/Y; keep its workflow source-only (#146).
- Retain artists across partial title updates and publish song metadata/artwork only when changed (#161, #162).
- Correct Android 9 audio API use, release failed codecs, reconnect with settled geometry/readiness, and require own-app VPN scope (#168 and local corrections).
- Add one guarded Auto-mode API 29 P2P recovery for the exact reported pre-create Builder error, plus bounded wireless/media/theme/own-app-exit diagnostics without payload recording or automatic uploads.

See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) for requirements, device-test limits and fresh-report guidance. Preferred channel selection does not establish stutter as fixed; Qin Plus, Siri, iOS 15 and day/night reports remain under investigation. Android 9 is still the minimum; Wi-Fi Direct needs Android 10+.

# DiPlay 0.2.10 — 2026-10-03

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
