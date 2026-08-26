# 在线发现 Phase 3：安全下载、导入与阅读验证记录

日期：2026-08-26  
分支：`master`  
版本：`1.11.260826`（versionCode 12）

## 1. 本阶段完成内容

- 只允许已标记为公共领域或免费全文的 HTTPS EPUB、TXT 和可提取文字 PDF 进入导入链路。
- 对初始地址、每次重定向和最终地址重新执行协议、主机、端口、DNS 与私网边界校验。
- 下载器采用 10/30/120 秒超时、3 次重定向上限和 100 MiB 硬上限，先写入应用私有随机 `.part` 文件。
- 在导入前校验 EPUB/PDF/TXT 实际内容；取消、失败和应用重启会清理半成品。
- 复用现有 `BookImportService` 和私有书库，使用不包地址的稳定索引处理重复导入和并发。
- 详情页提供“加入书架”、“开始阅读”、确定/不确定进度、取消、安全错误与“已加入书架”状态。
- 导入后传递本地 book ID 给现有首页路由，EPUB/TXT/PDF 继续使用原有阅读器和阅读进度。
- 不合规、不可直接阅读或版权未明的条目不显示导入动作，仅保留可信来源页。

## 2. 自动化验证证据

在版本号升级后执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

结果：

- Gradle：`BUILD SUCCESSFUL in 4m 28s`。
- JVM 测试：533，通过 533，失败 0，错误 0，跳过 0（73 个测试套件）。
- Lint：错误 0，警告 149，其他提示 5；未增加 Lint baseline。
- 私有书库原生校验：`private_book_store_native_validation=PASS`。
- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`
  - 大小：126,027,586 字节
  - SHA-256：`5235E17E61636A4BF5C2389460354416A8464487D451BB98F13ADB2103F50025`
- 测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - 大小：3,772,709 字节
  - SHA-256：`13A2007D2B4AB8BCFFF109DD2F3B02772453670BCAD43C9DC9616E2F22EA64DE`
- APK 元数据：包名 `com.air5005.pagenest`，versionCode 12，versionName `1.11.260826`，minSdk 29，targetSdk 36。

新增覆盖包括地址策略、DNS rebinding 边界、重定向、下载上限、文件验证、取消清理、重复/并发导入、ViewModel 状态机和阅读器交接。网络测试使用可控响应，不依赖实时公网结果。

## 3. 设备验证状态

`adb devices -l` 未发现已连接设备，因此：

- API 36 模拟器的发现页 Compose 测试和固定样本 TXT 下载→导入→打开验收：`NOT RUN (no connected device)`。
- HyperOS 3 ARM64 的 EPUB/TXT/PDF、弱网取消和后台语音联合矩阵：`PENDING (target device not connected)`。
- Compose 设备测试已成功编译进测试 APK，但不将编译结果表述为模拟器或 HyperOS 3 真机通过。

## 4. GitHub Release 归档

- 发布源提交：`e77e8b486d64d8d4bd988df74f8bb312d4490623`。
- Release：`pagenest-v1.11.260826`。
- 发布页：<https://github.com/air5005/pagenest/releases/tag/pagenest-v1.11.260826>。
- GitHub Actions：<https://github.com/air5005/pagenest/actions/runs/32915266557>，build（7 分 35 秒）和 release（13 秒）均成功。
- 远程 APK：`PageNest-pagenest-v1.11.260826-debug.apk`，126,027,498 字节。
- 远程 SHA-256：`1dfcef40087439dda1201970c84d364e0d7038a73b2536518a5919db58e82d99`。
- 下载后实算摘要与远程 `SHA256SUMS.txt` 完全一致，GitHub 资产 `digest` 也一致。
- `SHA256SUMS.txt` 大小 107 字节，GitHub 资产 SHA-256 为 `30a8c09ee20fdc9c329b28b882a8c4be4da922aa40bf5b95eeaa560410133de7`。
- 远程 APK 包内元数据确认为 `com.air5005.pagenest`、versionCode 12、versionName `1.11.260826`、minSdk 29、targetSdk 36。
- Release 为正式非草稿、非预发布版本。

本机与 GitHub Actions 使用不同构建环境，调试签名 APK 的字节大小及摘要可以不同；对外归档以 Release 内 APK 与同一 Release 的 `SHA256SUMS.txt` 匹配为验收依据。

## 5. 下一续接入口

1. 连接目标 HyperOS 3 ARM64 手机，先运行 `tools/hyperos3-device-preflight.ps1`。
2. 验收真实 EPUB、TXT 和可提取文字 PDF 的在线加入书架、打开、进度保存与重复导入。
3. 在弱网、中途取消、切换后台和应用重启后确认无 `.part` 残留。
4. 联合验收后台语音、锁屏控制、音频焦点与 60 分钟运行矩阵。

设计与执行依据：

- `docs/superpowers/specs/2026-08-26-pagenest-online-discovery-phase3-design.md`
- `docs/superpowers/plans/2026-08-26-pagenest-online-discovery-phase3-secure-import.md`
