# Banxuan Debug Preview 0.4.1

**[下载新版 APK](https://github.com/Yunzenn/Banxuan/releases/download/v0.4.1-preview/banxuan-0.4.1-preview.apk)** · [安装说明](INSTALL.md)

标签 v0.4.1-preview；versionName 0.4.1-preview；versionCode 5；minSdk 28。
精确构建提交和 APK SHA-256 以本 Release 的 BUILD_INFO.json / SHA256SUMS.txt 为准。

## 更新内容

- Daylight 浅色 Home / Settings / Memory 和静态角色。
- Adaptive Round 1：按实际窗口分配角色、字幕和 PTT，保留字体大小。
- UI 软件矩阵 8/8，既有界面回归 38/38，app JVM 34/34。
- 原始失败与修复、验收边界见 evidence/ADAPTIVE_ROUND_1.md。

不是 v1、完整 Alpha 或第三方 XiaoZhi APK。没有默认测试记忆，普通安装显示「记忆服务尚未连接」。
真实 HTTPS/WSS 语音、参考手机、CD12Max、物理音频与完整视觉验收尚未完成。
默认包不含 Cubism Core、Live2D 模型、客户图片/声音资产。

## 签名与升级

**CI Debug signing，不是稳定更新身份；UPDATE COMPATIBILITY: NOT YET VERIFIED。**
不同构建可能不能覆盖安装，不要直接卸载有数据的旧版。
卸载会清除 identity、设置和缓存，可能需要重新绑定；不承诺已提供导出/导入迁移。
本次按用户要求发布新 Debug Preview，不代表固定签名或 tag 自动发布基础设施已完成。

## 来源与许可

只提升成功 main CI 构建的原始已检查 APK，不二次打包或重签名。
随包保留 BUILD_INFO、SHA256SUMS、badging、signature 和许可；核对构建提交与 tag。
APK 包含 Concentus 许可，自有源码许可见 LICENSE。
旧 v0.4.0-preview 标签和附件保持不变。
