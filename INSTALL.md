# Banxuan 新版测试包：下载与安装

**[下载 Banxuan 0.4.1-preview APK](https://github.com/Yunzenn/Banxuan/releases/download/v0.4.1-preview/banxuan-0.4.1-preview.apk)**

新版包含 Daylight 浅色界面和手机/小屏窗口适配，Android 9+ 可尝试安装。
仍是 CI Debug Preview，不是稳定签名版，真实 AI 语音和记忆后端尚未接通验收。

1. 在 Android 浏览器中点击上面的下载按钮。
2. 在「下载」中打开 banxuan-0.4.1-preview.apk，按系统提示允许当前来源安装。
3. 打开「小星陪伴 · 预览」，体验首页、设置和「我的记忆」。

**已有旧版不要直接卸载。** CI 调试签名可能不同，不能保证覆盖安装。
卸载会清空设备身份、设置和缓存，可能需要重新绑定。遇到签名冲突先保留错误信息。

## 常见问题

- 下载打不开：打开[发布页](https://github.com/Yunzenn/Banxuan/releases/tag/v0.4.1-preview)，在 Assets 中选择上述 APK，不要选 Source code 或 androidTest APK。
- 还是旧界面：检查是否误下了旧的 banxuan-preview-v0.4.apk。
- 没有回复或记忆：后端尚未部署验收，不是需要购买解锁，也不能直接判断麦克风坏了。
- 能否自动升级：目前没有内置更新器、稳定签名或数据迁移保证。
- 是否含 Live2D：默认包只有静态角色，不含 proprietary Core 或用户模型。

## 校验与版本

versionName = 0.4.1-preview；versionCode = 5；minSdk = 28。
发布页附 SHA256SUMS.txt、BUILD_INFO.json、APK_BADGING.txt、APK_SIGNATURE.txt、许可文件。
请以本版 BUILD_INFO 的提交和 SHA-256 为准，不沿用旧包校验值。
[适配报告](evidence/ADAPTIVE_ROUND_1.md) 是同一 UI 代码的本地软件证据，不是真机或跨签名升级认证。

PR/main 临时测试包仍在 [Actions](https://github.com/Yunzenn/Banxuan/actions/workflows/ci.yml) 的 Artifacts 中，
通常需要登录 GitHub，保留 14 天；普通用户优先下载本页顶部的版本化 Release。
[旧版归档](https://github.com/Yunzenn/Banxuan/releases/tag/v0.4.0-preview) 保留，不覆盖旧附件。
[反馈问题](https://github.com/Yunzenn/Banxuan/issues)时请遮住个人信息、身份和凭据。
