# PageNest 续接手册（重启或换机）

本仓库直接在 `master` 开发。Online Discovery Phase 3 的安全下载、私有书库导入、一键加入书架和打开阅读器已完成。当前优先入口是 **HyperOS 3 ARM64 真机验收**；目标手机未连接时不得用电脑或 x86_64 结果替代。

## 1. 恢复工作区

```powershell
cd D:\pagenest
git checkout master
git pull origin master
git status --short
```

预期 `git status --short` 没有输出。Phase 4 发布源代码提交为：

```text
a60fad3ed4e35354c5c42dd1e46c531165fcebf4
```

Phase 4 已发布为 GitHub Release `pagenest-v1.9.260825`。完整验证与远端归档证据见 `docs/testing/import-compatibility-phase4.md`；续接时始终以最新的 `origin/master` 为准。

## 2. 已完成的 UI Refresh Phase 1–4

- PageNest 蓝绿设计令牌与主题基础。
- “阅读窗口”自适应桌面图标与页栖品牌资源。
- 中文无状态首次启动引导及设备端 Compose UI 测试。
- 可选示例书解析故障边界；x86_64 缺少 `libappmobi.so` 时首页不再崩溃。
- API 36 模拟器明暗模式、目录选择返回、跳过和重启恢复验收。
- GitHub Release `PageNest 1.6.260825` 已发布，APK 与远端校验清单一致。
- 首页默认进入“今天读书了吗”阅读仪表盘，展示真实的今日分钟、连续阅读和本周 150 分钟目标。
- 最近阅读按最后打开时间排序，最多显示 3 本非音频书籍，并沿用原有格式路由继续阅读。
- 空书架支持一键导入，全部书籍保留搜索、排序、布局、书架和选择管理。
- 阅读活动读取失败只降级统计，不阻断首页、导入和书架。
- 首页文案已补齐中文、德语、法语、西班牙语、葡萄牙语、日语、俄语、阿拉伯语和印地语资源。
- API 36 模拟器的浅色、深色、导入、全部书籍、返回和重启路径已验收。
- GitHub Release `PageNest 1.7.260825` 已发布，远端 APK 与 `SHA256SUMS.txt` 一致。
- 阅读正文默认沉浸显示，中央点击显示控制区，无弹层交互 4 秒后自动隐藏。
- 目录、进度、听书、显示、书签与更多工具已统一到新的阅读控制区。
- 后台朗读会话可显示迷你播放器并展开或收起完整控制面板。
- 阅读控制区的 424 个单元测试、5 个 API 36 设备测试、APK 构建和 Lint 发布门禁已通过。
- GitHub Release `PageNest 1.8.260825` 已发布，GitHub 资产摘要与 `SHA256SUMS.txt` 一致。
- 旧 MOBI CRC 已改为尽力补充字段，缺少 `libappmobi.so` 时不再推翻原本可解析的 TXT。
- TXT 导入会保留原始文件名，不再在书架和阅读器显示私有 SHA-256 文件名。
- API 36 x86_64 模拟器已真实完成 TXT 目录导入、书架展示、正文阅读、控制区唤出与 5 秒自动隐藏验收，运行期间无致命崩溃。
- EPUB 核心解析仍依赖旧原生库；x86_64 失败已明确记录，ARM64 HyperOS 3 真机结论保留待验。
- 发布树的 431 个 JVM 测试、5 个 API 36 设备测试、APK 构建与 Lint 门禁已通过。
- GitHub Release `PageNest 1.9.260825` 已发布，GitHub 资产摘要与 `SHA256SUMS.txt` 一致。
- Online Discovery Phase 1 已建立 Gutendex、Project Gutenberg OPDS、Standard Ebooks OPDS、安全 XML 解析、跨源去重/RRF、4 MiB 原子缓存和来源级容错。
- 在线发现新增 44 个测试；完整工程 469 个 JVM 测试、调试 APK、测试 APK和 Lint 门禁通过。
- Standard Ebooks 完整 OPDS 可能需要会员身份或开源项目授权；未配置凭据时按可用性降级，不阻断其他来源。
- Online Discovery Phase 2 已加入蓝绿发现页、在线详情、Open Library 1 秒限流与 24 小时缓存、安全来源跳转和四项底部导航。
- Phase 2 发布树的 495 个 JVM 测试、主 APK、测试 APK 与 Lint 门禁已通过；本轮无连接设备，Compose 测试只完成编译，HyperOS 3 结论仍待真机。
- Online Discovery Phase 3 已完成 HTTPS/重定向/私网防护、100 MiB 上限、EPUB/TXT/PDF 验证、取消清理、私有书库导入、重复保护和阅读器跳转。
- Phase 3 发布候选树的 533 个 JVM 测试、主 APK、测试 APK 和 Lint 门禁已通过；无连接设备，模拟器与 HyperOS 3 验收仍待执行。
- GitHub Release `PageNest 1.11.260826` 已发布，远程 APK、`SHA256SUMS.txt`、GitHub 资产摘要与包内版本信息已交叉验证。
- PageNest 1.12.260826 新增应用内日志中心、2 MiB 有界轮转日志、脱敏与重复抑制，并修复实机发现页无搜索键、导入结果遮屏、重启重复扫描三项问题；验证证据见 `docs/testing/diagnostics-logging.md`。
- GitHub Release `PageNest 1.12.260826` 已发布；远端 APK、校验文件与包内版本已验证。由于 CI debug 签名不固定，覆盖旧版失败时需先卸载旧版再安装。

设计与执行依据：

- `docs/superpowers/specs/2026-08-25-pagenest-ui-refresh-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-ui-refresh-phase1.md`
- `docs/testing/ui-refresh-phase1.md`
- `docs/superpowers/specs/2026-08-25-pagenest-home-dashboard-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-home-dashboard-phase2.md`
- `docs/testing/ui-refresh-phase2.md`
- `docs/superpowers/specs/2026-08-25-pagenest-immersive-reader-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-immersive-reader-phase3.md`
- `docs/testing/ui-refresh-phase3.md`
- `docs/superpowers/specs/2026-08-25-pagenest-import-compatibility-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-import-compatibility-phase4.md`
- `docs/testing/import-compatibility-phase4.md`
- `docs/superpowers/specs/2026-08-25-pagenest-online-discovery-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-online-discovery-phase1-catalog-core.md`
- `docs/testing/online-discovery-phase1.md`
- `docs/superpowers/plans/2026-08-26-pagenest-online-discovery-phase2-ui.md`
- `docs/testing/online-discovery-phase2.md`
- `docs/superpowers/specs/2026-08-26-pagenest-online-discovery-phase3-design.md`
- `docs/superpowers/plans/2026-08-26-pagenest-online-discovery-phase3-secure-import.md`
- `docs/testing/online-discovery-phase3.md`
- `docs/superpowers/specs/2026-08-26-pagenest-diagnostics-logging-design.md`
- `docs/superpowers/plans/2026-08-26-pagenest-diagnostics-logging.md`
- `docs/testing/diagnostics-logging.md`

## 3. 下一开发入口

在目标手机连接后继续 **Online Discovery Phase 3 真机验收**：

1. 运行真机预检并确认 Android 16 / SDK 36 / HyperOS 3 / `arm64-v8a`。
2. 在真实网络分别验收 EPUB、TXT 和可提取文字 PDF 的加入书架、开始阅读和进度保存。
3. 验收重复导入、弱网、取消、返回和应用重启后的半成品清理。
4. 联合验收后台语音、锁屏控制、音频焦点与 60 分钟运行矩阵。

Phase 1–3 的证据和限制分别见 `docs/testing/online-discovery-phase1.md`、`docs/testing/online-discovery-phase2.md` 和 `docs/testing/online-discovery-phase3.md`。

## 4. HyperOS 3 真机入口（设备连接后）

继续 **UI Refresh Phase 5：HyperOS 3 真机兼容性验收与剩余格式验证**。优先完成：

Phase 5 Task 1 已完成：真机预检模块、命令包装器和 7 个快照测试位于 `tools/`，提交为 `b801b157c4f2bd1c16cdb5a78f465fe0ea8cf5f2`。当前 ADB 只发现 x86_64 模拟器，预检已正确拒绝，尚未产生任何真机 PASS。

下一步：

1. 用 USB 或无线调试连接目标 HyperOS 3 手机，复制 `adb devices -l` 第一列序列号；
2. 运行 `.\tools\hyperos3-device-preflight.ps1 -Serial '<目标手机序列号>'` 并要求 `preflight_passed=True`；
3. 在目标手机安装 GitHub Release `pagenest-v1.9.260825`；
4. 在 ARM64 真机分别验收 TXT、EPUB、MOBI/AZW3 和可提取文字 PDF；
5. 验收目录、进度保存、显示设置、图片换肤与语音入口；
6. 完成后台朗读、锁屏控制、来电/音频焦点恢复和 60 分钟运行矩阵；
7. 将真机发现的问题逐项按 systematic-debugging 与 TDD 修复，不用 x86_64 模拟器替代 ARM64 结论。

开始前先用 Superpower brainstorming 固化 Phase 5 真机矩阵；遇到失败时用 systematic-debugging 收集证据，再按 TDD 编写修复计划。每个任务完成后提交并推送到 `origin/master`。

命令行构建必须使用 JDK 17。当前 Android Studio 内置 JBR 为 Java 25，Gradle 8.11.1 会在启动阶段报 `25.0.2`；可在当前 PowerShell 会话设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
```

## 5. 仍待真机完成的独立事项

语音阅读 Task 9 的电脑端门禁和文档已经完成，但目标 HyperOS 3 手机的后台朗读与 60 分钟矩阵仍需要真机：

- `docs/superpowers/plans/2026-08-23-voice-reading.md`
- `docs/testing/voice-reading-hyperos3.md`

连接手机后先运行 `adb devices -l`。只有目标手机显示为 `device`，才记录 HyperOS 3 真机结论。

## 6. 阶段交付规则

```powershell
git diff --check
git add <本阶段文件>
git commit -m "feat: ..."
git push origin master
git fetch origin master
git rev-parse HEAD
git rev-parse origin/master
```

两个提交号应一致。不要用 `git clean -fd` 清理未知文件；未跟踪内容可能属于使用者。
# 最新续接点（2026-08-26，PageNest 1.15）

- 在线发现可靠性代码与 TDD 已完成，本地完整 Gradle 门禁通过。
- 版本已提升为 `1.15.260826` / versionCode 16。
- `master` 已推送，1.15 APK 与校验文件已归档到 GitHub Release。
- 下一步：连接并授权目标小米手机，覆盖安装 1.15，验证在线推荐、搜索和
  Gutenberg EPUB 导入。
- 证据：`docs/testing/online-discovery-resilience-1.15.md`。

# 最新续接点（2026-08-27，PageNest 1.16）

- 修复 HyperOS 系统 TTS 已初始化却因未枚举离线音色而被误判不可用的问题。
- AUTO 模式在未同意联网或未配置 Azure Key 时直接使用系统语音。
- 版本提升为 `1.16.260827` / versionCode 17。
- 下一步：完成发布门禁并在目标小米手机上回归听书；随后继续阅读进度排队与首次索引性能阶段。
- 证据：`docs/testing/hyperos-speech-fallback-1.16.md`。

# 最新续接点（2026-08-27，PageNest 1.17）

- 修复解析期间阅读进度操作丢失，并在索引完成后自动执行最后一次拖动目标。
- 移除索引固定等待，阻止同书重复全书索引，复用已持久化总字数，并在完成后刷新章节进度列表。
- 版本提升为 `1.17.260827` / versionCode 18。
- 下一步：目标小米手机安装 1.17，使用《程序员的思维修炼》回归进度、首次/再次打开速度、连续上下翻页、字体设置和听书。
- 证据：`docs/testing/reader-progress-indexing-1.17.md`。
