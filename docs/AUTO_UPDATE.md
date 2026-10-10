# GitHub Releases auto-update / GitHub Releases 自动更新

## Behavior / 使用方式

- If the public GitHub REST API is rate-limited, DiPlay uses the official GitHub Releases Atom feed and verifies the exact APK and SHA-256 assets.
- DiPlay checks the latest **stable** GitHub Release when the main activity opens, at most once every 24 hours after a successful check. Failed checks are eligible for another attempt on the next launch after an hour. No network error dialog interrupts automatic startup.
- Settings > Overview > About: **Check for updates**. The About DiPlay page also includes a switch to turn automatic checks on/off and a manual update button.
- If a newer release is found, release notes and a download confirmation are shown. A background task downloads the published APK while displaying progress.
- When CarPlay is active, release prompts are deferred and downloading/installing is blocked. Stop the car and disconnect CarPlay before updating.
- The APK is accepted only when GitHub provides both `DiPlay-vX.Y.Z-legacy-release.apk` and its `.sha256` sidecar. HTTPS, exact asset path, file size, SHA-256 digest, **package name**, **incremented version code** and **the installed app's signing certificate** are checked before installation.
- The system installer is launched only after explicit user confirmation. On Android 8.0+, the user may need to grant **Install unknown apps** permission for DiPlay. Normal Android apps cannot install updates silently.
- On Android 4.4, the app uses the bundled TLS 1.2 provider with platform trust verification, and a private FileProvider content URI. Some vendor ROMs may still lack a working system installer; users can install the verified official release manually.
- The debug package (`.hudtest`) has a different application ID and signature and cannot update the official package. Official Release APKs must be signed with the existing DiPlay original signing certificate.

## 中文说明

- 启动 DiPlay 时联网检查 GitHub 的最新正式 Release，成功检查后 24 小时内不重复请求，失败则可在一小时后下次启动时重试；自动联网失败不会打断开机使用。
- 在【设置 → 概览 → 关于】点击【检查软件更新】，或在【关于 DiPlay】页面开启/关闭自动检查与手动更新。
- 检测到新版本后显示版本说明，用户确认后在后台下载 APK、展示进度，并在安装前进行 SHA-256、包名、版本号和**现有应用签名**校验。
- 正在运行 CarPlay 时不进行下载/安装，自动检查结果延后提示。请先停车、断开 CarPlay，再安装。
- 无静默安装权限；必须经用户确认，并交由 Android 系统安装程序完成覆盖升级。Android 8.0+ 可能需要在系统设置中允许 DiPlay 安装未知来源应用。
- 只信任本项目 GitHub Releases 中与版本号匹配的 APK 和哈希文件。旧版签名不一致的包不能直接覆盖安装；Android 4.4 定制 ROM 的 HTTPS 与安装器兼容性仍需真机确认。

## Release publishing contract / 发布要求

Publish **both** of the following assets for each stable version. `versionName` must increase, `versionCode` must increase and the original app signature must be retained.

```
DiPlay-v0.2.34-legacy-release.apk
DiPlay-v0.2.34-legacy-release.apk.sha256
```

The SHA-256 file uses the format `<64-lowercase-hex>  <apk-filename>`. If either asset is missing, invalid, unsigned with the existing certificate or served from a different origin, the app refuses to install it.
