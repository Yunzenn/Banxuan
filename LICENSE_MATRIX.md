# License Matrix — Frozen Evidence

Product emulator CI (2026-10-09): official Android SDK tooling/system image is used only on
ephemeral GitHub-hosted runners under the SDK distribution terms; no SDK binary is copied
into this repository or app. No new third-party emulator action or application dependency.
This does not change proprietary Cubism/model redistribution exclusions.

| 来源与 commit | License 文件 | 结论 | 分发义务/限制 |
|---|---|---|---|
| `google/material-design-icons@bd8cb85bd4bad964fe6918f79665bb40c3a8efef` | `/LICENSE` | Apache-2.0；Daylight 设置图标 | 原始 pathData 保留，仅调整原生主题 tint 命名空间；完整许可随 APK assets/licenses 分发，修改说明见 evidence/DAYLIGHT_UI.md。 |
| `org.ow2.asm:asm:9.6` | Maven POM / upstream `LICENSE.txt` | BSD-3-Clause；W4 JVM test-only | 复用字节码读取器；不进入 APK。版本固定，不自研 class parser；POM 已核查，不代表完整供应链安全审计。 |
| `78/xiaozhi-esp32@64b57d0...` | `/LICENSE` | MIT | 源码实质复制需保留版权与许可文本。 |
| `xinnan-tech/xiaozhi-esp32-server@788f530...` | `/LICENSE` | MIT | 后端 patch 保留版权与许可文本。 |
| `mdloverm/rokid-xiaozhi@8e3c920...` | `/LICENSE` | MIT，Copyright 2026 DLOVER | 抽取代码时保留 copyright + MIT notice。 |
| `DayanJ/xiaozhi-android-native@5fdc51e...` | `/LICENSE` | MIT，Copyright 2025 DayanJ | 目前仅参考；复制时保留 notice。 |
| `lostromb/concentus@3885c4e...` | `/LICENSE` | BSD 风格三条款/Opus 同类许可 | 源码保留 notice；二进制分发在文档/材料中复现版权、条件和免责声明；不得用贡献者名义背书。 |
| `Live2D/CubismJavaSamples@8ce6803...` | `/LICENSE.md`、`/Core/LICENSE.md` | 多重专用许可 | Java Components：Open Software License；Core：Proprietary Software License；达到条款定义的 business 门槛时需 Release License；样例模型另行授权。发布前必须法律复核。 |
| `Voine/ChatWaifu_Mobile@14092ac...` | 根目录未发现 LICENSE | 无法授予复制权 | REFERENCE ONLY，禁止复制。 |
| `TOM88812/xiaozhi-android-client@30a0c80...` | `/LICENSE` | Apache-2.0 | 保留 LICENSE/NOTICE 与修改说明；目前仅参考。 |
| `stixez/droid-mcp@aeaa5b9...` | `/LICENSE` | Apache-2.0 | 若以后引入，保留许可证/NOTICE 和修改说明。 |

说明：本矩阵不是法律意见。第三方依赖和用户导入模型资产仍需独立审查。

**EXPANDABLE_APPLICATION / RELEASE BLOCKER**：本产品允许用户导入不同模型，需向 Live2D 确认可扩展应用分类及特殊 Publication License；不按个人/小规模主体的一般豁免放行发布。[官方规则](https://www.live2d.com/en/sdk/license/expandable/)（2026-09-25 核查）。开发许可、模型许可与发布许可分别验收。Core 仅从官方 SDK 下载取得，禁止借用第三方仓库 Core 二进制。详见 PHASE_2B_REUSE_GATE.md。

## Phase 2B 补充（2026-09-25）

| 来源与冻结 SHA | License 文件 | 采用与义务 |
|---|---|---|
| CubismJavaFramework `ed15cb21a466893381d1dbddce0da943c7fe9a0f` | `/LICENSE.md` | Live2D Open Software License；与 Core 专用许可和模型许可分开。尚未引入应用；不得代用户接受下载许可 |
| JieRobot/wanyu-ai-android `f873e137e224192fbac72021536054ed4a5ad044` | `/LICENSE` | MIT，Copyright (c) 2026 陈兵。未来实质改编保留版权与全文许可；当前仅审计 |
| moeru-ai/airi `a142a053fdc304666ba7caf8462678b79187f8ea` | `/LICENSE` | MIT，Copyright (c) 2024-PRESENT Neko Ayaka。实质复制需保留；当前仅参考思想 |

Core AAR 未下载，SDK/模型许可等待用户本人审查；商业发布资格与资产分发另审，不能由本次源码审计推导获准。项目作者使用 yunzenn；第三方合法版权头不得删除或冒名替换。证据与范围见 PHASE_2B_REUSE_GATE.md。

Phase 1C Concentus 采用记录：`third_party/concentus/concentus-3885c4e-java.zip` 为冻结 commit 的原始 Java 源码和 LICENSE 归档，未修改 codec，所有原文件版权头保留。生成 JAR 包含 `META-INF/concentus/LICENSE`；APK 分发材料为 `assets/licenses/concentus-LICENSE.txt`。没有引入仓库的原生 Opus 预编译二进制。
