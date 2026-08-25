# PageNest 续接手册（重启或换机）

本仓库直接在 `master` 开发。UI Refresh Phase 2 已完成，当前优先入口是 **UI Refresh Phase 3：沉浸式阅读器**。

## 1. 恢复工作区

```powershell
cd D:\pagenest
git checkout master
git pull origin master
git status --short
```

预期 `git status --short` 没有输出。本阶段验收代码提交为：

```text
d9f94bb9e70ca1ae488d198f3744662366fce37a
```

Phase 2 发布提交为 `d9f94bb9e70ca1ae488d198f3744662366fce37a`，对应 GitHub Release `pagenest-v1.7.260825`。完整发布证据见 `docs/testing/ui-refresh-phase2.md`；续接时始终以最新的 `origin/master` 为准。

## 2. 已完成的 UI Refresh Phase 1–2

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

设计与执行依据：

- `docs/superpowers/specs/2026-08-25-pagenest-ui-refresh-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-ui-refresh-phase1.md`
- `docs/testing/ui-refresh-phase1.md`
- `docs/superpowers/specs/2026-08-25-pagenest-home-dashboard-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-home-dashboard-phase2.md`
- `docs/testing/ui-refresh-phase2.md`

## 3. 下一开发入口

继续 **UI Refresh Phase 3：沉浸式阅读器**。按已确认的阅读器方案 A，实现：

1. 默认沉浸正文、章节和页码；
2. 轻触正文显示或隐藏阅读工具栏；
3. 统一目录、进度、听书和显示入口；
4. 语音播放时显示暂停、倍速、定时与当前引擎的迷你播放器；
5. 保持 EPUB/TXT/MOBI/AZW3、可提取文字 PDF、进度保存、图片换肤和后台语音行为不变。

开始前先用 Superpower brainstorming 固化 Phase 3 设计，再用 TDD 编写实施计划。每个任务完成后提交并推送到 `origin/master`。

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
