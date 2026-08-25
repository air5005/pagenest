# UI Refresh Phase 3：沉浸式阅读器验收记录

## 交付范围

- 阅读正文默认沉浸显示，正文中央点击可显示或隐藏控制区。
- 控制区无阻塞弹层时会在 4 秒后自动隐藏，并通过交互代次避免旧定时器误隐藏新操作。
- 顶部工具栏与底部操作坞统一提供返回、书签、更多、目录、进度、听书和显示入口。
- 进度面板支持上一页、下一页与滑动定位。
- 朗读会话运行时显示迷你播放器，可展开完整朗读面板；完整面板支持主动收起。
- 系统状态栏和导航栏跟随阅读控制区显示状态。
- 新增阅读器文案已覆盖默认、中文、德语、西班牙语、法语、葡萄牙语、日语、俄语、阿拉伯语和印地语资源。

本阶段继续使用现有 `PageView`、解析器、阅读位置保存和后台朗读服务，没有替换正文渲染内核，也没有把 PDF 阅读器强行合并到可重排阅读器中。

## TDD 证据

| 任务 | RED | GREEN | 提交 |
| --- | --- | --- | --- |
| 阅读控制状态机 | 类型尚不存在，测试无法编译 | reducer 聚焦测试 4/4，通过后补充显式可见性契约至 5/5 | `9c7b0e8` |
| ViewModel 协调 | `ControlsVisibilityChanged` 不存在 | 状态统一由 reducer 驱动，聚焦测试 5/5 | `9eb6ec3` |
| 无状态 Compose 控制区 | `ReaderChrome` 不存在 | API 36 上 `ReaderChromeTest` 4/4 | `b7b7550` |
| 阅读器接线与面板收起 | `SpeechControlSheet` 没有关闭回调 | 两组设备测试合计 5/5 | `4912924` |
| 多语言资源 | Lint 报告 17 个缺失翻译错误 | Lint 为 0 errors、148 warnings | `3964ac4` |

## 发布前验证

验证环境：Windows / PowerShell、Microsoft OpenJDK 17、Gradle 8.11.1、Android API 36 模拟器 `pagenest_api36`。

- `:app:testDebugUnitTest`：424 tests，0 failures，0 errors，0 skipped。
- `:app:assembleDebug`：成功。
- `:app:assembleDebugAndroidTest`：成功。
- `:app:lintDebug`：0 errors，148 warnings；警告为项目既有建议项，不阻止发布。
- `ReaderChromeTest` 与 `SpeechControlSheetDismissTest`：API 36 模拟器 5/5 通过。
- 新安装调试包后首页可正常启动，AndroidRuntime fatal 计数为 0。

## 已知限制

全新 API 36 模拟器上尝试导入临时 TXT、手工 EPUB2/EPUB3 以及 W3C/IDPF `wasteland.epub` 时，现有导入引擎均提示无法解析或导入 0 本。因此本阶段对阅读控制区完成了隔离 Compose 测试和整套编译接线验证，但没有把“从全新模拟器导入公开样书并进入真实正文”记录为通过。

该问题不由本阶段的新控制区代码引入，但会阻碍真实阅读路径验收，已列为下一阶段首要任务：先建立可重复导入样书，再完成真实正文与 HyperOS 3 真机验收。

## 发布归档

计划版本：`PageNest 1.8.260825`，标签 `pagenest-v1.8.260825`。GitHub Release、APK 与 `SHA256SUMS.txt` 的远端核验将在版本任务完成后补记。
