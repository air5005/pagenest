# PageNest 1.16 HyperOS 语音兼容验证

## 修复内容

- HyperOS 系统语音已初始化但不公开音色列表时，使用系统默认音色朗读。
- 保存的 Azure 音色不适用于系统引擎时，自动选择同语言的本地音色。
- AUTO 模式只有在用户已同意联网且 Azure Key 已配置时才尝试 Azure；其他情况直接使用系统语音。
- 明确的联网音色不会被错误当成本地音色使用；缺语言包、不支持语言及真正播放失败仍保留精确错误。

## 自动验证

- `SystemTtsEngineTest` 新增 HyperOS 空音色枚举和跨引擎音色回退测试。
- `ReaderSpeechRoutingPolicyTest` 覆盖 AUTO 的授权、配置和显式模式边界。
- 发布门禁已通过：`:base:testDebugUnitTest`、`:bookparser:testDebugUnitTest`、`:bookread:testDebugUnitTest`、`:mobi:testDebugUnitTest`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug`，共 393 个 Gradle 任务，构建成功且无 Lint 错误。
- 本地 APK 元数据：`com.air5005.pagenest`、versionCode 17、versionName `1.16.260827`、minSdk 29、targetSdk 36。
- 本地 APK SHA-256：`96bba078b28dc113c04328e16cd9cf8c7c4dae9c2f366f9dc487ca2ea0581395`。

## 真机状态

`adb devices -l` 未列出设备，因此不声明真机通过。安装 1.16 后需用《程序员的思维修炼》回归：进入阅读页、点击听书、确认中文连续朗读、暂停/继续/上一段/下一段及后台播放。

## 版本

- versionCode：17
- versionName：`1.16.260827`
- 标签：`pagenest-v1.16.260827`
