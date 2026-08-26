# PageNest 1.15 在线书源可靠性验证

## 本阶段修复

- 新增 Open Library 公开全文书目作为发现兜底，只纳入
  `public_scan_b=true` 且 `ebook_access=public` 的记录；借阅和预览记录不会
  冒充免费全文。
- Project Gutenberg 榜单只有子目录链接时，根据其公版书编号补充官方
  EPUB3 图片版地址，使榜单书籍可以直接导入阅读。
- 任一来源先返回有效书目后，只再等待 1.5 秒聚合其他来源，不再被单个
  慢来源拖满 20 秒。
- 保留来源失败的安全分类并写入受大小限制的应用诊断日志，不记录响应
  正文、用户查询内容或完整请求地址。
- 自动跳转仍然关闭，避免目录请求被第三方重定向到未授权主机。

## TDD 证据

- 首次运行新增测试时，编译因 `SourceFailure`、Open Library 适配器和注册
  参数不存在而失败。
- 实现基础功能后，快速聚合测试因仍等待完整来源超时而失败。
- 实现有界聚合后，调用方取消测试暴露了子协程取消未回传的问题；修复
  取消传播后仓库测试通过。

## 自动验证

2026-08-26 在 Windows 开发机执行：

```text
:base:testDebugUnitTest
:bookparser:testDebugUnitTest
:bookread:testDebugUnitTest
:mobi:testDebugUnitTest
:app:testDebugUnitTest
:app:assembleDebug
:app:assembleDebugAndroidTest
:app:lintDebug
```

同一 Gradle 门禁结果：`BUILD SUCCESSFUL in 4m 56s`，共 393 个任务，
52 个执行、341 个为最新状态。

真实公共端点检查：Open Library 推荐查询 HTTP 200，约 4 秒返回 20 条公开
记录；英文 Alice 查询 HTTP 200，约 2.7 秒返回 20 条记录，其中 4 条满足
公开全文筛选。Project Gutenberg OPDS HTTP 200，约 3.8 秒返回 56,505 字节。

## APK

- 版本：`1.15.260826`（versionCode 16）
- 本地文件：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：126,253,178 字节

## 真机状态

写入本记录时 `adb devices -l` 没有列出设备，因此尚未执行 1.15 的安装和
在线发现真机验证。此项明确记录为 `NOT RUN (device unavailable)`，不能据此
声称目标 HyperOS 手机已经验证通过。重新连接并授权后应验证推荐、热门、
最新、英文搜索、Gutenberg EPUB 导入以及“运行日志”中的来源失败分类。

