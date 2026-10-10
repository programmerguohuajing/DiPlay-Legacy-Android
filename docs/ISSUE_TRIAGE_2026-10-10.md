# Issue triage / 问题梳理（2026-10-10）

Inventory: 68 open, 7 closed at time of review, as returned by `gh issue list --state all --limit 100`. This document is an initial diagnostic triage, not a claim that all issues are resolved. Preserve issue reports and request new v0.2.32 logs when older APK behavior differs.

## P0: data-plane read failures, USB control, stability

- #79 (Allwinner T3 / Android 4.4.2; v0.2.32): USBMUX/NCM `Android could not queue ... read request` with `firstBytes=16384 fallbackBytes=not_attempted`; intermittent iAP2 READY before failures. Multiple attached files appear to contain identical saved diagnostics. Distinguish from wireless AirPlay startup.
- #78 (8227L / API 27; v0.2.32): CarPlay succeeds but `rx=0.4–11 fps`, `decode p90=310–774 ms`, read waits up to ~2 seconds, and recurrent USBMUX/NCM queue failures. Both decoder latency and upstream USB input starvation contribute; do not blame touch dispatch only. Verify request-size fallback first, then capture codec name, render mode and frame pacing.
- #61, #55, #58, #59, #71, #75, #77, #52, #50, #40, #46, #28, #8, #5: related wired or mixed connection failures; require issue-specific logs and observed failure stage before merging root causes.

## P0: wireless iAP2 negotiation / Wi-Fi bootstrap

- #68, #71, #72, #77, #79, #67, #69, #39: distinct head-unit models with RFCOMM or AirPlay setup stalls. Reused device-specific ROM details matter. #71 logs confirm RFCOMM streams but no iAP2 authentication or Wi-Fi configuration; do not assert pairing was successful just because HFP phone calling worked.

## P1: audio routing and absent independent CarPlay audio

- #76, #65, #63, #51, #44, #45, #34, #38: distinguish no wireless audio SETUP from audio arriving but routed to the wrong Android output. #65 reports that opening OEM A2DP media panel enables audio while CarPlay video stays alive, strongly suggesting OEM Bluetooth A2DP output rather than independently negotiated CarPlay audio. #76 shows frequent low video frame rates as well; collect audio session and AirPlay SETUP traces instead of changing volume routing blindly.

## P1: TLS, signing, installation

- #73, #67: old Android TLS provider TLSv1.2 failure. Bundled Conscrypt added after v0.2.31, so request a v0.2.32 diagnostic before attributing failures to that provider.
- #70: v0.2.21–v0.2.24 original Private Beta signature versus v0.2.25–v0.2.31 Debug signature; v0.2.32 APK was independently verified with original signature. Old Debug-signed installations still cannot update in place.
- #60, #32, #3, #15, #22, #41, #42, #43, #35, #57: install/parse/crash, request package verifier result and actual API/ABI before attributing to media transport.

## P2: platform support and feature requests

- #74 USB Wi-Fi adapters, #66 modern OEM support, #62/#36/#9 Android 4.2.2 API17 request, #18 x86 Android 8.1 request, #17 CarWith, #25/#49/#56/#54/#53/#33 vehicle-model requests, #19 permission UX, #37 fullscreen status bar, #47 Bluetooth availability: separate scope/feasibility from confirmed regressions.

## Verification standard

Every proposed fix should have: issue + source log, exact failure stage, regression test, reproducible build, device retest result, and release-tag inclusion status. Do not close issues based on unit tests alone. Never publish private saved logs or signing keystores.
