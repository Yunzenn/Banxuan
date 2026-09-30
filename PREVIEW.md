# Banxuan Debug Preview 0.4

**已发布：[直接下载安装包](https://github.com/Yunzenn/Banxuan/releases/download/v0.4.0-preview/banxuan-preview-v0.4.apk)** · [三步安装教程](INSTALL.md) · [发布页](https://github.com/Yunzenn/Banxuan/releases/tag/v0.4.0-preview)

发布标签 `v0.4.0-preview`，源码提交 `057030884f2c754a316ac52f7e1a84f75ece39fc`。安装后的桌面名称是「小星陪伴 · 预览」。这是已发布版本的说明，不代表后续任意构建也使用同一签名或校验值。

这是开发预览，不是 v1，不是完整 Alpha，也不是第三方 XiaoZhi APK。
Android 9 / API28 起可尝试安装；CD12Max、参考手机和完整视觉验收尚未完成。

## 普通安装实际能体验什么

- 自有首页、角色占位区域、设置、PTT 控件与状态展示。
- 「我的记忆」入口；默认未连接记忆服务器，诚实显示「记忆服务尚未连接」。
- 不包含示例记忆，也不会把测试 fixture 当成真实聊天积累。
- 未配置并验证真实 HTTPS/WSS 服务端，不能宣称开箱即可与线上 AI 完整语音聊天。
- 不包含 Cubism Core、Live2D 模型、客户角色图片或第三方 XiaoZhi APK。

## 已有软件证据不等于默认可用功能

W0–W4 的模拟器/单测覆盖了记忆读取、编辑、确认、忽略、删除、cache freshness、
单实例接线、不确定结果不盲重试、成功后不重复 list。这些行为的设备测试通过注入
测试 gateway 执行；不是已部署记忆服务或真实对话记忆的证据。
W4：API28 SDK 模拟器 410x502@320dpi，28/28 instrumentation；app JVM 32/32。
详见 [W3 验证](evidence/tests/W3_VALIDATION.md) 和 [W4 验证](evidence/tests/W4_VALIDATION.md)。W0–W4 基础设施闭环，不等于真实记忆服务已上线。

## 下载、校验与安装风险

绿色 Actions run 的 `banxuan-preview-<commit>` artifact 提供 APK、SHA256SUMS、
BUILD_INFO、APK manifest 摘要和签名证书摘要。PR artifact 对应临时合并提交，不是正式发布。
首个 GitHub prerelease 只使用绿色 main 构建的同一份 APK，不二次打包/重签名。
校验 `SHA256SUMS.txt` 后再安装，不需要安装 androidTest APK。

**这是 debuggable APK，只供测试，不要存放敏感数据。** 使用 CI 调试证书，未建立稳定发布
签名。不同 CI run/本地构建可能无法覆盖安装；不要为解决签名冲突直接卸载有重要数据的
旧版本。卸载会删除设备身份、设置和缓存，可能需要重新绑定。先确认数据可丢弃。
不能用 W3 曾经卸载旧测试包的运行结果证明跨签名/跨版本身份持久性。

只分发本仓库构建产物。APK 内保留 Concentus 许可，应用代码许可见仓库 LICENSE。
未来启用 Live2D 或附带角色/声音资产，必须重新完成独立发布审查。
