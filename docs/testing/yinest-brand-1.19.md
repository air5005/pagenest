# 羿巢阅读（YiNest）1.19 品牌迁移验证

## 品牌方案

- 中文名称：羿巢阅读
- 英文名称：YiNest
- 品牌含义：“羿”来自两个孩子共同的名字，“巢”代表一家人共同建立的家。
- 品牌标语：为家人筑一座会成长的书巢。

## 本阶段变更

- 中文系统的桌面、欢迎页和应用内名称统一为“羿巢阅读”。
- 其他语言的产品名称统一为“YiNest”。
- 在线书源、在线下载和 Azure 语音请求的客户端标识统一为 `YiNest`。
- README 与后续 GitHub Release 标题、APK 文件名改用 YiNest 品牌。
- 新版 Release 标签使用 `yinest-v*`；工作流仍兼容历史 `pagenest-v*` 标签。

## 兼容性边界

- Android 包名继续使用 `com.air5005.pagenest`，保证已安装版本可以原位升级且保留应用数据。
- GitHub 仓库继续使用 `air5005/pagenest`，保证旧克隆地址、Issue 和历史 Release 不失效。
- 内部主题类与源码包路径暂不重命名，它们不会显示给用户，避免无收益的大范围迁移风险。

## TDD 与构建证据

- 品牌资源测试覆盖中文名称、中文欢迎文案和英文欢迎文案。
- 网络契约测试覆盖 Open Library、Azure Speech 与在线书籍下载的 YiNest 客户端标识。
- Release 工作流契约测试覆盖 `yinest-v*` 标签和版本化 YiNest APK 文件名。
- 完整门禁通过：基础、解析、阅读和应用单元测试，Debug 主 APK、Debug 测试 APK及 Lint，共 393 个 Gradle 任务成功。
- APK 元数据：package `com.air5005.pagenest`、versionCode 20、versionName `1.19.260827`、minSdk 29、targetSdk 36。
- APK 标签：默认 `YiNest`、简体中文 `羿巢阅读`。
- 本地 APK 大小：126,289,974 字节；SHA-256：`e13554c0fe33487a44d19bd13e823cebb190f81460ad81370ba29a26238a97ea`。
- GitHub Actions 发布流程成功：<https://github.com/air5005/pagenest/actions/runs/33041703496>。
- GitHub Release：<https://github.com/air5005/pagenest/releases/tag/yinest-v1.19.260827>。
- 云端 APK 文件名：`YiNest-1.19.260827-debug.apk`，大小 126,289,794 字节。
- 云端 APK 下载复核 SHA-256 与 `SHA256SUMS.txt` 一致，均为 `8314ce9bc692b701efe90514daa71d18e152439a1b12cb5f641236949f42d27e`。
- 云端 APK 再次读取确认：package `com.air5005.pagenest`、versionCode 20、versionName `1.19.260827`、默认标签 `YiNest`、简体中文标签 `羿巢阅读`。

## 真机状态

检查时 `adb devices -l` 未列出设备，因此本阶段不声明 HyperOS 3 真机回归通过。安装后应确认桌面名称为“羿巢阅读”，旧版数据仍可访问，并回归导入、阅读、听书和在线发现。

## 版本

- versionCode：20
- versionName：`1.19.260827`
- 标签：`yinest-v1.19.260827`
