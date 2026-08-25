# PageNest UI Refresh Phase 2 验收记录

验收日期：2026-08-25（Asia/Shanghai）  
功能提交：`d6207de8fe2343d13456b82425144cdb591e572d`  
分支：`master`

## 阶段范围

- 首页默认呈现蓝绿渐变“阅读仪表盘”。
- 今日阅读分钟、连续阅读天数和本周 150 分钟目标来自现有本地阅读活动。
- 最近阅读按 `lastOpened` 降序展示最多 3 本非音频书籍，进度限制在 0–100%。
- 最近书籍点击沿用现有 EPUB/TXT/MOBI/AZW3、PDF 和音频路由策略。
- 空书架提供一键导入；全部书籍继续提供搜索、排序、网格/列表、书架及选择管理。
- 阅读活动读取失败时统计降级为 0，不阻断书架与导入。
- 新文案已补齐项目现有的 9 个语言资源目录。

## TDD 证据

RED 阶段分别确认：

1. `HomeDashboardCalculatorTest` 因计算器不存在而编译失败。
2. `HomeDashboardFlowTest` 因安全组合 Flow 不存在而编译失败。
3. `HomeDashboardContentTest` 因无状态仪表盘不存在而编译失败。
4. `HomeViewModelImportFlowTest.dashboardBookClickPublishesExistingReaderRoute` 因首页没有按书籍 id 打开入口而编译失败。

GREEN 阶段：

- 仪表盘计算与 Flow 测试：8/8 通过。
- 首页相关 JVM 测试：18/18 通过。
- API 36 Compose 仪表盘测试：3/3 通过。

## 完整自动化门禁

命令行使用 JDK 17；Android Studio 2026 当前内置 JBR 为 Java 25，直接作为 Gradle Launcher 会在项目配置阶段报告 `25.0.2`。

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

功能完成门禁结果：`BUILD SUCCESSFUL in 4m 56s`。更新发布版本后再次执行同一完整命令，最终发布候选结果：`BUILD SUCCESSFUL in 5m 19s`。

- JVM 测试套件：45。
- JVM 测试：419，通过 419，失败 0，错误 0，跳过 0。
- Lint：错误 0，警告 148。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，125,096,822 字节。
- AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，3,772,592 字节。
- 本地发布候选 Debug APK SHA-256：`58B583CCF939D53BE331A2FB42A781F1B5BE5C46A42CFB48B4509BB8F3290D43`。
- 包内版本：`com.air5005.pagenest`，`versionCode=8`，`versionName=1.7.260825`。

首次完整门禁正确发现 19 条 `MissingTranslation` 错误；补齐 9 个语言目录后，单独 Lint 与重新执行的完整门禁均通过，没有建立或更新 Lint 基线。

## 模拟器验收

设备：`pagenest_api36`  
型号：`sdk_gphone64_x86_64`  
系统：Android 16 / API 36  
ABI：`x86_64`

| 场景 | 结果 |
| --- | --- |
| 全新数据启动、跳过首次引导并进入仪表盘 | 通过 |
| 空书架显示真实 0 分钟、0 天和 0/150 分钟 | 通过 |
| “一键导入书籍”打开目录确认框 | 通过 |
| 确认框进入系统目录选择器并可返回 | 通过 |
| “全部书籍”进入原书架，搜索/排序/布局入口保留 | 通过 |
| 系统返回键从全部书籍回到仪表盘 | 通过 |
| 最近阅读点击按现有格式路由（JVM 接线测试） | 通过 |
| 浅色模式标题、空状态、卡片和按钮可读 | 通过 |
| 深色模式标题、空状态、卡片和按钮可读 | 通过 |
| 强制停止后重新启动保持在首页 | 通过 |
| 最终安装、Compose 测试和重启后的 PageNest 致命异常 | 0 |

Compose 设备测试命令：

```text
adb shell am instrument -w -e class com.wxn.reader.presentation.home.dashboard.HomeDashboardContentTest com.air5005.pagenest.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`OK (3 tests)`，用时 5.495 秒。

本地截图（`captures/` 已被 Git 忽略）：

- `captures/ui-refresh-phase2/home-dashboard-empty-light.png`
- `captures/ui-refresh-phase2/home-dashboard-empty-dark.png`

实装检查曾发现旧 `Scaffold` 将默认内容色设置为透明，导致可访问树中存在但肉眼不可见的仪表盘标题；已将默认内容色修正为主题正文色，并通过浅色/深色截图复验。

## 兼容性与限制

- x86_64 模拟器验证首页安全降级与界面行为，不验证依赖 ARM 原生库的 MOBI 正常解析。
- 本阶段没有改变图片换肤、在线/离线语音、后台媒体服务、PDF 阅读或存储授权规则。
- 最近阅读的真实多书数据仍应在 HyperOS 3 ARM64 真机用用户书库补充体验验收。
- 语音阅读 Task 9 的 HyperOS 3 后台朗读与 60 分钟矩阵仍待目标手机完成。

## 下一阶段

下一开发入口：**UI Refresh Phase 3：沉浸式阅读器**。

继续时读取：

- `docs/superpowers/specs/2026-08-25-pagenest-ui-refresh-design.md`
- `docs/TASK5_RESUME_MANUAL.md`
- 本文档

Phase 2 已达到可安装、可回归的发布检查点，并完成远端 APK 归档。

## GitHub Release

- Release：`PageNest 1.7.260825`。
- 标签：`pagenest-v1.7.260825`。
- 发布源提交：`d9f94bb9e70ca1ae488d198f3744662366fce37a`。
- GitHub Actions：`Archive Android APK Release` 运行 `32847109039` 成功；build 8 分 41 秒，release 10 秒。
- 远端 APK：`PageNest-pagenest-v1.7.260825-debug.apk`，125,096,734 字节。
- 远端 APK SHA-256：`FFE1E60E0A5C6F0E5B233763B6758DA0005AF8823DDADCAF8915F69DD7C0EDA0`。
- `SHA256SUMS.txt` 声明值与下载后实算值一致。
- 下载后包内校验：`com.air5005.pagenest`，`versionCode=8`，`versionName=1.7.260825`，`minSdk=29`，`targetSdk=36`。
- Release 状态：最新、非草稿、非预发布。
- Release 页面：<https://github.com/air5005/pagenest/releases/tag/pagenest-v1.7.260825>
- Actions 页面：<https://github.com/air5005/pagenest/actions/runs/32847109039>

本地与 GitHub Actions 在不同操作系统和构建环境独立生成 APK，因此二进制哈希不同；对外归档以远端 APK 哈希及同一 Release 中的 `SHA256SUMS.txt` 为准。
