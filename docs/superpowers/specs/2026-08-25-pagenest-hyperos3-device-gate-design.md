# PageNest HyperOS 3 Device Gate Phase 5 Design

## Context

PageNest 1.9.260825 已在 API 36 x86_64 模拟器完成 TXT 导入和真实阅读验收，但 EPUB 解析依赖旧框架的 ABI 原生库，后台语音、锁屏控制及五种格式兼容性也只能由目标 ARM64 真机给出结论。目标截图记录的系统为 HyperOS `3.0.303.0.WNNCNXM.C11`、Android 16，认证型号 `2407FRK8EC`。

当前 ADB 只连接了 `pagenest_api36` x86_64 模拟器。直接复用不带设备选择的命令有把模拟器结果误写成真机证据的风险。

## Decision

Phase 5 先提供一个可测试的 PowerShell 真机预检门禁，再执行人工和仪器测试矩阵。

预检门禁必须：

- 枚举 ADB 设备并要求显式选中唯一目标序列号；
- 拒绝 `unauthorized`、`offline`、模拟器属性或 `ro.kernel.qemu=1`；
- 要求 Android release 16、SDK 36 和主 ABI `arm64-v8a`；
- 从小米系统属性识别 HyperOS 3，并同时保存完整构建指纹与产品属性；
- 不把认证型号截图当作 `ro.product.model` 的强制等值条件，因为系统显示名称和认证型号可能不同；
- 输出不包含 IMEI、序列号明文之外的个人数据、书名、正文、API Key 或完整用户文件路径；
- 在任一硬门禁失败时返回非零退出码，且不得继续安装或测试；
- 将机器可读 JSON 和便于人工查看的文本摘要写入仓库外的临时证据目录。

## Verification Strategy

使用 TDD 将判定逻辑放入无 ADB 副作用的 PowerShell 模块。先用固定快照覆盖目标 ARM64 真机、x86_64 模拟器、Android 版本错误、ABI 错误和 HyperOS 属性缺失，再实现最小判定逻辑。命令包装器只负责采集属性、调用纯函数和写入证据。

模块测试不需要 Pester，使用仓库内自包含断言脚本，确保新电脑只安装 PowerShell 和 Android Platform Tools 即可运行。

## Device Matrix

预检通过后，依次执行：

1. 安装 GitHub Release `pagenest-v1.9.260825` 并核验版本。
2. TXT、EPUB、MOBI、AZW3、可提取文字 PDF 的导入与打开。
3. 目录、进度保存、显示设置和一键图片换肤。
4. 离线与 Azure 在线朗读、后台/锁屏媒体控制、音频焦点及耳机断开。
5. 连续 60 分钟运行及内存、电量、温度、Crash/ANR 证据。

## Non-goals

- 没有目标手机连接时不伪造 PASS。
- 本任务不绕过 HyperOS 电池、通知或后台权限。
- 本任务不收集 IMEI、API Key、真实书籍正文或不必要的个人设备信息。
- 本任务不以模拟器替代 ARM64 EPUB 与后台语音结论。
