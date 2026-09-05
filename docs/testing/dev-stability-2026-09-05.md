# Windows 开发稳定性修复验证（2026-09-05）

## 本轮改动

- TTS：在启动 owner 协程之前初始化 `progressListener`，避免立即执行或快速调度时读取到尚未赋值的监听器。移除上轮临时加入的 `forkEvery = 25` 测试分进程配置。
- 多语言：10 组语言的导入进度字符串使用 `%1$d/%2$d`；修复阿拉伯语删除失败提示的损坏占位符。
- 原生日志：`jlong` 参数使用匹配的 64 位格式；EPUB 错误日志不再误读 MOBI 对象。
- JPEG 2000：分配前检查像素乘法、32 位分配大小和 JNI 数组头部长度；传播像素转换失败结果，避免后续使用无效输出。
- 文档：同步已安装的 Windows 工具链、模拟器配置、当前功能和正式品牌。YiNest / 羿巢阅读是正式品牌；`pagenest` 包名、仓库名和兼容标识保留。

## 回归证据

1. 新增 TTS 立即调度测试：修复前在监听器未安装的断言处稳定失败；修复后通过。它验证已确认的构造初始化竞态，不单独证明所有历史偶发失败都来自同一原因。
2. TTS 相关 6 个测试类、48 项测试全部通过，覆盖原有关闭、取消、异常处理及新增初始化路径。
3. 新增多语言格式测试：修改 XML 前 3 项全部失败，修复后 3 项全部通过；检查格式参数契约及导入进度、删除失败两条消息的实际替换。
4. JPEG 2000 尺寸自测：17 项边界全部通过，使用 NDK 编译器在 Windows 宿主运行，启用 `-Wall -Wextra -Werror`。运行方法见 [开发环境指南](../DEVELOPMENT.md)。

## 完整桌面门禁

JDK 17，使用项目固定的 Gradle 8.11.1，在仓库根目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain
```

完成版本递增后再次执行，结果：`BUILD SUCCESSFUL`，耗时 3 分 52 秒。

| 检查 | 结果 |
| --- | --- |
| App 单元测试 | 89 个测试类，592 项通过，0 失败、0 错误、0 跳过 |
| App / JPEG 2000 原生编译 | arm64-v8a、armeabi-v7a、x86、x86_64 通过 |
| MOBI 原生编译 | 配置的 arm64-v8a、armeabi-v7a 通过 |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 设备测试 APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Lint | 0 错误、236 警告、5 提示；字符串格式问题为 0 |

单测报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
Lint 报告：`app/build/reports/lint-results-debug.html`。
这些构建输出会被后续构建覆盖，结果仅代表本次代码状态。

Debug APK 包名 `com.air5005.pagenest`，版本 `1.20.260905`（versionCode 21），大小 126,289,994 字节。最终本地构建的 SHA-256：

```text
46A1113773A3E43213F77ECDC51E3FFEE3F8E3298E178803F923F6B54043E5DA
```

## GitHub 发布归档

- 发布提交：`e5ba88ee22a48ca4d1f962812f6708c7d5360c7d`。
- 标签：`yinest-v1.20.260905`，指向上述发布提交。
- GitHub Actions：[Archive Android APK Release #19](https://github.com/air5005/pagenest/actions/runs/33951681580)，结论 `success`。
- GitHub Release：[YiNest 1.20.260905](https://github.com/air5005/pagenest/releases/tag/yinest-v1.20.260905)，非草稿、非预发布。
- 云端 APK：`YiNest-1.20.260905-debug.apk`，126,289,830 字节。
- 云端 APK SHA-256：`c1c3a801cfe13cc190e2f88e8b7da57f3a5581b8fadc67b07e6d2e7266d8635b`；下载后的实际摘要与同一 Release 的 `SHA256SUMS.txt` 一致。
- 下载云端 APK 后重新读取包信息，确认 package `com.air5005.pagenest`、versionCode 21、versionName `1.20.260905`、minSdk 29、targetSdk 36。

本机与 GitHub Actions 的调试签名及构建环境不同，因此本地 APK 和云端 APK 的字节数、摘要不同；对外归档以同一 GitHub Release 内 APK 与 `SHA256SUMS.txt` 的匹配结果为准。

## 未覆盖与后续项

- 本轮只编译设备测试包，没有执行真机测试，也没有重新安装本次 APK 做设备端验收。此前模拟器启动验证不能替代本轮设备回归或 HyperOS 3 验收。
- [HyperOS 3 语音阅读发布门禁](voice-reading-hyperos3.md)仍未完成，包括目标真机后台连续朗读和 60 分钟人工验收。
- 236 条 Lint 警告未全部处理；本轮未批量升级依赖，也没有屏蔽告警。
- `mobi/src/main/cpp/util/book_util.h` 及派生类仍使用 `long` 保存书籍 ID，32 位 ABI 下存在将 JNI `jlong` 截断的风险，需要独立贯通接口修改与测试。
- `prepareReturnData` 仍未检查 `NewIntArray` 分配失败，极端内存不足时的 JNI 处理需要另行加固。
- 尺寸边界自测不等同于完整图像解码、安全或各设备兼容性验收。
