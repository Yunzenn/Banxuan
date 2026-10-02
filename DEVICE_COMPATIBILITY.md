# Device Compatibility — Android platform and reference validation

## 产品范围与能力边界（2026-10-01）

Banxuan 面向 **Android 9+ / API 28+ 手机与 Full Android 手表**，`minSdk = 28` 不变。
这是产品目标，不是“任何 Android 设备已兼容”的声明。不新增 Wear OS runtime，不支持 Android 8 及以下。
CD12Max 是参考手表与专项认证设备，不是唯一产品目标，也不是软件开发前置。

基础语音、记忆可信界面及有权限边界的设备操作保持同一产品语义；其实际可用性仍需分别验证。
Live2D、动画和部分设备感知按能力渐进增强：Core ABI、GL 或资源预算不满足时退回静态角色，
不把可选渲染失败扩大成基础 App 不支持，也不把 fallback 当成 Live2D 已通过。
不能仅凭 CPU 型号、物理屏幕 dpi 或 Android 版本推断运行能力。

## 验证分工与状态

| 环境 | 用途 | 当前证据边界 |
|---|---|---|
| Android SDK Emulator / API 28 | 自动化、布局、生命周期回归 | W3/W4 软件自动验证通过；不是完整视觉或物理设备验收 |
| 普通 Android 9+ 参考手机 | 真实麦克风、扬声器、网络、权限与生命周期 | PENDING / NOT TESTED，不受 CD12Max 到货影响 |
| CD12Max | 该型号 ABI、ROM、小屏、后台、温度与续航认证 | C1 TARGET VALIDATION PENDING；不阻塞其他设备开发 |

证据：[W3](evidence/tests/W3_VALIDATION.md)、[W4](evidence/tests/W4_VALIDATION.md)。
模拟器只证明自身环境的行为；不能推广为所有手机或手表 PASS。
Connected Voice 的真实 HTTPS/WSS 后端及真实记忆 authority 仍需独立闭环。

## Adaptive UI Round 1 — 软件自动验证（2026-10-02）

PR A 仅迁移文档；下表现已在 PR B 本地 SDK API28 模拟器实际执行，见 [报告](evidence/ADAPTIVE_ROUND_1.md)。
宽高使用 dp；每次须同时记录配置的逻辑显示尺寸和扣除系统栏/键盘后的实际可用窗口。
既有 **410×502 px @320dpi = 205×251dp** 是永久回归 fixture，并非 CD12Max 实测密度；
205×251dp 也不能直接当作扣除 insets 后的内容区。

| 逻辑窗口 fixture | fontScale 1.0 | fontScale 1.3 |
|---|---|---|
| 205×251dp | PASS / SOFTWARE AUTOMATED | PASS / SOFTWARE AUTOMATED |
| 240×320dp | PASS / SOFTWARE AUTOMATED | PASS / SOFTWARE AUTOMATED |
| 360×640dp | PASS / SOFTWARE AUTOMATED | PASS / SOFTWARE AUTOMATED |
| 411×891dp | PASS / SOFTWARE AUTOMATED | PASS / SOFTWARE AUTOMATED |

按最终冻结的 Round 1 Done Definition，每格验证：PTT 可见可按、字幕存在、Settings/Memory 页面
可达、关键文本无重叠、控件无越界、系统可见区域内可操作、Activity 重建后可用。
Memory mutation/编辑表单另在 compact 基线跑既有 28 项回归，不声称八格分别做过所有 mutation。
此前扩大契约中的逐格键盘/旋转测试尚未执行，不能从本轮 PASS 推导；完整视觉审查和真机仍待验。
不扩展成完整横屏、平板或折叠屏设计。

PR B 使用现有 Native Views：尺寸 token 与基于实际可用窗口的 `CompanionLayoutSpec` 分开。
COMPACT 优先保障 PTT 和字幕；EXPANDED 增加角色舞台、字幕空间并限制内容宽度，
沿用 `ProductUi.page()` 的 560dp maxWidth。不要按设备型号分支、复制三套布局或先造复杂能力框架。
只做一轮适配，之后回到 Connected Voice；不改语音、记忆、后端、Live2D 或 Operator 语义。

## 参考设备 Probe

CD12Max 的 Full Android 9/API 28、410×502、4+32GB 等为用户提供规格，不是真机证据或全平台最低配置。
历史记录：2026-09-25 的 `adb devices -l` 为空，只描述当时，不代表当前 ADB 状态。

每台参考真机分别记录：Android release/API、model、CPU ABI（含进程与 APK native 库匹配）、hardware、
物理分辨率、Android 逻辑 density、可用窗口与 insets、RAM、OpenGL ES、GL_MAX_TEXTURE_SIZE、Audio HAL、
AudioRecord、AudioTrack、麦克风、扬声器、SAF、安装、后台行为、耗电及温度。
Live2D 另核 Core ABI、实际上传纹理大小与内存预算并跑 runtime；不能把 8192 原始 atlas 大小作为全平台门槛。
无法取得的数据明确记 UNKNOWN，不使用商品页、其他设备或模拟器补值。

真机执行前不生成虚构的 `DeviceCapabilityReport.md/json`。Probe APK 已有 Device / Audio / Graphics /
Storage 页面和 app-private report 导出骨架。硬件要求放宽不改变数据来源、测试时长或 PASS 标准。
