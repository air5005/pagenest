# PageNest 续接手册（重启或换机）

本仓库直接在 `master` 开发。UI Refresh Phase 3 的功能与发布前验证已经完成，当前优先入口是 **UI Refresh Phase 4：导入兼容性与真实阅读验收**。

## 1. 恢复工作区

```powershell
cd D:\pagenest
git checkout master
git pull origin master
git status --short
```

预期 `git status --short` 没有输出。Phase 3 发布源代码提交为：

```text
6f9347316c02d87893a3802fd3ddd5a4bff04679
```

Phase 3 已发布为 GitHub Release `pagenest-v1.8.260825`。完整验证与远端归档证据见 `docs/testing/ui-refresh-phase3.md`；续接时始终以最新的 `origin/master` 为准。

## 2. 已完成的 UI Refresh Phase 1–3

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

## 3. 下一开发入口

继续 **UI Refresh Phase 4：导入兼容性与真实阅读验收**。优先完成：

1. 为 TXT 与公开 EPUB 样书建立可重复的导入回归夹具；
2. 定位全新 API 36 模拟器导入 0 本或无法解析的原因，并按 TDD 修复；
3. 在真实正文中验收沉浸控制区、目录、进度保存、显示设置和语音入口；
4. 在目标 HyperOS 3 手机安装最新 Release APK，完成真实设备阅读与后台语音检查；
5. 保持 MOBI/AZW3、可提取文字 PDF 和图片换肤现有行为不回退。

开始前先用 Superpower systematic-debugging 收集导入失败证据，再用 brainstorming 固化 Phase 4 边界，并按 TDD 编写实施计划。每个任务完成后提交并推送到 `origin/master`。

命令行构建必须使用 JDK 17。当前 Android Studio 内置 JBR 为 Java 25，Gradle 8.11.1 会在启动阶段报 `25.0.2`；可在当前 PowerShell 会话设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
```

## 4. 仍待真机完成的独立事项

语音阅读 Task 9 的电脑端门禁和文档已经完成，但目标 HyperOS 3 手机的后台朗读与 60 分钟矩阵仍需要真机：

- `docs/superpowers/plans/2026-08-23-voice-reading.md`
- `docs/testing/voice-reading-hyperos3.md`

连接手机后先运行 `adb devices -l`。只有目标手机显示为 `device`，才记录 HyperOS 3 真机结论。

## 5. 阶段交付规则

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
