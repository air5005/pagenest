# 在线发现 Phase 2：页面与导航验证记录

日期：2026-08-26  
分支：`master`  
版本：`1.10.260826`（versionCode 11）

## 1. 本阶段完成内容

- 首页底部导航升级为“书架 / 发现 / 听书 / 我的”，以稳定枚举代替散落的数字索引。
- 增加蓝绿渐变的在线发现页：搜索、推荐/热门/最新/来源标签、全部/中文/English 筛选、编辑推荐和热门榜单。
- 增加图书详情页，展示封面、作者、版权状态、格式、出版年份和可信来源入口。
- Gutendex 与 Project Gutenberg 默认启用；Standard Ebooks 在未取得官方授权时明确显示“需要授权”，生产环境不会构造或请求该来源。
- Open Library 只用于点开详情后的低频元数据补充：请求串行且至少间隔 1 秒，正/负结果缓存 24 小时，不提供下载地址。
- 外部来源页面只能由允许的来源和严格校验的 Gutenberg 数字 ID、Standard Ebooks slug 或 Open Library Work ID 生成。
- Phase 2 不执行在线文件下载；安全下载、校验、私有书库导入与打开阅读器留给 Phase 3。
- 发现页可见文字已加入基础中文及当前维护的中文、阿拉伯语、德语、西班牙语、法语、印地语、日语、葡萄牙语和俄语资源。

## 2. 自动化验证证据

完整门禁命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

结果：

- Gradle：`BUILD SUCCESSFUL in 4m 46s`。
- JVM 测试：495，通过 495，失败 0，错误 0，跳过 0。
- Lint：错误 0；警告 148；其他提示 5；未增加 Lint baseline。
- 私有书库原生校验：`private_book_store_native_validation=PASS`。
- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`
  - 大小：125,820,070 字节
  - SHA-256：`AE415CAC3184FEEF0714A59333F4C7D8FCB90624A0B9DDAD0A599893DE53D8E5`
- 测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - 大小：3,772,709 字节
  - SHA-256：`2D15920849F714FD4F195520B397324AAC58F15DDC007835FA761EDB5A80C38D`
- APK 元数据：包名 `com.air5005.pagenest`，versionCode 11，versionName `1.10.260826`，minSdk 29，targetSdk 36。

新增测试覆盖目录生产注入、来源状态、Open Library 限流/缓存/响应边界、发现页状态和搜索防抖、安全来源链接、四项导航稳定映射以及 Compose 页面结构。外部服务测试继续使用 MockEngine 或固定样本，不依赖实时公网结果。

## 3. 设备验证状态

本次门禁时 `adb devices` 没有发现已连接设备，因此：

- Compose 设备测试已编译进入测试 APK，但本轮未在模拟器执行。
- 未把电脑端构建结果表述为 HyperOS 3 ARM64 真机通过。
- 目标手机连接后仍需运行 `tools/hyperos3-device-preflight.ps1`，再验证发现页、真实目录请求、详情、系统浏览器来源跳转、深浅色、大字体和返回行为。

## 4. 服务与安全策略

- Project Gutenberg 官方 XML OPDS 当前入口为 `https://www.gutenberg.org/ebooks/search.opds/`，官方预计在 2027 年停用旧 XML OPDS，后续应迁移到新目录能力。
- Standard Ebooks 完整 OPDS 访问可能要求 Patrons Circle 身份或开源项目授权；PageNest 默认关闭该来源并展示授权状态。
- Open Library 遵守低频使用策略：只在用户点开详情后请求，固定 User-Agent、字段白名单、1 秒限流、2 MiB 响应上限和 24 小时缓存。
- Open Library 的全文/借阅字段只作为描述，不生成获取动作。
- 所有来源跳转均由受信任 ID 生成 HTTPS 白名单页面，不打开服务响应携带的任意 URL。

## 5. 下一续接入口

下一阶段是 **Online Discovery Phase 3：安全下载、导入书架并打开阅读器**。进入实现前先完成独立设计与 TDD 计划，至少覆盖：

1. 每次重定向和最终目标重新校验协议、主机与端口；必要时阻止私网/本机地址。
2. 100 MiB 文件硬上限、超时、取消清理、媒体类型与 EPUB/TXT/PDF 文件头验证。
3. 临时文件同步后再通过现有 `BookImportService` 发布到私有书库。
4. 重复导入、文件名、错误提示、进度、离线重试和应用重启恢复。
5. 导入成功后打开现有阅读器并沿用阅读进度；不得绕过现有私有书库校验。
6. HyperOS 3 ARM64 真机补做网络、格式和后台朗读联合验收。

设计与执行依据：

- `docs/superpowers/specs/2026-08-25-pagenest-online-discovery-design.md`
- `docs/superpowers/plans/2026-08-26-pagenest-online-discovery-phase2-ui.md`

