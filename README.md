# 羿巢阅读（YiNest）

> 一款面向 Android 与小米 HyperOS 3 的现代阅读应用。
>
> A modern reading app for Android and Xiaomi HyperOS 3.

## 项目简介

羿巢阅读为用户提供本地阅读、个人书架管理、在线书籍发现和语音阅读。名字中的“羿”来自两个孩子共同的名字，“巢”代表一家人共同建立的家。项目仍在持续开发，重点完善阅读体验与 Android / 小米 HyperOS 3 兼容性。

## 已有功能

- 个人书架、书籍详情、分类管理、本地导入与阅读进度记录。
- TXT、EPUB、PDF、MOBI / AZW3、FB2、HTML / HTM 等格式的解析入口，以及音频书籍播放。
- 沉浸式阅读、书签、划线与笔记、阅读统计、字体和主题设置。
- 在线书籍发现、搜索、详情与下载入库。
- 系统 TTS 和 Azure 语音阅读设置、播放服务与诊断入口。

以上为仓库中已有的实现，不代表每种格式、设备或语音服务都已完成发布验收。Azure 在线语音需要用户自行配置服务；系统离线语音依赖设备安装的语音引擎和数据。

## 技术与模块

工程采用 Kotlin、Jetpack Compose / Material 3、ViewModel、Hilt、Room、DataStore 和协程，并包含 C/C++ 原生代码。最低支持 Android 10（API 29），编译和目标 SDK 为 API 36。

| 模块 | 主要职责 |
| --- | --- |
| `app` | 页面导航、书架与业务数据、在线发现、语音阅读 |
| `base` | 公共模型、工具与基础能力 |
| `bookparser` | 书籍文件解析 |
| `bookread` | 阅读排版与页面呈现 |
| `mobi`、`jp2forandroid` | MOBI 和 JPEG 2000 原生解析支持 |
| `text2speech` | 语音合成相关支持 |

## 构建与调试

Windows 环境安装、JDK / SDK 版本、模拟器和真机连接步骤见 [开发环境指南](docs/DEVELOPMENT.md)。配置 JDK 17 与 Android SDK 后，在仓库根目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。项目自带 Gradle Wrapper，无需全局安装 Gradle。

## 开发方式

项目采用测试驱动开发（TDD），遵循以下循环：

1. **RED**：先编写失败的测试。
2. **GREEN**：编写最小实现，让测试通过。
3. **REFACTOR**：在测试保护下重构代码。

## 当前状态

项目已经具备可构建的 Android 应用、单元测试、Lint 检查和设备测试入口，当前配置版本为 `1.20.260905`。Windows 开发工具链与 API 36 模拟器已完成安装及启动验证；后续代码修改仍须重新执行对应检查。

小米 HyperOS 3 / Android 16 真机兼容性、语音后台连续播放及 60 分钟人工验收尚未完成，详见 [语音阅读真机发布门禁](docs/testing/voice-reading-hyperos3.md)。模拟器启动成功不等同于通过该门禁。

## 品牌与兼容性

正式中文品牌为“羿巢阅读”，英文品牌为“YiNest”。Android 包名 `com.air5005.pagenest`、仓库名 `pagenest` 及内部源码包名保留，用于维持原位升级、应用数据和历史链接兼容；详见 [品牌迁移说明](docs/testing/yinest-brand-1.19.md)。

## 项目声明

羿巢阅读是独立开发的阅读应用，与微信读书及其运营主体不存在隶属、授权或合作关系。本项目不会复制其专有代码、商标或受保护资源。
