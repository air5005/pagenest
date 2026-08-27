# HyperOS 系统语音兼容实施计划

1. 在 `SystemTtsEngineTest` 增加空音色列表和指定音色不可用时使用系统默认音色的失败测试。
2. 最小修改 `SystemTtsEngine.start` 的音色选择策略，保留语言与真实播放错误边界。
3. 增加 AUTO 无 Azure 配置时的路由策略测试，并在会话创建处传入有效模式。
4. 运行语音单元测试、应用单元测试、编译、Lint 与安装包构建。
5. 更新版本和测试记录，提交并推送 `master`，创建 GitHub Release 并归档 APK。

