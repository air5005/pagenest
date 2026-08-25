# PageNest Import Compatibility Phase 4 Design

## Context

UI Refresh Phase 3 的阅读器自动化测试已经通过，但全新 API 36 x86_64 模拟器无法把 TXT 与 EPUB 样书加入书架。设备日志证明文件扫描、私有存储、EPUB 元数据读取均已执行，失败发生在元数据解析结束后的旧 CRC 补充步骤：`FileParserImpl` 对所有格式调用 `MobiParser.getFileCrc()`，而 x86_64 APK 不包含 `libappmobi.so`，导致 `UnsatisfiedLinkError` 被导入服务归类为 `PARSE_FAILED`。

## Decision

保留现有 TXT、EPUB、PDF、MOBI/AZW3 元数据解析器，保留私有文件存储和 SHA-256 数据库去重。将旧的 MOBI 原生 CRC 调整为尽力补充的兼容字段：

- 原生 CRC 可用时继续保存原值；
- 返回空值、抛出普通异常或 `LinkageError` 时使用 `0`；
- 不因可选 CRC 失败而推翻已经成功的书籍解析；
- 不吞掉 `Error` 的其他严重类型；
- SHA-256 继续作为导入去重与文件身份的权威依据。

## Verification

先为 CRC 成功、空值、普通异常、缺失原生库四条路径编写 JVM 失败测试，再实现最小兼容函数并接入 `DocumentFile` 与 `CachedFile` 两条解析路径。完成后运行模块测试、全项目测试、Lint、APK 构建，并在 API 36 模拟器导入真实 TXT 与 W3C EPUB，进入正文验证沉浸控制区。

## Non-goals

- 不在本阶段重写 EPUB/TXT 解析器。
- 不移除 MOBI/AZW3 支持。
- 不改变私有存储、安全校验、SHA-256 去重或数据库结构。
- 不把 PDF 阅读器合并进可重排阅读器。
