# PageNest 续接手册（重启或换机）

本仓库直接在 `master` 开发。UI Refresh Phase 1 已完成，当前优先入口是 **UI Refresh Phase 2：阅读仪表盘首页**。

## 1. 恢复工作区

```powershell
cd D:\pagenest
git checkout master
git pull origin master
git status --short
```

预期 `git status --short` 没有输出。本阶段验收代码提交为：

```text
f7842a178a9285cec5c2a10dfbe45f0c6ca0ae49
```

## 2. 已完成的 UI Refresh Phase 1

- PageNest 蓝绿设计令牌与主题基础。
- “阅读窗口”自适应桌面图标与页栖品牌资源。
- 中文无状态首次启动引导及设备端 Compose UI 测试。
- 可选示例书解析故障边界；x86_64 缺少 `libappmobi.so` 时首页不再崩溃。
- API 36 模拟器明暗模式、目录选择返回、跳过和重启恢复验收。

设计与执行依据：

- `docs/superpowers/specs/2026-08-25-pagenest-ui-refresh-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-ui-refresh-phase1.md`
- `docs/testing/ui-refresh-phase1.md`

## 3. 下一开发入口

继续 **UI Refresh Phase 2：阅读仪表盘首页**。按已确认的首页方案 B，实现：

1. 蓝绿渐变阅读概览卡；
2. 最近阅读与继续阅读入口；
3. 空书架状态和导入 CTA；
4. 与现有电子书、有声书、我的三个底部入口兼容；
5. 保持图片换肤、语音阅读、PDF 和导入流程不变。

开始前先用 Superpower brainstorming 固化 Phase 2 设计，再用 TDD 编写实施计划。每个任务完成后提交并推送到 `origin/master`。

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
