# PageNest Import Compatibility Phase 4 Design

## Context

UI Refresh Phase 3 的阅读器自动化测试已经通过，但全新 API 36 x86_64 模拟器无法把 TXT 与 EPUB 样书加入书架。初始设备日志首先暴露了一个格式无关的失败点：`FileParserImpl` 在解析后对所有格式调用 `MobiParser.getFileCrc()`，而 x86_64 APK 不包含 `libappmobi.so`，导致 TXT 也被 `UnsatisfiedLinkError` 推翻。修复该边界后的复测进一步证明，EPUB 的核心元数据解析本身还会调用 `NativeLib.loadEpub`，因此当前旧框架的 EPUB 能力仍受 ABI 原生库约束。

## Decision

保留现有 TXT、EPUB、PDF、MOBI/AZW3 元数据解析器，保留私有文件存储和 SHA-256 数据库去重。将旧的 MOBI 原生 CRC 调整为尽力补充的兼容字段，先恢复不依赖该原生库的 TXT 导入：

- 原生 CRC 可用时继续保存原值；
- 返回空值、抛出普通异常或 `LinkageError` 时使用 `0`；
- 不因可选 CRC 失败而推翻已经成功的书籍解析；
- 不吞掉 `Error` 的其他严重类型；
- SHA-256 继续作为导入去重与文件身份的权威依据。

## Verification

先为 CRC 成功、空值、普通异常、缺失原生库四条路径编写 JVM 失败测试，再实现最小兼容函数并接入 `DocumentFile` 与 `CachedFile` 两条解析路径。完成后运行模块测试、全项目测试、Lint、APK 构建，并在 API 36 模拟器分别导入真实 TXT 与 W3C EPUB。TXT 用于进入真实正文验证沉浸控制区；EPUB 的 x86_64 失败单独记录，最终兼容性结论留给含目标 ARM64 原生库的 HyperOS 3 真机。

## Non-goals

- 不在本阶段重写 EPUB/TXT 解析器。
- 不移除 MOBI/AZW3 支持。
- 不改变私有存储、安全校验、SHA-256 去重或数据库结构。
- 不把 PDF 阅读器合并进可重排阅读器。
