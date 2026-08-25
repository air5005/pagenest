# PageNest 导入兼容性与真实阅读验收（Phase 4）

日期：2026-08-25  
设备：`pagenest_api36`，Android API 36，x86_64  
分支：`master`

## 结论

- TXT 从目录授权、扫描、复制、解析、入库、书架展示到正文阅读的真实链路通过。
- TXT 书名使用原始文件名 `manual-reader-sample`，不再显示私有存储的 SHA-256 文件名。
- 阅读正文可显示；中央点击可唤出顶部工具栏和“目录 / 进度 / 听书 / 显示”底部入口；无操作 5 秒后自动隐藏。
- 阅读过程 AndroidRuntime 致命崩溃数为 0。
- Phase 3 的两个 Compose 设备测试套件复跑 5/5 通过。
- EPUB 在该 x86_64 模拟器仍无法解析，因为旧阅读框架的 EPUB 元数据解析本身依赖当前 APK 未提供的 `libappmobi.so`。这不是 CRC 降级函数可以解决的问题，也不在本阶段重写 EPUB 引擎。
- 目标小米 HyperOS 3 为 ARM64 路径；EPUB、后台语音和长时间运行仍需在目标真机单独验收，不能用本次模拟器结果替代。

## 根因与 TDD 修复

首次导入日志显示，`FileParserImpl` 在每种格式解析结束后都无条件调用 `MobiParser.getFileCrc()`。x86_64 缺少 `libappmobi.so` 时抛出 `UnsatisfiedLinkError`，已解析的 TXT 也被导入服务归类为解析失败。

按 RED-GREEN 流程新增 `BestEffortBookCrcTest`，覆盖：

- 有效 CRC 原值保留；
- 空值降级为 0；
- 普通异常降级为 0；
- `UnsatisfiedLinkError` 降级为 0；
- 其他致命 `Error` 继续抛出。

聚焦测试 5/5 通过后，将 `DocumentFile` 和 `CachedFile` 两条解析路径接入同一边界。随后另一个 RED 测试复现 TXT 书名被私有哈希替换的问题，最小实现仅对 TXT 使用源文件显示名，不改变其他格式的解析元数据。

## 真实设备路径

测试目录 `/sdcard/Download/PageNestTest` 包含 1 本 UTF-8 TXT 与 3 本 EPUB。清空应用数据、重新安装当前 APK 并重新授予目录权限后：

- 页面反馈：`已导入《manual-reader-sample》`、`Added 1 books(s)`；
- 书架计数：`全部书籍 1`；
- 书架卡片与阅读器标题：`manual-reader-sample`；
- 正文首屏：`PageNest 沉浸式阅读测试`；
- EPUB：3 本均明确反馈“无法解析这本书”，日志定位到 `EpubParser.getEpubInfo -> NativeLib.loadEpub`。

阅读器控制区截图保存在本地忽略目录 `build/captures/phase4/reader-controls-final.png`，用于人工视觉检查，不作为源代码提交。画面确认正确书名、中文正文、圆角顶部工具栏及四入口底部工具栏。

## 自动化结果

```text
ReaderChromeTest + SpeechControlSheetDismissTest
Finished 5 tests on pagenest_api36(AVD) - 16
BUILD SUCCESSFUL
controls_visible_after_5s=False
fatal_count=0
```

## 发布门禁与归档

版本：`versionCode 10` / `versionName 1.9.260825`

发布源代码：`a60fad3ed4e35354c5c42dd1e46c531165fcebf4`

标签：`pagenest-v1.9.260825`

发布前与版本变更后的精确发布树均执行完整门禁。最终发布树结果：

```text
app JVM tests:          425/425 passed
bookparser JVM tests:     6/6 passed
API 36 device tests:      5/5 passed
Lint:                     0 errors, 148 warnings
assembleDebug:            passed
assembleDebugAndroidTest: passed
APK metadata: versionCode 10, versionName 1.9.260825, targetSdk 36
```

GitHub Actions `Archive Android APK Release` 运行 `32862641105` 成功，生成正式 GitHub Release：

- Release：`https://github.com/air5005/pagenest/releases/tag/pagenest-v1.9.260825`
- APK：`PageNest-pagenest-v1.9.260825-debug.apk`
- 远端大小：`125273858` bytes
- GitHub 资产摘要：`f79902553accfdf1267785ff39a7308d5dba08a6bf4bdababa6974769e2d5fdf`
- `SHA256SUMS.txt` 中的 APK 摘要与 GitHub 资产摘要完全一致。
- `SHA256SUMS.txt` 自身的 SHA-256 为 `aa6191ff9b5dbaad979715dff6ce8d7d616eb24e9dd1a96d360a8bb2ad0ddf79`，同样与 GitHub 资产元数据一致。
