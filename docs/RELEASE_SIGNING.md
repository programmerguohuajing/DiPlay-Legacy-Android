# Release signing compatibility / 正式包签名兼容性

## Baseline / 基准证书

The v0.2.21 public APK uses the original DiPlay Private Beta signing certificate:

`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`

v0.2.30 and v0.2.31 were signed with a different Android Debug certificate. As a result, Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when attempting to update installations signed with the v0.2.21 certificate.

v0.2.21 公开包使用上面的原始 DiPlay 签名。v0.2.30、v0.2.31 使用了不同的 Android Debug 证书，不能覆盖安装旧签名版本。

## Signing a compatible Release / 构建兼容升级包

Restore the **original private signing keystore** using a secure channel. An APK contains only the public certificate; it cannot recreate the private key. Never upload this keystore or its passwords to the repository.

通过安全渠道找回原始签名私钥库；无法通过旧 APK 反推出私钥。不要把密钥库或口令提交到仓库。

Set the following local/CI secrets (no default signing password is allowed):

- `ANDROID_KEYSTORE_PATH` — absolute keystore path
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

First run `gradlew.bat :mobile:verifyReleaseSigningIdentity` on Windows. The task checks the certificate SHA-256 against v0.2.21 and stops on an unexpected certificate. To build a standalone car-test Release, additionally provision the independently verified `DIPLAY_AUTH_ASSETS_DIR`, then run `gradlew.bat :mobile:assembleStandaloneRelease`. Authentication asset verification is separate from signing verification.

先运行签名预检，通过后才能构建；MFi 认证资源校验与 APK 签名校验是两项独立要求。

Before attaching a Release APK, run Android SDK `apksigner verify --min-sdk-version 19 --print-certs <APK>`, verify SHA-256 and Android 4.4 compatible signature scheme, then confirm an in-place update on a test device that has v0.2.21 installed.

发布前要用 apksigner 验证签名、最低 API 19，并在安装了 v0.2.21 的测试设备上验证覆盖升级。

## GitHub Actions

`.github/workflows/verify-release-signature.yml` checks the published APK against the historical certificate on Release publication. It also supports manual revalidation by tag. This post-publication check is an alarm, not a substitute for pre-publication validation. Do not upload or publish an APK until the local signing task and APK inspection pass.

GitHub Actions 任务会在 Release 发布后检查签名，并允许按 Tag 手动重测；由于这是发布后检查，不能代替上传前的校验。

## Existing users / 现有用户

Users installed from v0.2.21 (original certificate) must not be told to install an APK signed with the later Debug key as an update. Preserve pairing/configuration data where possible. If the original private key cannot be recovered, a same-package in-place update cannot be guaranteed; backup and uninstall/reinstall may be necessary and may erase private data. Do not silently change the package name to mask this problem.

安装 v0.2.21 的用户不要直接覆盖安装 Debug 证书的新版。未找回原签名私钥前不要承诺无损升级。
