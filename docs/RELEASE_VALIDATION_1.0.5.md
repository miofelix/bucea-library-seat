# 1.0.5 发布验证

记录日期：2026-10-21。计划版本 **1.0.5 / versionCode 19**，包名 `cn.edu.bucea.library`，最低 Android 8.0 / API 26。发行说明见 [releases/1.0.5.md](releases/1.0.5.md)。

本次重点验证应用更新按设备 ABI 选择安装包，并保留通用包回退。

## 自动检查

- ABI 选择、通用包回退和非法变体校验已加入 JVM 测试。
- 发布工具测试 44 项、官网构建测试 14 项通过；Debug / Release JVM 测试各 148 项通过，Lint 无 Error。

## 发布与公网

`v1.0.5` Release 已正式发布，Release 工作流 `39594362697` 的 publish 与 website job 均成功。更新清单为 versionCode 19，包含 universal、arm64-v8a、armeabi-v7a、x86 和 x86_64 五个包；ARM64 包大小约 33.9MB，通用包约 53.1MB。
