# PageNest 语音阅读：HyperOS 3 / Android 16 真机发布门禁

本文档是 PageNest 语音阅读首版的真机验收记录模板。目标设备必须是 HyperOS 3 / Android 16（API 36）。桌面构建通过不能替代本页的真机测试和连续 60 分钟人工验收。

## 当前状态

- 目标截图：HyperOS `3.0.303.0.WNNCNXM.C11`、Android 16、认证型号 `2407FRK8EC`
- 真机预检工具：`PASS`（提交 `b801b157c4f2bd1c16cdb5a78f465fe0ea8cf5f2`）
- 最近预检：仅发现 `emulator-5554`；已按预期拒绝（`emulator,manufacturer,primary-abi,hyperos-version`）
- 真机自动化：`NOT RUN (no connected device)`
- APK 安装：`NOT RUN (no connected device)`
- 60 分钟人工验收：`NOT RUN (no connected device)`
- 发布结论：**未通过真机发布门禁**

连接目标手机后，从“采集设备与 APK 证据”开始填写本页，不要把空白项改成 PASS。

连接后先显式选择手机序列号运行硬门禁，只有 `preflight_passed=True` 才继续本页后续命令：

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
.\tools\hyperos3-device-preflight.ps1 -Serial '<目标手机序列号>' -AdbPath $adb
```

## 1. 测试前准备

电脑端需要 JDK 17、Android SDK 36、Build Tools 36.0.0 和最新版 Platform Tools。完整安装说明见 [开发环境指南](../DEVELOPMENT.md)。在仓库根目录打开 PowerShell：

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
java -version
adb version
```

手机端准备：

1. 充电到至少 80%，关闭省电模式，记录测试开始时电量和室温。
2. 打开“设置 > 我的设备/关于手机”，连续点击 OS 版本进入开发者模式。
3. 打开“设置 > 更多设置 > 开发者选项 > USB 调试”。如设备显示“USB 调试（安全设置）”，也将其打开。
4. 首次连接时保持手机解锁，在 RSA 指纹弹窗中只授权当前可信电脑。
5. 确认系统已安装可用的中文系统语音引擎及离线语音数据。

HyperOS 的菜单名称会因机型和地区略有差异；找不到时使用设置内搜索“开发者选项”“后台自启动”“电池”或“通知”。Android 官方真机连接说明：<https://developer.android.com/studio/run/device>。

## 2. 连接设备

### USB 调试

使用支持数据传输的 USB 线，USB 用途选择“文件传输/Android Auto”，然后执行：

```powershell
adb kill-server
adb start-server
adb devices -l
```

必须出现且只能选定目标手机的 `device` 行。`unauthorized`、`offline` 或空列表都不算已连接。

### 无线调试

手机和电脑处于同一可信局域网，在“开发者选项 > 无线调试”选择“使用配对码配对设备”：

```powershell
adb pair <手机显示的IP:配对端口>
adb connect <手机显示的IP:调试端口>
adb devices -l
```

配对端口与调试端口可能不同。公共 Wi-Fi 下不要启用无线调试；完成后在手机中关闭无线调试或删除已配对电脑。

## 3. 构建、安装与自动化门禁

先运行不依赖手机的完整门禁：

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\PageNestDev\jdk-17.0.20.1+1"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

再采集设备与 APK 证据：

```powershell
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.mi.os.version.name
adb shell getprop ro.build.fingerprint
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

只有 Android 版本为 `16`、SDK 为 `36` 且 HyperOS 属性能识别目标版本时才继续。记录：

| 证据 | 实际值 |
| --- | --- |
| 测试时间（含时区） | NOT RUN |
| 制造商/型号 | NOT RUN |
| Android release / SDK | NOT RUN |
| HyperOS 版本 | NOT RUN |
| Build fingerprint | NOT RUN |
| Git commit | NOT RUN |
| APK SHA-256 | NOT RUN |

安装并运行精确的仪器测试：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.air5005.pagenest.speech
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wxn.reader.data.source.local.AppDatabaseMigrationTest
```

预期：安装输出 `Success`，语音包测试与 Room 迁移测试均为 0 failure。仪器测试报告位于 `app/build/reports/androidTests/connected/debug/`，原始结果位于 `app/build/outputs/androidTest-results/connected/debug/`。Android 官方命令行测试说明：<https://developer.android.com/studio/test/command-line>。

| 自动门禁 | 结果 | 证据文件/摘要 |
| --- | --- | --- |
| APK 安装 | NOT RUN | |
| Speech Android tests | NOT RUN | |
| Room migration test | NOT RUN | |

## 4. Azure 配置（不得暴露 Key）

1. 仅在 PageNest 的“语音阅读设置”中输入 Azure Speech Key 和 Region。
2. Region 使用 Azure 资源页面显示的区域短名称，例如 `eastasia`；不要猜测。
3. 点击“测试连接”，分别记录有效配置、无效 Key 和错误 Region 的用户可见结果。
4. Key 不得放入 PowerShell 命令、环境变量、截图、录屏、日志、测试报告、Git 提交或聊天消息。
5. 截图前遮盖通知和输入框；应用不应重新显示完整 Key，只允许覆盖或删除。
6. 验收结束后在设置中删除 Key，并确认在线音频缓存已清理。

普通自动化测试禁止调用真实 Azure；真实 Key 只用于本节的人工连接验收。

首次启用“在线”或“自动”模式时必须额外验证：

- 提示内容逐字为“在线朗读会将当前段落文本发送给 Azure 生成语音，是否继续？”。
- 选择“取消”后保持未授权，当前段落不得产生 Azure 请求或开始在线播放。
- 再次发起并选择“同意”后保存授权，本次操作只启动一次；后续已授权操作不应重复弹窗。

## 5. HyperOS 后台与媒体控制设置

为了验证“用户允许后台朗读”这一产品路径：

1. “设置 > 应用 > 权限 > 后台自启动”中允许 PageNest。小米官方路径参考：<https://www.mi.com/global/support/faq/details/KA-507611/>。
2. “设置 > 电池 > 应用电池管理/PageNest”选择“不限制”。记录实际菜单名称。
3. “设置 > 通知与状态栏 > 应用通知 > PageNest”允许通知、锁屏通知和媒体通知。
4. 在最近任务界面测试锁定 PageNest（若机型提供），但不要把“锁定”当作后台服务正确性的替代证据。
5. 分别测试返回桌面、锁屏、熄屏 10 分钟、从通知栏暂停/继续/上一段/下一段/停止。
6. 播放其他媒体或触发导航语音制造音频焦点丢失；PageNest 应暂停，焦点恢复后不得自动发声。
7. 有线耳机或蓝牙音频播放中断开连接；PageNest 应立即暂停，不得从扬声器继续。

## 6. 格式与阅读联动矩阵

每种书准备一份合法测试文件。不要在报告中记录真实书名、完整文件路径或正文；使用 `fixture-epub` 等代号。每格填写 PASS/FAIL、时间和截图/录像编号。

| 场景 | 当前段开始 | 跨页 | 跨章 | 上一/下一段 | 高亮与自动跟随 | 手动跳转取消旧语音 | 结果/证据 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| EPUB | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| TXT | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| MOBI | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| AZW3 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | |
| 可提取文字 PDF | NOT RUN | NOT RUN | 不适用 | NOT RUN | NOT RUN（按页） | NOT RUN | |
| 纯图片扫描 PDF | 预期拒绝 | 不适用 | 不适用 | 不适用 | 不适用 | 不适用 | NOT RUN；预期“此 PDF 为扫描版，暂不支持语音朗读” |

## 7. 引擎、网络和恢复矩阵

| 场景 | 预期 | 结果/证据 |
| --- | --- | --- |
| 系统中文离线音色 | 无网络也能朗读 | NOT RUN |
| 有效 Azure Key + Region | 在线中文音色成功 | NOT RUN |
| 首次在线提示后取消 | 明确说明发送当前段落；Azure 请求数为 0 | NOT RUN |
| 首次在线提示后同意 | 保存授权并且只启动一次在线请求 | NOT RUN |
| 无效 Key | 显示明确认证错误，不泄露 Key | NOT RUN |
| 错误 Region | 显示 Region 错误 | NOT RUN |
| Wi-Fi 切移动网络 | 当前会话可继续或同段回退，无重音 | NOT RUN |
| 在线时断网 | 自动模式从同一未完成段落回退离线 | NOT RUN |
| 恢复网络 | 不自动重播已完成段落 | NOT RUN |
| 通知操作 | 暂停/继续/上一段/下一段/停止均正确 | NOT RUN |
| 音频焦点丢失/恢复 | 暂停，恢复后仍保持暂停 | NOT RUN |
| 耳机/蓝牙断开 | 立即暂停且扬声器不续播 | NOT RUN |
| 段落完成 | 只保存已完整播放段落的末尾 | NOT RUN |
| 强制停止后重开 | 恢复最近已完成位置，保持暂停且不自动发声 | NOT RUN |

“强制停止后重开”属于真实系统进程边界，只能用本行真机证据验收；服务对象重建测试不能替代它。

## 8. 连续 60 分钟人工检查表

开始前建立本机临时证据目录、清理旧日志并记录基线：

```powershell
$evidenceRoot = Join-Path $env:TEMP ("PageNest-HyperOS3-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
Set-Content -LiteralPath (Join-Path $env:TEMP 'PageNest-HyperOS3-current.txt') -Value $evidenceRoot
adb logcat -c
adb shell dumpsys battery | Tee-Object -FilePath (Join-Path $evidenceRoot 'battery-start.txt')
adb shell dumpsys meminfo com.air5005.pagenest | Tee-Object -FilePath (Join-Path $evidenceRoot 'meminfo-start.txt')
adb shell dumpsys thermalservice | Tee-Object -FilePath (Join-Path $evidenceRoot 'thermal-start.txt')
```

另开一个 PowerShell 窗口执行下面的 60 分钟内存采样。它每分钟提取一次 `TOTAL PSS`，保存原始样本，并在结束时生成可复核的峰值；采样窗口必须覆盖整个连续朗读过程：

```powershell
$evidenceRoot = Get-Content -LiteralPath (Join-Path $env:TEMP 'PageNest-HyperOS3-current.txt')
$csv = Join-Path $evidenceRoot 'total-pss-60min.csv'
'Timestamp,TotalPssKb' | Set-Content -LiteralPath $csv -Encoding utf8
$sampleUntil = (Get-Date).AddMinutes(60)
while ((Get-Date) -le $sampleUntil) {
    $meminfo = adb shell dumpsys meminfo com.air5005.pagenest
    $match = [regex]::Match(($meminfo -join "`n"), 'TOTAL PSS:\s*(\d+)')
    if (-not $match.Success) {
        throw '无法从 dumpsys meminfo 提取 TOTAL PSS；保留当前输出并停止验收'
    }
    '"{0}",{1}' -f (Get-Date -Format o), $match.Groups[1].Value |
        Add-Content -LiteralPath $csv -Encoding utf8
    if ((Get-Date) -le $sampleUntil) { Start-Sleep -Seconds 60 }
}
$samples = Import-Csv -LiteralPath $csv
$peak = ($samples | Measure-Object -Property TotalPssKb -Maximum).Maximum
"SampleCount=$($samples.Count) PeakTotalPssKb=$peak" |
    Tee-Object -FilePath (Join-Path $evidenceRoot 'total-pss-summary.txt')
```

预期至少获得 60 个有效样本。采样命令失败、应用进程消失或样本不足时，本轮 60 分钟门禁判为 FAIL，不得用开始/结束两个瞬时值冒充峰值。

- [ ] 00:00：记录开始时间、环境温度、设备体感温度、电量、充电状态、网络和引擎。
- [ ] 00:05：锁屏并熄屏，确认语音持续且媒体通知可操作。
- [ ] 00:15：执行暂停/继续、上一段/下一段，确认无重叠语音。
- [ ] 00:20：制造一次音频焦点丢失与恢复，确认不会自动继续。
- [ ] 00:25：断开耳机或蓝牙，确认立即暂停。
- [ ] 00:30：切换 Wi-Fi/移动网络/离线，确认同段回退且无重复。
- [ ] 00:40：手动翻页和跳章，确认旧 generation 被取消且高亮跟随目标。
- [ ] 00:50：切到 PDF，验证有文字页跟随和扫描版提示。
- [ ] 00:60：记录结束电量、温度、峰值内存、崩溃和 ANR 扫描。

结束时采集：

```powershell
adb shell dumpsys battery | Tee-Object -FilePath (Join-Path $evidenceRoot 'battery-end.txt')
adb shell dumpsys meminfo com.air5005.pagenest | Tee-Object -FilePath (Join-Path $evidenceRoot 'meminfo-end.txt')
adb shell dumpsys thermalservice | Tee-Object -FilePath (Join-Path $evidenceRoot 'thermal-end.txt')
adb logcat -d -b crash | Tee-Object -FilePath (Join-Path $evidenceRoot 'logcat-crash.txt')
adb logcat -d | Select-String -Pattern 'ANR in com.air5005.pagenest|FATAL EXCEPTION' |
    Tee-Object -FilePath (Join-Path $evidenceRoot 'logcat-anr-fatal.txt')
Write-Output "Evidence: $evidenceRoot"
```

| 60 分钟证据 | 实际值 |
| --- | --- |
| 开始/结束时间 | NOT RUN |
| 开始/结束电量 | NOT RUN |
| TOTAL PSS 样本数/峰值/证据 CSV | NOT RUN |
| 观察到的设备温度 | NOT RUN |
| Crash buffer | NOT RUN |
| ANR/FATAL 扫描 | NOT RUN |
| 最终结果 | NOT RUN |

## 9. 常见问题

- `unauthorized`：解锁手机并确认 RSA；仍无弹窗时撤销 USB 调试授权，再重新连接。
- `offline`：更换数据线/USB 口，重新启动 ADB；无线连接则关闭后重新配对。
- 没有设备：确认线缆支持数据、USB 用途不是“仅充电”，按需安装小米 OEM 驱动。
- 多台设备：先执行 `adb devices -l`，所有后续命令用 `adb -s <serial> ...` 指定目标，避免证据来自错误设备。
- 安装失败：先检查手机剩余空间和系统安装确认；不要用卸载规避数据库迁移测试。
- 没有系统中文声音：在系统文字转语音设置安装中文离线语音数据后重试。
- 后台数分钟后停止：复查后台自启动、电池“不限制”、通知权限，并记录 HyperOS 实际设置页面。
- Azure 失败：核对资源 Region 与 Key 是否属于同一 Azure Speech 资源；不要把 Key 复制到日志或问题单。
- 仪器测试失败：保留 `app/build/outputs/androidTest-results/connected/debug/`、设备属性、commit 和 APK SHA-256，再定位问题。

## 10. 发布签字

以下条件全部满足后才能把 Task 9 标记完成：桌面门禁通过、目标设备属性符合要求、APK 安装成功、两组 connected tests 通过、矩阵无未执行必测项、60 分钟无崩溃/ANR且证据完整。

| 角色 | 姓名 | 时间 | 结论 |
| --- | --- | --- | --- |
| 执行人 | | | NOT RUN |
| 复核人 | | | NOT RUN |
