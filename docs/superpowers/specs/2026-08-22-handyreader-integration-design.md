# 页栖集成 HandyReader 设计

## 1. 目标

将 HandyReader 的 Android 源码作为页栖的工程底座合入现有 `D:\pagenest` 仓库，在保留页栖 Git 历史和文档的前提下，尽快得到可构建、可安装、可导入本地书籍的应用。

首个集成里程碑不是重新设计全部界面，而是建立一个受测试保护的可运行基线，确认 EPUB、TXT、PDF、MOBI 和 AZW3 五种无 DRM 文件能够在 Android 16 / HyperOS 3 真机上导入和打开。此后再逐步替换为页栖的产品界面。

## 2. 上游基线与许可证

- 上游仓库：`https://github.com/EucWang/HandyReader.git`
- 导入提交：`48dcb3f8b8e1b27f8e228af0eed26a5311308170`
- 上游许可证：GNU GPL v3
- 页栖导入 HandyReader 后按 GNU GPL v3 发布源代码。
- 根目录保留完整 GPLv3 `LICENSE`，并新增 `UPSTREAM.md`，记录上游地址、提交、导入日期、修改说明和版权归属。
- 第三方组件原有许可证和版权声明必须保留，尤其是 libmobi、OpenJPEG、PDFBox 及其随附源码。
- 只支持用户合法持有的无 DRM 本地文件，不实现或启用 DRM 绕过。检测到加密文件时明确提示“不支持受 DRM 保护的书籍”。

## 3. 合入策略

采用“源码快照导入 + 上游远程”方案，不合并两个仓库的无关 Git 历史。

1. 保留页栖现有 `.git`、`README.md` 和 `docs/`。
2. 从固定上游提交复制 Android 工程源码，不复制上游 `.git`、本地配置、签名文件或构建产物。
3. 将上游原始 README 存档到 `docs/upstream/HandyReader-README.md`，根 README 继续描述页栖。
4. 为页栖仓库添加名为 `handyreader-upstream` 的只读上游远程，方便以后比较和选择性同步修复。
5. 每次同步上游都使用独立提交，并在提交说明中记录上游提交号；不自动覆盖页栖修改。

这种方式让页栖保持清晰的产品历史，同时保留可追溯的源码来源。与 `git merge --allow-unrelated-histories` 相比，它不会把上游全部历史和页栖历史混在一起；与 Git submodule 相比，开发者克隆页栖后无需额外初始化子模块。

## 4. 工程结构

初次导入保留上游模块边界：

- `app`：Android 应用入口、Compose 页面、导航、Room 数据库和依赖注入。
- `base`：跨模块基础模型和工具。
- `bookparser`：TXT、EPUB、PDF、HTML、Markdown 等格式解析入口。
- `mobi`：libmobi、JNI 和 MOBI/AZW/AZW3 转换解析。
- `bookread`：分页、翻页、文本选择和阅读渲染组件。
- `jp2forandroid`：PDF 中 JPEG 2000 图像支持。
- `text2speech`：现有语音接口；首个本地阅读里程碑不扩展在线语音功能。

导入阶段不立即重构模块。先建立可重复构建和特征测试，再按功能切片清理依赖、迁移包名和替换 UI，避免同时改变来源、结构与行为而失去排错基线。

## 5. 构建基线调整

上游源码不能原样作为页栖基线，已确认存在以下阻塞：

- `app/build.gradle.kts` 在配置阶段强制读取未提交的 `key.properties`。
- 工程默认启用 Firebase Analytics、Crashlytics 和 Google Services，不符合首版完整离线定位。
- 应用当前 `targetSdk` 为 35，而页栖目标是 36。
- MOBI 模块依赖 Android NDK 和 CMake，并固定了 NDK 29 预览版本。
- 业务测试大多是 Android Studio 模板测试，无法保护现有行为。

集成时进行以下最小调整：

- 应用标识设置为 `com.air5005.pagenest`，显示名称设置为“页栖”。
- 统一 `minSdk 29`、`targetSdk 36`、`compileSdk 36`。
- 移除 Firebase Analytics、Crashlytics、Google Services 和应用内评价依赖，不要求 `google-services.json`。
- 删除构建阶段对 `key.properties` 的无条件读取；Debug 构建使用 Android 默认调试签名，Release 签名只从未提交的本地配置读取。
- 使用 SDK Manager 安装并锁定可用的 NDK 29 稳定版与 CMake 3.22.1；锁定后的完整版本写入工程和开发文档。
- 增加 `.gitignore`，排除 `local.properties`、`key.properties`、密钥、IDE 文件和构建产物。
- Kotlin/Java 源码包名在可运行基线建立后分批迁移；首个可安装 APK 必须已经使用页栖的 application ID，避免与 HandyReader 正式应用冲突。

## 6. 数据与导入流程

页栖沿用此前确认的复制导入方案：

1. 用户通过 Android Storage Access Framework 选择文件。
2. 应用校验扩展名、MIME 类型、可读性、文件大小和是否受 DRM 保护。
3. 合法文件复制到应用私有目录；原文件不被修改或删除。
4. 解析模块提取标题、作者、封面、目录和格式信息。
5. Room 保存书架记录、私有副本位置、解析状态和阅读进度。
6. 阅读器通过统一的书籍标识打开私有副本，不长期依赖外部 URI 权限。

导入采用幂等设计。内容哈希相同的文件再次导入时提示重复，不创建第二份私有副本；解析失败时删除未完成副本并保留可诊断的错误类型。

## 7. 错误处理

- 文件不可读：提示检查文件权限或重新选择。
- 格式与扩展名不匹配：拒绝导入并说明检测到的格式。
- DRM 文件：拒绝打开，不尝试解密。
- 存储空间不足：在复制前检查可用空间，失败时清理临时文件。
- 解析器异常：转换为稳定的领域错误，不把原始堆栈直接显示给用户。
- 原生库加载失败：记录 ABI、Android 版本和库名，并显示当前设备不受支持的提示。
- 数据库或应用升级失败：不静默清库；通过迁移测试保护用户书架和进度。

## 8. TDD 与验证策略

导入既有源码本身作为一次可追溯的供应链快照提交，不伪装成新写功能。对页栖进行的每项行为修改均执行 RED → GREEN → REFACTOR。

### 8.1 特征测试

在重构现有行为前补充特征测试，覆盖：

- 五种目标扩展名的格式识别。
- 无 DRM 与 DRM 文件的处理边界。
- 私有目录复制、重复导入和失败清理。
- 书籍元数据和阅读进度的持久化。
- 阅读位置恢复和分页设置转换。

测试样书必须是可再分发的无 DRM 小型夹具，来源和许可证记录在测试资源说明中。DRM 测试使用自制的最小无内容结构夹具，不提交受版权保护的商业书籍。

### 8.2 自动化验证

每个实施切片至少运行：

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

涉及 JNI/原生解析器时，还要执行对应模块测试并验证 `arm64-v8a` APK 中包含所需 `.so`。首版只保证目标手机所需的 `arm64-v8a`，保留 `armeabi-v7a` 兼容性但不将其作为发布阻塞项。

### 8.3 HyperOS 3 真机验收

在目标 Android 16 / HyperOS 3 手机上：

- 通过 ADB 成功安装 Debug APK。
- 冷启动无崩溃，离线状态可进入书架。
- 分别导入 EPUB、TXT、PDF、MOBI、AZW3 测试书。
- 每种格式至少完成打开、翻页、退出、恢复进度。
- 重启应用后书架和进度仍存在。
- 拒绝 DRM 文件，且不留下损坏书架记录或临时文件。

## 9. 实施切片

1. 导入固定上游源码快照、许可证和来源记录。
2. 安装 NDK/CMake 并清理构建阻塞，得到页栖 Debug APK。
3. 删除联网分析依赖，设置页栖标识与 Android 36 基线。
4. 通过特征测试固定导入、解析、书架和阅读进度行为。
5. 在测试保护下实现私有目录导入和 DRM 拒绝策略。
6. 在 HyperOS 3 真机执行五种格式的冒烟验收。
7. 记录基线问题，后续按独立规格逐步重做书架和阅读器 UI。

每个切片保持可构建并独立提交。上游源码导入与页栖适配不得压成一个巨大提交，以便审计、回退和定位问题。

## 10. 完成标准

本次集成在同时满足以下条件时完成：

- HandyReader 固定版本源码已进入 `D:\pagenest`，且来源与许可证可追溯。
- 页栖仓库原有 README、开发文档和 Git 历史保留。
- Debug 构建不依赖私有密钥、Firebase 配置或外部后端。
- 包名为 `com.air5005.pagenest`，目标为 Android 36。
- 自动化测试、Lint 和 Debug 构建通过。
- APK 可安装到目标 HyperOS 3 手机。
- 五种无 DRM 格式完成基本阅读验收，DRM 文件被安全拒绝。
- 所有新增和修改代码已按 TDD 流程提交，已知限制记录在文档中。
