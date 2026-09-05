# 羿巢阅读（YiNest）开发环境指南

本文档说明在 Windows 上开发、构建和调试羿巢阅读 Android 应用所需的软件、安装步骤和设备连接方法。`PageNestDev` 工具目录、`PageNest_API_36` 模拟器名及 `com.air5005.pagenest` 包名为现有兼容标识，不是对外品牌名称。

## 1. 当前开发基线

以下是 2026-09-05 本机已安装的开发环境。路径中的 `%LOCALAPPDATA%` 表示当前用户的本地应用数据目录；在 PowerShell 中使用 `$env:LOCALAPPDATA`。

| 组件 | 版本 | 安装位置 |
| --- | --- | --- |
| Android Studio | 2026.1.4.7（Quail 4） | `%LOCALAPPDATA%\Programs\PageNestDev\android-studio` |
| JDK | Microsoft OpenJDK 17.0.20.1 | `%LOCALAPPDATA%\Programs\PageNestDev\jdk-17.0.20.1+1` |
| Android SDK Command-line Tools | 22.0（安装包 15859902） | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| Android SDK Platform | Android 36 | `%LOCALAPPDATA%\Android\Sdk\platforms\android-36` |
| Android SDK Build Tools | 35.0.0、36.0.0 | `%LOCALAPPDATA%\Android\Sdk\build-tools` |
| Android SDK Platform Tools / ADB | 37.0.1 | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| Android NDK | 27.0.12077973、29.0.13599879（r29-beta2） | `%LOCALAPPDATA%\Android\Sdk\ndk` |
| CMake | 3.22.1 | `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1` |
| Android Emulator | 37.1.11 | `%LOCALAPPDATA%\Android\Sdk\emulator` |
| AVD 系统镜像 | API 36 / Google APIs / x86_64 | `%LOCALAPPDATA%\Android\Sdk\system-images\android-36\google_apis\x86_64` |
| Git | 已安装 | 由系统 Git 提供 |

项目通过 Gradle Wrapper 固定 Gradle 8.11.1，无需安装全局 Gradle；使用仓库中的 `gradlew.bat` 构建和测试。两个 NDK 版本均需保留：不同原生模块使用不同版本，29.0.13599879 是项目固定的 beta2 版本，不应描述为稳定版或随意替换。

## 2. 必需软件与官方下载地址

### Android Studio

- 官方下载页：<https://developer.android.com/studio>
- Windows 安装说明：<https://developer.android.com/studio/install>
- Winget 包：`Google.AndroidStudio`

本机已使用官方 ZIP 包完成用户级安装。复现本机环境时使用上表版本；升级 IDE、SDK 或 NDK 前应单独验证项目兼容性。

### JDK 17

- Microsoft OpenJDK 官方下载页：<https://learn.microsoft.com/en-us/java/openjdk/download>
- Winget 包：`Microsoft.OpenJDK.17`
- 本机使用官方 Windows x64 ZIP 包，解压到上表中的用户级目录。

### Android SDK Command-line Tools

- 官方下载页：<https://developer.android.com/studio#command-tools>
- 本机使用的 Windows 安装包：<https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip>
- SHA-256：`90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`
- 命令行工具说明：<https://developer.android.com/tools/sdkmanager>

### Platform Tools / ADB

- 发布说明与下载：<https://developer.android.com/tools/releases/platform-tools>
- ADB 使用说明：<https://developer.android.com/tools/adb>

### 小米真机 USB 驱动（按需安装）

Windows 无法通过 ADB 识别小米设备时，再安装对应设备的 OEM USB 驱动：

- Android 官方 OEM 驱动索引：<https://developer.android.com/studio/run/oem-usb>
- Android 真机调试指南：<https://developer.android.com/studio/run/device>

不要从非官方软件下载站下载 Android Studio、SDK 或 USB 驱动。

## 3. Windows 安装流程

### 3.1 安装 Android Studio 与 JDK 17

本机已完成安装，继续开发无需重复安装。先检查现有工具：

```powershell
Test-Path "$env:LOCALAPPDATA\Programs\PageNestDev\android-studio\bin\studio64.exe"
& "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1\bin\java.exe" -version
```

在其他 Windows 电脑上，可以使用官方 ZIP 包做用户级安装，也可以使用 Winget。先验证 Winget：

```powershell
winget.exe --version
# 若当前进程的 Path 未包含 WindowsApps，尝试完整路径：
& "$env:LOCALAPPDATA\Microsoft\WindowsApps\winget.exe" --version
```

本机 Winget 版本已验证为 `1.29.290`。命令别名在某个终端不可见，不代表 Winget 未安装；新开终端或使用上述完整路径即可进一步确认。

采用 Winget 安装时，可以执行：

```powershell
winget install --id Google.AndroidStudio --exact
winget install --id Microsoft.OpenJDK.17 --exact
```

这些包的安装器可能要求管理员权限，且安装路径与上表的用户级 ZIP 安装不同。若出现权限错误，可改用官方 ZIP 安装；ZIP 安装通常不会出现在 `winget list` 中。上述未指定版本的 Winget 命令会选择其源提供的版本，执行前应核对是否符合项目基线。

构建使用 JDK 17：命令行 `JAVA_HOME` 指向实际安装的 JDK 17 目录；Android Studio 中也将项目的 Gradle JDK 设为同一目录，避免自动采用 IDE 自带的其他 Java 版本。

### 3.2 安装 Android SDK

优先使用 Android Studio：

1. 打开 Android Studio。
2. 进入 `Tools > SDK Manager`。
3. 在 `SDK Platforms` 中安装 Android 36。
4. 在 `SDK Tools` 中勾选 `Show Package Details`，按上表安装 Build Tools、Platform Tools、Command-line Tools、两个 NDK 版本和 CMake。需要模拟器时另装 Emulator 与对应系统镜像。

也可以使用命令行工具。将下载包解压后整理成以下结构：

```text
%LOCALAPPDATA%\Android\Sdk\
└── cmdline-tools\
    └── latest\
        ├── bin\
        ├── lib\
        ├── NOTICE.txt
        └── source.properties
```

然后安装 SDK 组件：

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1"
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$sdkManager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"

& $sdkManager --sdk_root=$sdkRoot --licenses
& $sdkManager --sdk_root=$sdkRoot `
  'platform-tools' `
  'platforms;android-36' `
  'build-tools;35.0.0' `
  'build-tools;36.0.0' `
  'ndk;27.0.12077973' `
  'ndk;29.0.13599879' `
  'cmake;3.22.1'
```

`sdkmanager` 在当前工具包中会提示逐步迁移到 Android CLI；本项目仍保留上述兼容命令，同时优先通过 Android Studio SDK Manager 管理组件。

### 3.3 配置环境变量

当前用户需要以下变量：

```text
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk
JAVA_HOME=%LOCALAPPDATA%\Programs\PageNestDev\jdk-17.0.20.1+1
```

将下列目录加入当前用户的 `Path`：

```text
%LOCALAPPDATA%\Programs\PageNestDev\jdk-17.0.20.1+1\bin
%LOCALAPPDATA%\Android\Sdk\platform-tools
%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin
%LOCALAPPDATA%\Programs\PageNestDev\android-studio\bin
```

在 Windows 用户环境变量设置中填写上述目录的实际展开路径。本机已经配置这些用户变量；已有终端不会自动刷新。修改后应重新打开 PowerShell、终端和 Android Studio，使新环境变量生效。需要在当前 PowerShell 立即构建时，可以临时设置：

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
```

### 3.4 可选 Release 签名

Debug 构建始终使用 Android 默认调试密钥，不需要私有配置。需要生成已签名的 Release 包时，在仓库根目录创建已被 `.gitignore` 排除的 `key.properties`：

```properties
storeFile=C:\\path\\to\\release.keystore
storePassword=replace-with-local-secret
keyAlias=release
keyPassword=replace-with-local-secret
```

没有该文件时仍可配置和构建 Debug 变体；Release 签名配置只在文件存在时加载。

## 4. 安装验证

重新打开 PowerShell 后执行：

```powershell
java -version
adb version
sdkmanager --list_installed
```

预期至少能看到：

```text
build-tools;35.0.0
build-tools;36.0.0
cmake;3.22.1
ndk;27.0.12077973
ndk;29.0.13599879
platform-tools
platforms;android-36
```

在仓库根目录执行：

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

确认 Gradle 显示 8.11.1、构建 JVM 使用 JDK 17。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，单元测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`，Lint 报告位于 `app/build/reports/lint-results-debug.html`。以当前代码实际生成的报告为准，不把一次历史构建结果当作后续修改的验收结果。

JPEG 2000 像素缓冲区尺寸检查另有独立的 Windows 原生回归测试，使用已安装的 NDK 编译器，不需要启动模拟器。修改该检查时还需运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\jp2forandroid\src\test\cpp\run_pixel_buffer_size_test.ps1 `
  -ProjectDirectory "$PWD\jp2forandroid" `
  -OutputDirectory "$PWD\jp2forandroid\build\pixel-buffer-size-tests"
```

该测试覆盖正常尺寸、零/负尺寸、32 位内存分配和 JNI 数组长度边界；它不替代 Android 各 ABI 编译或实际 JPEG 2000 图像解码验收。

## 5. 连接小米 HyperOS 3 真机

### USB 调试

1. 在手机系统设置中启用开发者选项。
2. 打开开发者选项中的“USB 调试”。部分小米设备还需要打开“USB 调试（安全设置）”。
3. 使用支持数据传输的 USB 线连接电脑。
4. 手机出现 RSA 指纹授权提示时，确认允许当前电脑调试。
5. 在电脑执行：

```powershell
adb kill-server
adb start-server
adb devices
```

设备状态应显示为 `device`。如果显示 `unauthorized`，请解锁手机并确认 RSA 授权；如果没有设备，检查 USB 模式、数据线、接口及小米 OEM 驱动。

### HyperOS 3 真机预检

`adb devices -l` 可能同时列出模拟器和手机。复制目标手机第一列的序列号，显式运行预检：

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
.\tools\hyperos3-device-preflight.ps1 -Serial '<目标手机序列号>' -AdbPath $adb
```

只有输出 `preflight_passed=True` 才能继续安装和真机测试。预检会拒绝未授权/离线设备、模拟器、非 Android 16 / API 36、非 ARM64、小米厂商或 HyperOS 3 标识不符的设备。证据默认保存在 Windows 临时目录，输出中的 `evidence_root` 是具体位置；不要将包含设备序列号和构建指纹的证据直接提交到公开仓库。

预检判定逻辑可在没有手机时独立回归：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\tests\HyperOs3Preflight.Tests.ps1
```

### 无线调试

Android 11 及以上设备可以使用无线调试：

1. 手机和电脑连接同一局域网。
2. 在开发者选项中打开“无线调试”。
3. 在 Android Studio 的 Device Manager 中选择 `Pair Devices Using Wi-Fi`。
4. 使用二维码或配对码完成连接。

详细步骤以 Android 官方真机指南为准：<https://developer.android.com/studio/run/device>。

## 6. Android 模拟器

本机已安装 Android Emulator 37.1.11，WHPX 硬件加速已验证可用，并已完成以下 AVD 的 Android 16 启动及 Debug APK 安装、冷启动验证：

| 项目 | 配置 |
| --- | --- |
| AVD 名称 | `PageNest_API_36` |
| 设备模板 | Pixel 6 |
| 系统镜像 | API 36 / Google APIs / x86_64 |

在 Android Studio 的 Device Manager 中启动该 AVD，或在 PowerShell 中执行：

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -accel-check
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd PageNest_API_36
```

在另一个终端检查设备并安装 APK；如果列出多个设备，使用 `adb -s <序列号>` 明确选择目标：

```powershell
adb devices -l
adb -s '<模拟器序列号>' install -r .\app\build\outputs\apk\debug\app-debug.apk
```

在其他电脑上配置模拟器时：

1. 在 BIOS 中开启 Intel VT-x 或 AMD-V。
2. 确认 Windows 虚拟化组件可用。
3. 通过 Android Studio 的 SDK Manager 安装 Android Emulator。
4. 通过 Device Manager 创建 AVD。

模拟器用于日常开发，不具备小米 HyperOS 3 系统环境。本机尚未完成目标 HyperOS 3 真机验收；发布前仍须连接符合预检要求的真实设备，完成第 9 节门禁。

## 7. TDD 开发流程

羿巢阅读采用 RED → GREEN → REFACTOR：

1. 先编写能够描述行为的失败测试，并确认测试因缺少功能而失败。
2. 编写让测试通过的最小实现。
3. 运行相关测试和完整测试集。
4. 在测试保护下重构。
5. 提交前运行单元测试、Lint 和 Debug 构建。

功能代码不得先于对应测试提交。UI 自动化测试仅覆盖关键用户路径，大部分业务规则应放在无需 Android 设备即可运行的 JVM 单元测试中。

## 8. 暂不需要的软件

- 全局 Gradle：使用项目的 Gradle Wrapper。
- 数据库服务器或自建后端服务：本地阅读数据保存在设备中；在线发现和 Azure 语音按需访问对应服务。
- Docker：首版开发不需要。

## 9. 语音阅读真机发布门禁

HyperOS 3 / Android 16 的设备准备、USB/无线调试、Azure 安全配置、自动化命令和连续 60 分钟验收矩阵统一记录在：

- [语音阅读 HyperOS 3 真机发布门禁](testing/voice-reading-hyperos3.md)

提交语音阅读阶段前，先执行桌面门禁：

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb devices -l
```

只有 `adb devices -l` 出现目标手机的 `device` 行后才能运行真机门禁：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.air5005.pagenest.speech
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wxn.reader.data.source.local.AppDatabaseMigrationTest
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

没有连接设备时必须记录 `NOT RUN (no connected device)`，不得写成测试通过。
