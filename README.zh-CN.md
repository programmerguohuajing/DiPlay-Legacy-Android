# DiPlay Legacy Android (中文版)

[简体中文](README.zh-CN.md) · [English](README.md)

> 本项目基于开源项目 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 进行深度兼容与重构，重点适配 **老款 Android 车机（最低支持 Android 4.4 KitKat / API 19）** 及各类车机硬件架构（全志 T3 / 32 位 ARMv7、Intel x86、主流 64 位 ARM 等）。
>
> 上游原项目地址：https://github.com/shihabal3amri/DiPlay

为兼容的比亚迪及老旧 Android 车机提供有线（USB）及无线 CarPlay 互联支持，内置熟悉的 DiAuto 风格界面。独立应用包名：`com.shihab.diplay`。

**验证边界：最低安装版本不等于真机连接保证。** 当前 T3 / Android 4.4 适配基于 API 19 软件检查和模拟器专项测试；尚未完成该 T3 车机与 iPhone 15 Pro / iOS 26.6 的 USB、蓝牙、热点、画面、声音和触控全链路验证。内置实验性附件身份的本地签名成功，也不等于 iPhone 必然认可它。专项结果见 [T3 验证记录](docs/T3-ANDROID44-VALIDATION.md)。

---

## 核心改进与特性

相比上游原版（通常仅支持 Android 9+ 及 64 位系统），本项目针对老车机进行了全方位底层加固与重构：

### 1. 深度适配低版本 Android 系统 (Android 4.4+)
- **系统门槛下探至 Android 4.4 (API 19)**：为基础运行路径增加旧版实现与 API 判断。仅在高版本可用的实验性功能不会因此获得低版本硬件支持：
  - 修复 Android 7.0 以下因 `Configuration.locales` 引发的 `NoSuchFieldError` 启动闪退。
  - 修复 Android 6.0 以下因调用 `Context.getSystemService(Class)` 引发的 `NoSuchMethodError` 闪退。
  - 修复 Android 5.0 以下 `Theme.Material` 主题缺失导致的膨胀异常，回退兼容 Holo 主题。
  - 修复无 `BluetoothLeScanner` 低版本系统的蓝牙扫描，支持手动输入蓝牙 MAC 地址连接。

### 2. 解决安装与解析包失败问题
- **双重签名机制 (v1 JAR + v2 APK Signature)**：
  - 上游原版通常仅打 v2 签名，导致 Android 4.4 / 6.0 老车机安装时直接提示“无法解析安装包”。
  - 本项目采用生产签名证书，同时生成 v1 与 v2 签名，满足旧版 Android 的签名格式要求。具体安装和覆盖升级结果见专项验证记录。

### 3. 全架构 CPU 支持 (包含 32 位老车机芯片)
- **原生编译 4 种 CPU 架构**：包括 `armeabi-v7a` (32位 ARM)、`x86` (32位 Intel)、`arm64-v8a`、`x86_64`。
- **提供 32 位车机所需的 ARMv7 原生库**。这解决缺少该架构库的问题，不代表 T3 / AC8227L / 展讯 / 联发科所有固件的 USB、蓝牙和解码器均已实测。

### 4. 内置离线 MFi 认证证书
- 独立安装包构建会带入本地私有认证材料，无需配置外部认证服务器。该身份是实验性的，本地签名成功并不能保证 iPhone 接受，也不是 Apple 认证。

### 5. USB 底层连接优化与驱动冲突解决
- **自动驱动脱钩 (解决 `iPhone USB configuration is busy` / errno 16)**：底层 C 语言驱动层在切换 Configuration 6 遇到 `EBUSY` 时，自动探测并执行 `USBDEVFS_DISCONNECT` 解绑内核冲突驱动，并自动重试。
- **全志 T3 等芯片专属单 fd 共享**：USBMUX 与 NCM 数据通道共用已授权的底层设备描述符，避免二次 `openDevice` 导致内核抛出 `ENOENT` / `EBUSY`。

---

## 安装与升级重要说明

> [!IMPORTANT]
> **必须先卸载旧版本！**
> 
> 由于 Android 系统的安全规范，不同作者构建的 APK 签名证书各不相同：
> 1. 上游原版、第三方修改版与本项目的 **Release 签名私钥不同**。
> 2. 如果您的车机上之前安装过其他版本的 DiPlay，直接安装本项目 APK 会被系统提示 **“签名冲突 / 无法安装 / 与已安装应用签名不一致”**。
> 3. **解决方法**：请在车机【应用管理】中**完全卸载旧版 DiPlay**，然后再安装本项目的最新 Release 安装包。以后升级本项目的后续版本可直接覆盖安装。

---

## 连接使用与排查建议

### 有线 (USB) 连接
1. **排查原厂投屏软件占用**：
   - 很多老款车机（如全志 T3、亿连车机）出厂自带“亿连”、“CarLife”、“Zlink”等后台守护进程。这些程序开机后会抢先霸占 iPhone 的 USB 接口。
   - 建议在车机【设置】→【应用管理】中，将原厂的“亿连”或“CarLife”点击**【强行停止】**（或关闭其自启动权限）。
2. **连接步骤**：
   - 拔下 iPhone 数据线；
   - 打开 DiPlay 进入等待连接界面；
   - 将 iPhone 插入车机主 USB 数据口（支持互联的数据口，非仅供电口）；
   - iPhone 弹出“信任此电脑”时点击**【信任】**。

### 无线连接
- 支持 车机热点（Car Hotspot）、Wi-Fi Direct 以及 局域网（Same LAN）三种模式。
- 在部分不支持蓝牙 BLE Scanning 的老车机上，可在设置中手动填入车机蓝牙 MAC 地址以完成握手配对。

---

## 硬件适配与安装包推荐

| 安装包类型 | 适用设备 | 说明 |
| :--- | :--- | :--- |
| **通用版 (Universal)** | 各类车机均可使用 | 内置全架构 so，适合不清楚芯片架构的用户 |
| **ARMv7 专版 (armeabi-v7a)** | 全志 T3、AC8227L、展讯等 32 位老车机 | 体积精简 60% 以上，降低 4.4 系统 Dalvik 内存开销 |
| **x86 专版** | Intel 凌动 / x86 架构 Android 车机 | 解决 Issue #18 反馈的 x86 车机兼容 |

---

## 问题反馈与交流讨论

- **Issues 讨论**：如果遇到任何问题，欢迎在 [Issues](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/issues) 中发帖讨论，作者有时间就会回复解答。
- **提供日志**：遇到具体问题时，**最好贴出详细的运行日志或诊断报告**，以便快速排查定位问题：
  1. 打开 DiPlay【设置】→【诊断】→【保存诊断报告】（v0.2.27 起已支持自动同步写入插上的 U 盘及 SD 卡根目录 `/mnt/sdcard/DiPlay/`）。
  2. 将导出的 `.txt` 诊断报告或相关截图/崩溃信息附在 Issue 描述中。
  3. 注明车机芯片型号、实际 Android 系统版本、iPhone 型号及 iOS 版本。
- **欢迎提交 PR**：老车机硬件型号繁多、ROM 深度定制，欢迎有能力的开发者提交 Pull Request (PR) 共同完善兼容支持！
- ⭐ **点赞支持**：如果这个项目对你的老车机有所帮助，请顺手帮我点个 **Star**，感谢大家的支持与鼓励！

---

## 开源协议与鸣谢

- 基于 [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) 与 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 开发。
- UI 交互衍生自 [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0)。
- CarPlay 为 Apple Inc. 的注册商标，本项目仅供技术学习与车载兼容性研究。
