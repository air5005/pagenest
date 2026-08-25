# PageNest 在线公版小说发现与阅读设计

## 1. 目标

为 PageNest 增加独立“发现”页面，聚合多个合法免费电子书目录，实时展示热门、最新收录和题材分类。用户点击一本书后，可以查看来源和版权状态，安全下载到 PageNest 私有书库，并直接使用现有阅读器、进度保存、换肤和语音阅读能力。

首版同时展示中文和英文，提供“全部 / 中文 / English”筛选。内容范围仅限数据源明确提供合法免费获取链接的作品，不抓取起点、番茄、晋江等平台网页或私有接口。

## 2. 已确认的产品决策

- 采用多来源聚合，而不是绑定单一 API。
- 首版接入 Gutendex、Project Gutenberg OPDS、Standard Ebooks OPDS 和 Open Library。
- 采用 A 布局：微信读书式内容首页。
- 首页底部导航调整为“书架 / 发现 / 听书 / 我的”。
- 点击“开始阅读”时，透明下载并导入私有书库，然后自动进入现有阅读器。
- 点击“加入书架”时完成相同的安全下载与导入，但停留在详情页并显示已加入状态。
- 后续需要选择时默认采用本文推荐方案，按用户授权自动继续。

## 3. 数据源职责

### 3.1 Gutendex

作为 Project Gutenberg JSON 主适配器，提供热门下载榜、搜索、语言与题材筛选、封面、下载次数及 EPUB/TXT/HTML 获取链接。默认热门按下载量排序；“最新收录”使用 Gutenberg ID 降序，并在 UI 明确标注为“最新收录”，不误写为“最新出版”。

Gutendex 是开源第三方服务。客户端不得把其公共演示实例视为永久 SLA；适配器基地址可通过构建配置替换，以便未来自托管。

### 3.2 Project Gutenberg OPDS

作为 Gutenberg 官方目录及 Gutendex 故障时的备用来源。它与 Gutendex 属于同一内容家族，聚合层应合并同一 Gutenberg ID，不重复展示。

### 3.3 Standard Ebooks OPDS

提供排版质量更高的 EPUB。与 Gutenberg 重复的作品优先采用 Standard Ebooks 的 EPUB，仍保留其他来源作为下载回退。

### 3.4 Open Library

提供实时趋势、封面和书目资料补充。Open Library 不作为无条件全文源；只有响应明确表明全文公开且给出合法获取链接时，才生成“开始阅读”。借阅、预览或无访问权限的条目只显示“查看来源”。

遵守 Open Library 的低频使用要求：请求串行限速到每秒不超过 1 次、使用应用版本和项目地址标识 User-Agent、缓存响应，不批量抓取。

## 4. 统一领域模型

```text
OnlineBook
├── stableKey
├── title / authors / summary
├── languages / subjects
├── coverUrl
├── popularity / catalogUpdatedAt
├── rightsStatus
├── sourceReferences[]
└── acquisitions[]
    ├── sourceId
    ├── format
    ├── url
    ├── accessKind
    └── qualityPriority
```

每个数据源实现 `OnlineCatalogSource`：

```kotlin
interface OnlineCatalogSource {
    val id: String
    suspend fun browse(request: CatalogRequest): CatalogPage
    suspend fun details(reference: SourceReference): SourceBookDetails
}
```

页面和 ViewModel 只依赖统一模型，不直接认识 Gutendex JSON 或 OPDS XML。

## 5. 聚合、排序与去重

`OnlineDiscoveryRepository` 使用独立超时并发查询各来源。单源超时或格式错误只产生来源级警告，其余来源继续返回。

去重顺序：

1. 相同来源的稳定 ID；
2. ISBN、Gutenberg ID、Open Library ID 等跨源标识；
3. 规范化标题 + 第一作者 + 语言组成的保守指纹。

第三层只在字段完整且高置信度时合并，避免把同名不同书错误合并。合并后保留全部来源引用和下载回退链。

不同来源的下载次数不能直接比较。热门聚合采用 Reciprocal Rank Fusion：根据每个来源内部排名计算分数，再叠加高质量可下载格式和元数据完整度的小幅加权。最新页面按来源的“目录收录/更新时间”排序，并显示准确标签。

下载格式优先级：Standard Ebooks EPUB、其他明确可下载 EPUB、UTF-8 TXT。HTML 首版只作为外部查看链接，不转换为本地书籍。

## 6. 页面与交互

### 6.1 发现页

- 蓝绿渐变页头和搜索框。
- 一级标签：推荐、热门、最新、来源。
- 独立语言筛选：全部、中文、English。
- 题材快捷入口：文学、悬疑、科幻、历史、全部。
- “本周最受欢迎”推荐横幅。
- 横向“为你精选”书单。
- 纵向“实时热门榜”。
- 卡片展示封面、书名、作者、语言、最佳格式、来源或下载热度。

首版“为你精选”是透明、可解释的规则推荐：题材筛选、来源质量、热度和元数据完整度，不采集行为画像，不引入账号或服务端推荐系统。

### 6.2 详情页

- 封面、书名、作者、语言、题材。
- “公版来源 / 免费全文 / EPUB”等状态标签。
- 简介和相关作品。
- 优选来源、备用来源、格式及访问状态。
- 无全文权限时显示“查看来源”，不显示“开始阅读”。
- 有全文权限时显示“加入书架”和“开始阅读”。

### 6.3 下载与打开

“开始阅读”进入可取消的下载状态，展示进度。下载成功后调用现有 `BookImportService`，继续执行 HTTPS、大小、格式、DRM、SHA-256 去重、私有存储和解析校验，再使用现有格式路由进入正文。

同一本书已经导入时不重复下载，直接打开本地副本。应用离线时，已下载书籍始终可读；未下载条目显示“当前离线”。

## 7. 网络与安全

- 只允许 HTTPS。
- 每个来源维护明确主机允许列表；重定向每一跳重新校验，最多 3 跳。
- 目录请求超时 8 秒，详情请求 8 秒，下载连接超时 10 秒、整体超时 120 秒。
- 目录响应上限 2 MiB，封面 5 MiB，电子书 64 MiB。
- 不信任响应 MIME 或文件名；下载完成后仍走现有格式检测和保护检查。
- 临时文件原子发布，失败、取消或校验不通过时删除临时文件。
- 不启用 WebView JavaScript 来绕过下载，不执行来源提供的脚本。
- 日志不得包含完整书名、正文、查询词、完整下载 URL 或用户文件路径。
- 不内置用户 API Key。未来需要 Key 的来源必须使用与 Azure Key 等价的安全存储策略。

## 8. 缓存与离线

- 首页/热门缓存 30 分钟。
- 最新收录和题材页缓存 60 分钟。
- 书籍详情缓存 24 小时。
- 封面使用现有图片缓存能力，并限制磁盘占用。
- 缓存使用 stale-while-revalidate：先显示未过期或最近一次成功结果，再后台刷新。
- 所有来源失败时显示最近缓存及“内容可能不是最新”；没有缓存时显示可重试空状态。
- 目录缓存与已下载书籍分离，清除推荐缓存不得删除私人书库。

## 9. 失败处理

- 单来源失败：页面继续展示其他来源，并提供“部分来源暂不可用”说明。
- 全部来源失败：回退缓存；无缓存时显示网络错误和重试。
- 下载链接失效：按优先级尝试同书备用来源，每个来源最多一次。
- 权限或版权状态不明确：禁止应用内下载，只允许打开来源说明页。
- 导入解析失败：保留清晰的格式级错误，不把失败条目加入书架。
- EPUB 在当前 x86_64 模拟器可能受旧原生库限制；在线功能的最终 EPUB 打开结论必须在 ARM64 HyperOS 3 真机补证。

## 10. 测试策略

按 RED-GREEN-REFACTOR 实施：

- 每个来源使用固定 JSON/XML 夹具测试解析、分页、语言、格式和异常响应。
- 聚合器测试并发部分成功、稳定去重、RRF 排序和来源优先级。
- URL 策略测试 HTTPS、允许主机、重定向、大小限制和恶意文件名。
- 缓存测试 TTL、stale-while-revalidate、损坏数据删除和离线回退。
- 获取服务测试下载取消、备用来源、SHA-256 去重和 `BookImportService` 交接。
- ViewModel 测试加载、部分成功、空状态、搜索防抖、筛选和下载进度。
- Compose 测试发现页、语言筛选、详情页访问状态及开始阅读动作。
- API 36 模拟器验证 TXT 下载/导入/打开；ARM64 HyperOS 3 验证 EPUB 与全部真机路径。
- 发布前运行全部 JVM 测试、两个 APK 构建、Lint 和相关设备测试。

普通自动化测试禁止访问真实外部服务，全部通过 Ktor MockEngine 和版本化夹具完成。真实 API 只用于独立、低频、可重复的冒烟测试，不作为常规构建前提。

## 11. 分阶段交付

1. 统一模型、解析器、聚合排序和缓存基础。
2. Gutendex + Gutenberg OPDS + Standard Ebooks OPDS 接入。
3. Open Library 趋势与书目补充。
4. 发现页、详情页和四栏底部导航。
5. 安全下载、私有导入和自动打开。
6. 模拟器/真机验收与 GitHub Release 归档。

每个阶段完成后提交并推送到 `master`；每个正式版本继续通过 GitHub Release 归档 APK 和 `SHA256SUMS.txt`。

## 12. 非目标

- 不抓取或逆向商业中文网文平台。
- 不提供 DRM 绕过、付费内容下载或盗版书源规则。
- 首版不做账号、云同步、评论、社交和付费。
- 首版不实现服务端个性化推荐或用户行为画像。
- 首版不在 WebView 中直接运行未知内容站点。

## 13. 数据源与标准参考

- Gutendex：<https://github.com/garethbjohnson/gutendex>
- Project Gutenberg OPDS：<https://www.gutenberg.org/ebooks/offline_catalogs.html>
- OPDS 2.0：<https://specs.opds.io/opds-2.0>
- Open Library API 使用规则：<https://openlibrary.org/developers/api>
- Google Books API（未来可选来源）：<https://developers.google.com/books/docs/v1/using>
