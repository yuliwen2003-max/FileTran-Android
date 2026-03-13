# FileTran Android

FileTran 是一个面向 Android 的多通道文件传输应用，目标是在不同网络条件下都能把文件传出去、收回来。

- 局域网可用时：走 HTTP/二维码快速分享
- 无网或弱网时：可切换 NFC、声学、AirGap（彩码）等离线方式
- 实验场景：提供 NAT/UDP/IPv6/测速等网络工具页用于排障与调优

## 亮点功能

- 多协议文件传输
  - 局域网 HTTP 分享（单文件/多文件）
  - 文本分享与二维码分享
  - 反向上传/反向推送能力（实验）

- 离线传输能力
  - NFC + HCE 文件传输
  - 声学传输（音频编码发送与录音解码接收）
  - AirGap 视觉码传输（发送/接收）
  - SSTV（Robot36）图像传输相关能力

- 设备协同与效率功能
  - 剪贴板同步（Clipboard Sync）
  - 下载历史管理
  - 已安装 APK 分享
  - 蓝牙系统分享入口

- 网络与诊断实验室
  - NAT 类型探测
  - UDP 传输实验（IPv4/IPv6）
  - NAT 打洞实验（含 NAT3/4 场景探索）
  - iPerf / LibreSpeed / SpeedTest 等测速相关页面
  - 热点调试与信息页（含 Wi-Fi 二维码）

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- NanoHTTPD（内置轻量 HTTP 服务）
- ZXing（二维码编码/识别相关）
- CameraX（扫码与视觉采集）
- OkHttp + dnsjava（网络请求与解析）
- NDK/C++（`airgap` 模块）
- OpenCV Android SDK（`airgap/third_party`）

## 运行环境

- Android `minSdk 24`
- `targetSdk 36`
- Kotlin `2.0.21`
- AGP `8.13.2`
- JDK `11`

## 项目结构

```text
FileTran/
├─ app/                    # 主应用模块（UI、传输逻辑、页面）
├─ airgap/                 # AirGap 原生与解码能力（含 C++/OpenCV）
├─ gradle/                 # 版本目录与构建配置
├─ HOTSPOT_DEBUG.md        # 热点调试说明
└─ THIRD_PARTY_NOTICES.md  # 第三方许可说明
```

## 快速开始

### 1) Android Studio

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle Sync 完成。
3. 连接真机（推荐，涉及 NFC/音频/热点等硬件能力）。
4. 运行 `app` 模块。

### 2) 命令行构建

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

## 常用权限说明

应用包含多种传输模式，因此会申请较多权限，核心包括：

- 网络/热点：`INTERNET`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`
- 媒体与文件：`READ_MEDIA_*`、`READ_EXTERNAL_STORAGE`（旧版本）
- 扫码与音频：`CAMERA`、`RECORD_AUDIO`
- 蓝牙/NFC：`BLUETOOTH_*`、`NFC`
- 前台服务：`FOREGROUND_SERVICE*`

实际授权请按你开启的功能按需授予。

## 发布建议（重要）

当前仓库包含较大的第三方二进制文件（OpenCV/AirGap 相关），推送 GitHub 时可能触发大文件限制。

建议在发布前执行以下策略之一：

- 使用 Git LFS 管理大二进制文件
- 将超大依赖改为“构建时下载”而不是直接入库
- 清理不必要的 `.tmp/`、`.cxx/`、构建产物后再提交

## 致谢

- OpenCV
- ZXing
- NanoHTTPD
- 其他依赖见 `THIRD_PARTY_NOTICES.md`
