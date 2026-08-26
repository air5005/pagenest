# PageNest 1.12.260826 日志中心与实机缺陷修复验证

## 交付内容

- “我的”系统配置新增“运行与错误日志”入口，支持全部、运行、告警、错误筛选、刷新与确认清空。
- 日志仅保存在应用私有目录；单文件 512 KiB，最多 4 个文件，总上限约 2 MiB；界面最多读取最近 500 条。
- 相同级别、类别和消息在 10 秒内去重；不会在下载进度、阅读翻页、Compose 重组或音频帧循环内写日志。
- API Key、Authorization/Bearer、URL 查询与片段、私有绝对路径会脱敏；单条消息和异常栈均有长度限制。
- 关键边界类别包括 `APP_START`、`LIBRARY`、`BOOK_IMPORT`、`ONLINE_IMPORT`、`DISCOVERY`、`READER_ROUTE`、`SPEECH_SESSION`、`SPEECH_SERVICE` 和 `CRASH`。
- 修复 HyperOS 3 实机反馈：在线发现提供可见搜索按钮和键盘搜索动作；启动不再自动重扫保存目录；批量导入只显示有界汇总并可关闭、自动消失。

## 自动化门禁

- `:base:testDebugUnitTest`：22 项通过。
- `:app:testDebugUnitTest`：540 项通过。
- `:app:assembleDebug` 与 `:app:assembleDebugAndroidTest`：通过。
- `:app:lintDebug`：0 error，157 warning，另有 5 条 informational 项；全部为既有兼容性/本地化提示，无发布阻断错误。
- 本地 APK：126,185,582 bytes。
- 本地 SHA-256：`b11f1a780e28304e4fed385c8c17f8e8df4242b515e5f051ddb6651eac462bee`。
- 包名：`com.air5005.pagenest`；versionCode `13`；versionName `1.12.260826`；minSdk `29`；targetSdk `36`。
- 完整测试套件暴露既有 TTS 并发测试的固定 5 秒初始化等待在高负载下不稳定；单类复现通过后，将监听器等待改为带诊断文字的 30 秒有界等待，完整 540 项随后通过。生产 TTS 逻辑未改动。

## 设备验证状态

本轮代码修复来自用户提供的 HyperOS 3 实机截图。发布门禁时 `adb devices -l` 无连接设备，因此新 APK 的安装与三项缺陷复测仍为待验证，不记录为真机通过。

## 远端归档

待 GitHub Release `pagenest-v1.12.260826` 工作流完成后补充远端资产摘要与下载校验结果。
