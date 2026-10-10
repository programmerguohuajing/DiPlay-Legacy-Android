# Issue #52: Android 7.x wired NCM/VPN compatibility migration

## References
- Legacy Issue #52: https://github.com/programmerguohuajing/DiPlay-Legacy-Android/issues/52
- Original issue with Android 7.1.1 log: https://github.com/shihabal3amri/DiPlay/issues/557
- Original fix (merged): https://github.com/shihabal3amri/DiPlay/pull/575
- Reporter says the tagged fork works: https://github.com/ding2548-ui/DiPlay/tree/v2.0-84

## Safely ported to the Legacy branch
- NCM inbound resynchronization at a strictly validated NTH16 header after a damaged USB transfer, with a limit of 16 recoveries in 10 seconds. TCP is responsible for re-sending lost packets.
- Optional zero-byte pad handling across read boundaries, based on the actual bulk-IN endpoint max packet size (64/512 bytes).
- Bounded NCM `setInterface` retry (5 attempts, 100 ms intervals), retaining existing legacy usbfs claim / configuration behavior and error codes. A failed selection still releases every claimed interface.
- VPN attach-stage logging (TUN establishment, IPv6 bridge, local listener), and the full exception class/cause/throwing frame in diagnostics.

## Deliberately NOT copied
The working `v2.0-84` tag does not call `Builder.addAllowedApplication(packageName)` when creating its VPN. This could contribute to an `Invalid argument` on a modified Android 7 ROM, but the supplied logs do not identify which specific Builder call fails. Removing per-app VPN scoping on Android 5+ would broaden packet interception; this migration preserves the current restriction and records the failing API stage instead. The existing Android 4.4 route-only compatibility behavior remains unchanged.

No assumptions of identical code structure or security properties were made across the upstream and Legacy branches. Changes unrelated to #52 (USB permission UI, identification for older iOS) were not cherry-picked.

## Validation
- USB/NCM/VPN targeted unit tests: 50 passed, no failures.
- Debug APK assembly passed locally.
- Requires the reporter to retest the modified APK on the affected Android 7.1.1 head unit; an Android emulator cannot reproduce its USB driver or VPN/netd behavior.