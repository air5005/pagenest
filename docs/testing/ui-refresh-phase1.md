# PageNest UI Refresh Phase 1 验收记录

验收日期：2026-08-25（Asia/Shanghai）  
代码提交：`f7842a178a9285cec5c2a10dfbe45f0c6ca0ae49`  
分支：`master`

## 自动化门禁

执行命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`。

- 单元测试：410，通过 410，失败 0，错误 0，跳过 0。
- Compose 设备测试：`GettingStartedContentTest` 在 API 36 模拟器通过 1/1。
- Lint：错误 0，警告 148。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
- Debug APK SHA-256：`B96EFD566D57F1E2989172430BACBD9AB41ABF1C61BCA517ED94B5031EF0B864`。

## 模拟器覆盖

设备：`pagenest_api36`  
型号：`sdk_gphone64_x86_64`  
系统：Android 16 / API 36  
ABI：`x86_64`

| 场景 | 结果 |
| --- | --- |
| 全新数据启动显示页栖图标和中文引导 | 通过 |
| “选择书籍目录”显示确认对话框并打开系统文件选择器 | 通过 |
| 从系统文件选择器返回 PageNest | 通过 |
| “暂时跳过”进入空书架首页 | 通过 |
| 强制停止后重启保持在首页 | 通过 |
| 浅色模式文字和控件可读 | 通过 |
| 深色模式文字和控件可读 | 通过 |
| 跳过、重启及主题切换后 PageNest 致命异常 | 0 |

本地截图（`captures/` 已被 Git 忽略）：

- `captures/ui-refresh-phase1/onboarding-light.png`
- `captures/ui-refresh-phase1/home-light.png`
- `captures/ui-refresh-phase1/home-dark.png`

模拟器一次无快照冷启动时出现 Android System UI 未响应对话框；选择等待后系统恢复，PageNest 始终保持前台且应用日志无致命异常。该现象归类为模拟器环境问题，不计为应用失败。

## 原始崩溃回归

x86_64 包不包含 MOBI 解析器依赖的 `libappmobi.so`。以前首次跳过后，可选的 Alice 示例书解析会抛出 `UnsatisfiedLinkError` 并导致首页退出。

本阶段将可选示例书导入包裹在窄故障边界中：

- `LinkageError` 与普通解析异常转换为可恢复失败；
- `CancellationException` 继续向上传播；
- 不捕获任意 `Throwable`；
- 空书架和正常导入入口仍可使用。

API 36 x86_64 复验中，首次跳过、强制停止和重新启动后 `MainActivity` 均保持前台，新的 `AndroidRuntime` 致命异常数量为 0。

## 限制与后续

- MOBI 原生解析目前仅随 ARM64/ARMv7 架构交付；x86_64 模拟器只验证安全降级，不验证 MOBI 正常解析。
- 小米 HyperOS 3 真机安装、后台语音阅读和 60 分钟矩阵仍需目标手机补证。
- 下一开发入口：**UI Refresh Phase 2：阅读仪表盘首页**。
- 当前版本具备发布资格：新图标和中文引导已安装验证，API 36 首页崩溃回归已修复。
