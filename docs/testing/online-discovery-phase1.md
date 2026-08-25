# 在线发现 Phase 1：目录核心验证记录

日期：2026-08-26  
分支：`master`

## 1. 本阶段完成内容

- 建立与来源无关的在线书目、版权状态、获取格式和分页契约。
- 接入 Gutendex JSON 目录，支持推荐/热门、最新收录、搜索、题材和中英文筛选。
- 建立禁用 DTD、外部实体和 XInclude 的安全 OPDS 1.x XML 解析器。
- 配置 Project Gutenberg 官方 OPDS 与 Standard Ebooks OPDS 来源。
- 仅将“明确免费全文 + HTTPS”的 EPUB/TXT 标记为可直接阅读；HTML 只作为外部入口。
- 使用显式 ID 和保守元数据指纹跨源去重，Standard Ebooks EPUB 优先，其他 EPUB/TXT 保留为回退。
- 使用 Reciprocal Rank Fusion 融合各来源排名，不跨来源比较下载量。
- 建立 SHA-256 文件名、原子发布和 4 MiB 上限的目录缓存。
- 建立来源级 8 秒超时、部分成功、稳定警告顺序和陈旧缓存回退。

本阶段只提供数据核心，不增加底部“发现”入口，不包含 UI、Open Library 丰富信息或在线下载导入。

## 2. 自动化测试证据

所有外部响应均来自本地固定样本或 Ktor MockEngine，自动测试没有访问真实网络。

固定样本共 5 个：

- Gutendex：正常热门目录、畸形 JSON。
- OPDS：Gutenberg 热门目录、Standard Ebooks 目录、XXE 攻击文档。

在线发现新增测试共 44 个：

| 测试组 | 数量 |
| --- | ---: |
| 在线目录模型 | 3 |
| Gutendex 适配器 | 10 |
| OPDS 安全解析器 | 3 |
| OPDS 来源适配器 | 8 |
| 跨源合并 | 5 |
| RRF 排序 | 3 |
| 文件缓存 | 5 |
| 容错仓库 | 7 |

完整门禁命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

结果：

- Gradle：`BUILD SUCCESSFUL in 4m 30s`
- JVM 测试：469，通过 469，失败 0，错误 0，跳过 0。
- Lint：错误 0；现有警告 148；其他提示 5。
- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`
  - 大小：126,083,638 字节
  - SHA-256：`E1BAD53DBE324FFCDF5F8FE8CE3F5D344B32B9FCEF2C431575BF98DF9610D0AE`
- 测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - 大小：3,772,592 字节
  - SHA-256：`03A2FFFCBBAF31C4A0B253F8C65CAAD8F895240B771B1EF05EBD51FDAE2BDA54`

## 3. 安全边界

- JSON/XML 响应硬上限均为 2 MiB。
- OPDS 禁止 DOCTYPE、外部通用实体、外部参数实体、XInclude 和实体展开。
- 基础地址、分页地址、封面和获取地址均按配置校验 HTTPS 与允许主机。
- HTTP 错误、畸形响应和超限响应不会把响应正文、书名、查询词或获取 URL写入异常消息。
- 缓存文件名只含请求规范串的 SHA-256，不包含查询词；写入先同步临时文件再原子替换。
- 单一来源失败不影响其他来源；全部来源失败时可返回陈旧缓存；调用方取消会原样传播。

## 4. 真实服务限制与未覆盖项

- 本阶段未以真实公网请求做发布结论，不能据此声称外部服务始终可用。
- Project Gutenberg 官方说明 XML OPDS 入口当前为 `https://www.gutenberg.org/ebooks/search.opds/`，并预计在 2027 年停用旧 XML OPDS；后续需要规划 OPDS 2.0 迁移。
- Standard Ebooks 官方 OPDS 地址为 `https://standardebooks.org/feeds/opds`，但完整 OPDS 访问可能要求 Patrons Circle 身份或向其申请开源项目权限。当前适配器不内置凭据；若服务返回 401/403，容错仓库会把该来源标记为不可用，不阻断 Gutenberg/Gutendex。
- Phase 2 在展示 Standard Ebooks 前应决定：取得开源项目授权并安全配置凭据，或默认关闭该来源，仅保留代码能力。
- 在线获取文件的重定向、最终 URL、媒体类型、文件头、大小上限和私有书库发布尚未实现，必须在独立 Phase 3 安全门禁中完成。
- HyperOS 3 ARM64 真机和真实弱网验证仍待设备连接后执行。

官方依据：

- Project Gutenberg OPDS：<https://www.gutenberg.org/ebooks/offline_catalogs.html>
- Standard Ebooks feeds：<https://standardebooks.org/feeds>

## 5. 下一续接入口

下一阶段是 **Online Discovery Phase 2**：

1. 规划 Open Library 低频丰富信息与缓存/限速。
2. 增加“书架 / 发现 / 听书 / 我的”四项底部导航。
3. 实现蓝绿渐变的发现首页、推荐/热门/最新/来源标签、中英文筛选和分类入口。
4. 实现书籍详情页，明确显示来源、版权和格式；暂不启用未通过 Phase 3 的直接下载。
5. 增加 ViewModel、Compose 页面、无障碍语义和屏幕级测试。

设计与计划：

- `docs/superpowers/specs/2026-08-25-pagenest-online-discovery-design.md`
- `docs/superpowers/plans/2026-08-25-pagenest-online-discovery-phase1-catalog-core.md`

