# PageNest 在线图书安全下载与导入设计（Phase 3）

日期：2026-08-26  
状态：已确认（按用户授权自动采用推荐方案）

## 1. 目标与完成边界

在在线图书详情页为明确标记为“免费全文”的 EPUB、TXT 和可提取文字 PDF 提供“一键加入书架”和“开始阅读”。应用先把文件下载到应用私有临时区，完成网络、大小和格式边界检查，再交给现有 `BookImportService` 执行 DRM/保护检查、SHA-256 去重、私有书库原子发布、解析和数据库写入。

“加入书架”成功后停留在详情页；“开始阅读”成功后使用现有格式路由进入阅读器。失败或取消不能留下临时文件、半成品私有文件或无效数据库行。

本阶段只处理目录适配器已经产生的 `FREE_FULL + HTTPS` 获取项，不把 Open Library 的描述性全文/借阅字段转换成下载，不启用 HTML 转换，不绕过登录、会员、DRM 或访问控制。

## 2. 方案比较与选择

### 方案 A：网络响应直接作为 `BookImportService` 输入流

磁盘写入最少，但下载、网络连接、私有书库发布和解析生命周期耦合。重定向、响应头、进度、文件头和取消清理难以在清晰边界内验证，也会让现有导入服务承担网络职责。

### 方案 B：应用私有临时文件后交给现有导入服务（推荐并采用）

下载器只负责安全网络与临时文件；`BookImportService` 继续负责可信私有书库和书目导入。会多一次私有磁盘读取，但边界清楚、容易 TDD、取消可清理，并且不会削弱已经经过大量测试的导入链路。

### 方案 C：Android `DownloadManager`

适合面向用户的长期下载，但重定向逐跳检查、DNS/私网策略、临时文件所有权和精确错误映射不够可控，不适合作为首个安全实现。

采用方案 B。

## 3. 组件边界

```text
OnlineBookDetailScreen
        │ 加入书架 / 开始阅读 / 取消
        ▼
DiscoveryViewModel
        ▼
OnlineBookImportCoordinator
  ├── OnlineImportLedger（已导入映射）
  ├── SecureBookDownloader
  │     ├── DownloadUrlPolicy
  │     ├── PublicAddressDns
  │     ├── 手动重定向（最多 3 跳）
  │     ├── 100 MiB 流式上限
  │     └── DownloadedBookValidator
  └── BookImportService（现有）
        ▼
私有书库 + Room catalog
        ▼
HomeViewModel.openDashboardBook(bookId)
        ▼
现有 EPUB/TXT/PDF 阅读器
```

网络下载和导入使用独立接口，JVM 测试通过假传输或 MockWebServer/MockEngine，不访问公网。

## 4. URL、重定向与 DNS 安全

- 仅允许 `https`，显式或默认端口必须为 443；拒绝用户名、密码和片段。
- 来源与允许主机绑定：Gutendex/Gutenberg 仅允许 `gutenberg.org`、`www.gutenberg.org`；Standard Ebooks 仅在取得授权后允许 `standardebooks.org`、`www.standardebooks.org`。
- 初始地址以及每个 `Location` 跳转都重新解析和校验；相对跳转基于当前响应地址解析；最多 3 跳。
- 禁止协议降级、未知来源、未知主机、非 443 端口、凭据、无效国际化主机、控制字符和过长地址。
- HTTP 客户端自动重定向关闭，自动失败重试关闭，避免在策略层之外发出请求。
- 生产下载客户端使用同一连接栈的自定义 DNS：解析结果若包含 loopback、link-local、site-local、multicast、any-local，或 IPv4/IPv6 私有、共享地址、文档/保留范围，则整个解析失败。这样校验结果直接供该连接使用，避免“先检查、再由另一套 DNS 连接”的明显竞态。
- Android 网络安全配置继续禁止明文流量；TLS 使用系统信任根，不为第三方公共目录设置易失效的证书 pin。

## 5. 下载与文件验证

- 连接超时 10 秒、读取超时 30 秒、单次完整操作 120 秒。
- 响应必须是 2xx；401/403 映射为无权限，404/410 映射为链接失效，429/5xx 映射为可重试来源失败。
- `Content-Length` 大于 100 MiB 立即拒绝；缺失或伪造长度时仍按实际解码后字节计数，在第 100 MiB + 1 字节终止。
- 文件只写入 `cacheDir/online-import-staging`，随机生成 `.part` 名称，不使用响应文件名、书名、查询或 URL 作为路径。
- 写入完成后刷新并同步文件描述符，再验证格式；验证成功才把 `.part` 交给导入协调器。无论成功、失败或取消，协调器最终删除临时文件。
- EPUB：要求 ZIP 文件头，并确认 ZIP 内未压缩 `mimetype` 项为 `application/epub+zip`；压缩包结构的深层解析继续由现有解析器负责。
- PDF：要求 `%PDF-` 文件头；是否可提取文字继续由现有 PDF 导入/阅读链路验证。
- TXT：允许 UTF-8 BOM，拒绝 NUL 字节和明显二进制内容；名称使用经过清理的书名加 `.txt`，最终标题仍由现有 TXT 导入规则保留。
- 响应 MIME 作为附加约束：明确冲突时拒绝；缺失或 `application/octet-stream` 时以期望格式与文件头为准。

## 6. 获取回退、去重和持久状态

- 候选只取 `OnlineAcquisition.canReadDirectly == true`，按既有 `qualityPriority`、格式和来源稳定排序。
- 每个候选最多尝试一次；安全策略失败不扩大允许列表。404/410、暂时网络错误或服务端错误可尝试下一候选；格式伪装、超限、权限/版权不明确立即停止并向用户说明。
- 导入成功或现有 SHA-256 重复时都得到本地 `bookId`。
- `OnlineImportLedger` 以 `stableKey -> bookId` 记录已导入映射，使用哈希文件名或原子小型存储，不保存下载 URL。每次命中都通过本地书目查询确认书仍存在；无效映射删除后重新获取。
- 同一 `stableKey` 的并发操作由互斥锁合并，防止双重下载；不同图书可以独立进行。
- 应用重启后不恢复半完成下载；启动时安全清理过期 `.part` 文件。已完成私有书籍和 ledger 映射可继续使用。

## 7. UI 与交互

- 有可读获取项时详情页显示“加入书架”和主按钮“开始阅读”；无可读项仍只显示“查看来源”。
- 下载状态显示确定或不确定进度、当前阶段（正在下载/正在校验/正在导入）及取消操作。
- 加入成功显示“已加入书架”；再次操作命中有效 ledger 时不联网。
- “开始阅读”成功把 `bookId` 交回 `HomeScreen`，复用 `HomeViewModel.openDashboardBook` 生成 EPUB/TXT/PDF 路由。
- 页面离开或用户点击取消会取消当前作业；旋转等 Compose 重组不应重复启动。
- 错误使用类型化状态本地化展示，不把完整 URL、服务器正文、查询、私有路径或异常原文显示给用户或写入日志。

## 8. TDD 与验收

- URL 策略：协议、主机、端口、凭据、相对/绝对跳转、3 跳上限、恶意 `Location`。
- 地址策略：IPv4/IPv6 公网与 loopback/link-local/site-local/multicast/保留地址，混合解析结果必须失败关闭。
- 下载器：超时、状态码、长度头、流式 100 MiB + 1、取消、同步失败、临时文件清理、MIME 与 EPUB/TXT/PDF 文件头。
- 协调器：候选排序、回退边界、同书并发、ledger 命中/失效、Imported/Duplicate、导入异常和取消清理。
- ViewModel/Compose：首页行为不回归，按钮可用性、进度、取消、加入状态和开始阅读回调。
- API 36 模拟器至少完成 TXT 下载夹具→导入→书架→打开正文；真实公网只做独立低频冒烟，不进入普通自动测试。
- ARM64 HyperOS 3 最终验证 EPUB、TXT、可提取文字 PDF、弱网取消、后台语音与阅读进度。

## 9. 版本与发布

Phase 3 完成版本推荐为 `1.11.260826`（versionCode 12）。所有 JVM 测试、主 APK、测试 APK、Lint 和可用设备验收通过后，推送 `master`，创建 `pagenest-v1.11.260826`，并核对 GitHub Release APK 与 `SHA256SUMS.txt`。

