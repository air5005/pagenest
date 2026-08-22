# 页栖开发环境指南

本文档说明在 Windows 上开发、构建和调试页栖 Android 应用所需的软件、官方下载地址、安装步骤和设备连接方法。

## 1. 当前开发基线

以下版本已于 2026-08-22 在本机安装并验证：

| 组件 | 版本 | 安装位置 |
| --- | --- | --- |
| Android Studio | 2026.1.3.7（Quail 3） | `C:\Program Files\Android\Android Studio` |
| JDK | Microsoft OpenJDK 17.0.20.1 | `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` |
| Android SDK Command-line Tools | latest（15859902） | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| Android SDK Platform | Android 36 | `%LOCALAPPDATA%\Android\Sdk\platforms\android-36` |
| Android SDK Build Tools | 36.0.0 | `%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0` |
| Android SDK Platform Tools / ADB | 37.0.1 | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| Android NDK | 29.0.13599879 | `%LOCALAPPDATA%\Android\Sdk\ndk\29.0.13599879` |
| CMake | 3.22.1 | `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1` |
| Git | 已安装 | 由系统 Git 提供 |

项目使用 Gradle Wrapper 固定 Gradle 版本，因此不需要安装全局 Gradle。应用工程建立后，应使用仓库中的 `gradlew.bat` 执行构建和测试。

## 2. 必需软件与官方下载地址

### Android Studio

- 官方下载页：<https://developer.android.com/studio>
- Windows 安装说明：<https://developer.android.com/studio/install>
- Winget 包：`Google.AndroidStudio`

推荐使用最新稳定版，不使用 Canary 或 Beta 版本作为项目默认开发环境。

### Android SDK Command-line Tools

- 官方下载页：<https://developer.android.com/studio#command-tools>
- Windows 当前安装包：<https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip>
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

### 3.1 安装 Android Studio

在 PowerShell 中执行：

```powershell
winget install --id Google.AndroidStudio --exact `
  --silent `
  --accept-package-agreements `
  --accept-source-agreements `
  --disable-interactivity
```

验证安装：

```powershell
winget list --id Google.AndroidStudio --exact
Test-Path 'C:\Program Files\Android\Android Studio\bin\studio64.exe'
```

本项目固定的 Gradle 8.11.1 需使用 JDK 17。Android Studio 2026.1.3.7 自带的 JBR 25.0.2 无法解析当前 Kotlin DSL 构建脚本，因此命令行构建请将 `JAVA_HOME` 指向上表中的 Microsoft OpenJDK 17。

安装项目 JDK：

```powershell
winget install --id Microsoft.OpenJDK.17 --exact `
  --silent `
  --accept-package-agreements `
  --accept-source-agreements `
  --disable-interactivity
```

### 3.2 安装 Android SDK

优先使用 Android Studio：

1. 打开 Android Studio。
2. 进入 `Tools > SDK Manager`。
3. 在 `SDK Platforms` 中安装 Android 36。
4. 在 `SDK Tools` 中安装 Android SDK Build-Tools、Android SDK Platform-Tools 和 Android SDK Command-line Tools。

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
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$sdkManager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"

& $sdkManager --sdk_root=$sdkRoot --licenses
& $sdkManager --sdk_root=$sdkRoot `
  'platform-tools' `
  'platforms;android-36' `
  'build-tools;36.0.0' `
  'ndk;29.0.13599879' `
  'cmake;3.22.1'
```

`sdkmanager` 在当前工具包中会提示逐步迁移到 Android CLI；本项目仍保留上述兼容命令，同时优先通过 Android Studio SDK Manager 管理组件。

### 3.3 配置环境变量

当前用户需要以下变量：

```text
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk
JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
```

将下列目录加入当前用户的 `Path`：

```text
C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin
%LOCALAPPDATA%\Android\Sdk\platform-tools
%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
```

修改后应重新打开 PowerShell、终端和 Android Studio，使新环境变量生效。

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
build-tools;36.0.0
cmake;3.22.1
ndk;29.0.13599879
platform-tools
platforms;android-36
```

项目工程创建后，再执行：

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

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

### 无线调试

Android 11 及以上设备可以使用无线调试：

1. 手机和电脑连接同一局域网。
2. 在开发者选项中打开“无线调试”。
3. 在 Android Studio 的 Device Manager 中选择 `Pair Devices Using Wi-Fi`。
4. 使用二维码或配对码完成连接。

详细步骤以 Android 官方真机指南为准：<https://developer.android.com/studio/run/device>。

## 6. Android 模拟器

模拟器是可选组件。本机当前检测到 BIOS/固件虚拟化未启用，因此暂不安装 Emulator 和 AVD 系统镜像。

如需使用模拟器：

1. 在 BIOS 中开启 Intel VT-x 或 AMD-V。
2. 确认 Windows 虚拟化组件可用。
3. 通过 Android Studio 的 SDK Manager 安装 Android Emulator。
4. 通过 Device Manager 创建 AVD。

在发布前仍应至少使用一台真实的小米 HyperOS 3 设备完成兼容性测试。

## 7. TDD 开发流程

页栖采用 RED → GREEN → REFACTOR：

1. 先编写能够描述行为的失败测试，并确认测试因缺少功能而失败。
2. 编写让测试通过的最小实现。
3. 运行相关测试和完整测试集。
4. 在测试保护下重构。
5. 提交前运行单元测试、Lint 和 Debug 构建。

功能代码不得先于对应测试提交。UI 自动化测试仅覆盖关键用户路径，大部分业务规则应放在无需 Android 设备即可运行的 JVM 单元测试中。

## 8. 暂不需要的软件

- 全局 Gradle：使用项目的 Gradle Wrapper。
- 数据库服务器或后端服务：首版是完整离线阅读器。
- Docker：首版开发不需要。
