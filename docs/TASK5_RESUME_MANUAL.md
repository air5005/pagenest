# Task9 续接手册（重启/换机后继续开发）

本仓库已完成 Task1~Task8，并按你要求全部提交到 `master`。
每次系统重启或在新电脑克隆后，按以下步骤可直接从 Task9 继续。

## 1. 拉取最新版仓库

```powershell
cd D:\pagenest
git checkout master
git pull origin master
git status
```

要求：

- `git status` 应显示 `nothing to commit, working tree clean`
- `git log --oneline -n 1` 最近提交应是 Task8 的归档提交

## 2. Task9 继续入口

- 设计/任务源文件：
  - `D:\pagenest\docs\superpowers\plans\2026-08-23-voice-reading.md`
  - `D:\pagenest\docs\superpowers\specs\2026-08-23-voice-reading-design.md`

- 本次要继续的是：
  - Task 9：完成 Android 生命周期自动化验证，并在目标 HyperOS 3 手机上执行安装、后台朗读和 60 分钟测试矩阵

## 3. 开始开发前的最短检查

```powershell
Get-Content .\docs\superpowers\plans\2026-08-23-voice-reading.md | Select-String "Task 9" -Context 0,80
```

确认：

- Task 9 仍是未勾选状态。
- Task 8 的两个阅读器语音控制、Azure 设置、首次联网同意、PDF 提示和旧 Edge TTS 清理已经提交。
- 计划里 Task 9 涉及到的 Android 自动化、HyperOS 3 真机和 60 分钟验收矩阵与你要执行的测试一致。

## 4. 开发执行原则（必须遵守）

1. 每个阶段：`代码改动 -> 提交 -> 推送`，且推送到 `master`。
2. 每次提交信息建议沿用约定格式，如 `feat:`、`fix:`、`chore:`。
3. 你已授权后，可直接用 `git push origin master`。

## 5. 任务分支/路径约定

- 主分支即用 `master`。
- 建议继续在仓库主目录直接开发，不新增其它分支（按你当前要求）。
- 若你愿意保留安全缓冲，也可以临时建本地分支，但最终必须 `commit + push` 到 `master`。

## 6. 建议日常命令

### 开始 Task9 之前

```powershell
git clean -fd
git status
```

### 每个开发阶段完成后

```powershell
git add .
git commit -m "test: add HyperOS speech release gates"
git push origin master
git status
```

### 远端核对

```powershell
git log --oneline --max-count 5
git rev-parse HEAD
git rev-parse origin/master
```

`git rev-parse HEAD` 与 `git rev-parse origin/master` 应一致时说明已同步。

## 7. 你现在可直接操作

这份文档目的就是让你重启或换电脑后不需要重新判断上下文，  
直接执行：

```powershell
git pull origin master
```

然后从计划中的 `Task 9` 开始即可。若 `adb devices -l` 没有列出目标手机，可先完成桌面门禁和测试文档，再连接手机继续真机矩阵。
