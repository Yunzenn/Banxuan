# Banxuan / 伴星

> 一个以二次元角色呈现、拥有长期个人记忆，并能像 Codex 操作电脑一样通过自然语言**观察、理解和操作整块 Android 手表**的个人 AI Agent。
>
> 内部代号：**Companion Agent Runtime**（腕上陪伴智能体）。

---

## 这不是什么（先说清楚，能省掉大部分误解）

| 常见的误判 | 实际 |
|---|---|
| 手表版 ChatGPT | 不是。目标是一个**长期记住这个用户、会自然说话、能被打断、有角色感**的陪伴体。记忆和人格是核心能力，不是加在聊天框上的装饰。 |
| 通用 Android 应用 | 不是。只为**一个具体型号**开发，硬件约束可以（也必须）写进设计里。 |
| Wear OS 应用 | 不是。目标机是 **Full Android**，我们**不使用** Wear Compose / Wear OS runtime。 |
| 又一个 Live2D 看板 | 不是。Live2D 是可选视觉增强，**不占 Gate 编号、不阻塞任何版本**。 |
| 能跑 shell 的语音助手 | 不是。**永远不给模型 shell。** 工具是 typed 的，`exec_shell("anything")` 不是设计选项。 |
| Banxuan 的"基础版"安装包 | 不是。**本仓库不分发任何第三方 APK。** 被当作参考的那个第三方构建与 Banxuan 无关，见 [Reference XiaoZhi build](#reference-xiaozhi-build)。 |

项目需求正本见 **[`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md)**（接手必读），排期与 Gate 见 **[`ROADMAP.md`](ROADMAP.md)**。

---

## 目标硬件：一个型号，不是一类设备

```text
CD12Max 4+32G
  SoC        Unisoc W527 (12nm, 1x A75 + 3x A55)
  系统       Full Android 9 / API 28
  内存/存储  4 GB + 32 GB
  面板       2.06" AMOLED, 410 x 502 px, ~315 dpi
             -> Android 落在 320 桶 (density 2.0)
             -> 可用画布 205 x 251 dp   ← 不是 410 x 502 dp
  电池       ~1400 mAh
```

> **`205 x 251 dp` 这个换算错误已经造成过一次返工。** 任何视觉判断都必须先把模拟器覆盖成同样的几何，
> 否则结论无效。命令见 [CONTRIBUTING.md](CONTRIBUTING.md#target-hardware)。

电池归面板、麦克风和射频。所以 W527 **不跑**本地 LLM、大 embedding、VITS 或 reranker —— 那是把电和发热花在让产品变差上。

---

## 现在做到哪一步（诚实版）

版本线已冻结为 `v0.1 → v1.0`（见 ROADMAP）。`A+B+C = V1`。

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.1 | Companion Shell — 打开看到角色 / 最近消息 / PTT / 四态 / 设置 | ✅ **完成** |
| v0.2 | Voice Core — 软件内部真正跑通 Session 状态、气泡、埋点、打断 | ✅ **完成**（10/10 契约测试，含精确 `releaseToFirstAudioMs=360`；**模拟器证据**） |
| **v0.3** | Connected Voice — 真能"按住说话 → 听到回复" | 🚫 **BLOCKED：缺一个可达的 HTTPS endpoint** |
| v0.4 | Memory Companion — 记得住，第二次聊天会主动用过去信息 | 🚧 **进行中**，见下 |
| v0.5 – v1.0 | Memory Beta / Native Watch Agent / Integrated Agent / 真机适配 / UI Operator / 首发 | ⬜ 未开始 |

### v0.4 具体进度

v0.4 是当前工作区，且**没有完成**。已落地并各自有证据的部分：

```text
记忆信任面（"我的记忆"）      列出 / 确认 / 忽略 / 二次确认删除 / 行内编辑      18 条 instrumentation PASS
canonical 语义契约            两侧共读同一份契约，双向进 required CI             Kotlin 28 / Python 33 PASS
edit 契约                     内容修改、审计信封、identity 冲突拒绝            14 条原生 + v2 契约 12 例 PASS
远端桥                        canonical memory HTTP v1 + RemoteMemoryGateway   Kotlin 14 / Python 17 PASS
持久缓存                      Room、按 subject 分区、原子整替、无离线队列       19 条 instrumentation PASS
组合层（remote + cache）      remote-first、新鲜度语义、Mutex 串行化           22/23 单测；1 条未解决
```

**组合层是 PR #15，仍是 draft，且有 1 条测试失败，因此没有合并。** 把 `v0.4` 记为"完成"是不诚实的。

### 两个从未读过的设备闸门

`C1 Device Probe` 至今未跑 —— **CD12Max 真机从未连接过**。因此下列全部未验证：

```text
麦克风 / 扬声器 / 真实网络 / 续航 / 后台保活
ro.product.cpu.abilist        # arm64-v8a 是否存在
GL_MAX_TEXTURE_SIZE           # 若 < 8192，Mahiro 的 atlas 在这台设备上不可能上传
糯米OS 厂商 ROM 行为 / AccessibilityService 是否可启用且稳定
```

**这是当前最大的风险。** 模拟器不能替你回答 ABI、GPU 纹理上限、麦克风、扬声器、后台存活或厂商 ROM 行为。

### Live2D 的现状

`P2B-1A = NOT COMPLETE (2/5)`，且 Live2D 现在**完全不在产品构建里**：`:app` 对 Live2D 零编译依赖，
`app/src/main` 无任何 `com.aiwatch.live2d` 引用。debug APK 因此从 ~28 MB 降到 **6.3 MB**。

官方 Cubism SDK 不可再分发，所以 `settings.gradle.kts` 只在 SDK 根目录真实存在时才 include `:core-live2d`。

---

## 三道 Gate

```text
G1  Voice          按住说话 → 听到回复，状态正确，重启仍可用
G2  Memory         隔一天，她主动提起你之前说过的事
G3  Watch Operator 用自然语言操作整块手表
```

**G1 / G2 / G3 都属于 V1，都不是可选项。** 当初把 G3 降级成"加分项"是这个项目走过的一次方向漂移，
现在写死在这里防止再次发生。

Live2D 是一个独立的 **Visual Enhancement Gate**：不占 G 编号，不阻塞任何一个版本。

---

## 架构：手表是薄客户端

```text
watch (CD12Max)                        backend
├── Companion UI                       ASR
│   角色舞台 · 气泡 · PTT · 四态          ↓
├── core-audio                         context builder ←─ memory service
│   PCM · Opus · AudioTrack             ↓                 (候选检索 → 条件式重排)
├── core-memory                         LLM
│   canonical 语义（类型、identity）      ↓
├── core-memory-remote                 TTS
│   HTTP v1 客户端，指向 authority        ↓
├── core-memory-cache-android          流式 PCM/Opus 回传
│   Room，last-known copy（不是 authority）
└── core-protocol ──WebSocket / HTTPS──┘
```

**关键指标**：`t_release → first_audio` 目标 < ~1.2 s。它决定了上下文与记忆必须有多克制 ——
这也是 reranker **只在候选集歧义时才跑**的原因，不是"以后再优化"。

### canonical authority 在服务端，这条线不能被绕过

```text
服务端 CanonicalMemoryService     ← 唯一 authority（dedup / 生命周期 / identity 冲突 / subject 分区）
        ↑
RemoteMemoryGateway
        ↑
watch Room cache                  ← 只是 last-known copy，无任何语义
        ↑
Memory Trust UI
```

反面形态是被明确拒绝的：`DefaultMemoryGateway(RoomMemoryStore)`。它会让去重、生命周期和 identity 冲突
判定回到手表本地执行。缓存模块因此**不实现 `MemoryStore`、不是 `MemoryGateway`**，并且有测试用反射
与真实 SQLite schema 把这一点钉住。

### 模块

| 模块 | 职责 |
|---|---|
| `:app` | 首页 / 对话 / 角色 / 语音 / 主题 / **记忆信任面**。对 Live2D 零编译依赖 |
| `:core-protocol` | Xiaozhi Protocol v1、bootstrap、WebSocket、会话状态机、interrupt |
| `:core-audio` | PCM 组帧、Opus（Concentus）、播放队列、音频设备枚举 |
| `:core-memory` | **唯一的 canonical schema**（PROFILE / EVENT / EPISODE / RELATION）+ `MemoryGateway` 契约。纯 JVM，无 Android |
| `:core-memory-remote` | canonical memory HTTP v1 客户端（OkHttp，HTTPS-only，禁 redirect/retry） |
| `:core-memory-cache-android` | Room 持久缓存。**机械操作，无 canonical 语义** |
| `:core-live2d` | Cubism 适配（**可选**，仅当自备 SDK 时才存在于项目图中） |

---

## 你能帮上什么（按价值排序）

### 1. 一个可达的 HTTPS bootstrap endpoint —— 当前最大阻塞

v0.3 需要的是**真实可用的服务端**，不是代码。如果你能提供（或协助搭起）一个
[`xinnan-tech/xiaozhi-esp32-server`](https://github.com/xinnan-tech/xiaozhi-esp32-server) 实例：

```text
PTT → ASR → LLM → TTS → AudioTrack  并且 t_release → first_audio 有实测数字
```

这会同时解锁 **G1 闭环** 和整条远端记忆链路的真机验证。目前仓库里的记录是：

```text
SERVER ENDPOINT   : MISSING
PATCH SOURCE      : VERIFIED     （冻结 commit 已审计，静态测试通过）
PATCH DEPLOYMENT  : NOT VERIFIED
```

**离线也能帮**：即使没有公网地址，在本地跑起服务端、把 `t_release → first_audio` 的实际分布
（p50 / p95）贴出来，就是有价值的证据。

### 2. 一台 CD12Max，或仅仅是两条设备读数

如果你有这台表（或有同款 ROM 的设备），两条命令就能决定 Live2D 的命运，也能补上 ABI 这个空白：

```bash
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys SurfaceFlinger | grep -i 'GL_MAX_TEXTURE_SIZE'   # 或任意 GL 能力查询
```

更有价值的是把 `DEVICE_COMPATIBILITY.md` 里那份 probe 真正跑完：麦克风、扬声器、后台保活、
`AccessibilityService` 能否启用、真实耗电与发热。

### 3. 复用审计（这个项目的硬性前置）

**Reuse-first 是硬约束，不是偏好**：任何新子系统在设计和动手之前，必须先扫 GitHub、核对 LICENSE、
给出结论，并记入 [`REUSE_AUDIT.md`](REUSE_AUDIT.md)：

```text
DIRECT     直接作为依赖使用
ADAPT      取其模块或实现，改造后使用
REFERENCE  只取设计，不取代码
REJECT     不适用，并说明原因（许可证 / minSdk / 平台 / 体积 / 安全）
```

`.github/ISSUE_TEMPLATE/feature_request.yml` 里**有一个必填字段**要求你写这个结论。
"没有现成方案"而没有搜索记录，不算答案。

当前最需要审计的方向：**v0.5 的候选检索与时间衰减**、**v0.6 的 typed native tools**。

### 4. 设计评审（不需要写代码）

目标屏是 **205 x 251 dp**。如果你有手表 / 小屏交互经验，请挑刺：

- "我的记忆"的信任面（列出 / 确认 / 忽略 / 删除 / 行内编辑）在 205 dp 宽下是否可用
- 待确认、已记住、已忽略三态的信息密度是否合理
- 离线时应该说"当前离线，显示上次同步内容 · 21:34"，这个措辞是否成立

### 5. 找出契约矛盾

这个项目曾经因为**两份文档互相矛盾**而漂移过（§17 与 §18 对 Gate 的描述不一致）。所以：

- 如果你发现 `PRODUCT_REQUIREMENTS.md` / `ROADMAP.md` / `PROTOCOL_CONTRACT.md` /
  `SERVER_AUDIO_CONTRACT.md` / `REUSE_AUDIT.md` 之间有任何不一致，**这是一类高价值 issue**。
- 如果你发现某处声称 `PASS` 但证据不足以支撑，同样高价值。见下面的证据规则。

### 不接受的东西

请不要提交（会被直接拒绝，与代码质量无关）：

```text
Compose / Wear Compose / Wear OS runtime / 任何新 UI 框架
端侧 ASR / LLM / TTS
给模型的 shell 或任意代码执行
third_party/live2d 的任何内容（私有本地依赖，git ls-files 必须为空）
客户角色资产 / 有版权的立绘 / 特定真人声纹
未核实许可证的依赖或代码
把本地 Room 缓存变成第二套 memory authority 的任何改动
```

---

## 这个项目如何定义"完成"

证据纪律是本仓库最重要的规则，也最容易被侵蚀：

```text
没有验证过的结论必须写成 PENDING / UNVERIFIED，绝不写 PASS。

静态证据不是运行时证据。
  "补丁能干净应用 / 单测通过"  ≠  "它在设备上工作"

模拟器结果不是真机结果。
  模拟器无法告诉你 ABI、GPU 纹理上限、麦克风、扬声器、后台存活、耗电或厂商 ROM 行为。

正对照不等于真实结果。
  harness 因为别的组件产生了像素而通过，不能证明被测组件产生了像素。

不声称超出证据的结论。
  例如："confirmed memory 进入了模型请求上下文"（已验证）
        ≠ "模型真的用它回答了"（PENDING，且不得用 fake LLM 刷绿）
```

**PR 如果说某个东西 work，就必须说你是怎么知道的 —— 命令 + 输出。** 这一点在
[CONTRIBUTING.md](CONTRIBUTING.md#evidence-rules) 里有完整版本。

---

## 构建与测试

Gradle wrapper 已提交，用 `./gradlew`。

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :core-protocol:test :core-audio:test :core-memory:test :core-memory-remote:test
./gradlew :app:testDebugUnitTest          # 组合层（纯 JVM，不需要模拟器）
```

- **fresh clone 可直接构建**：`:core-live2d` 只在 SDK 存在时才进入项目图，所以干净检出会正常配置。
- `compileSdk 35` · `minSdk 28` · `targetSdk 35`
- 需要模拟器的验收：先覆盖几何（`wm size 410x502` / `wm density 320`），
  并且**每个测试方法单独一次 `am instrument`** —— 同类同进程整跑会因静态状态污染而出现假失败。
- 本机离线构建的坑（`androidx.annotation` 版本固定等）见 CONTRIBUTING，**不要把它当成通用 Android 规则**。

CI（`.github/workflows/ci.yml`，job 名 `fresh-clone`）只做干净检出能做的事：

```text
仓库卫生        third_party/live2d 未被跟踪；客户资产未被跟踪
JVM 单测        :core-protocol / :core-audio / :core-memory / :core-memory-remote
应用单测        :app:testDebugUnitTest（记忆组合层，纯 JVM）
Python 契约     canonical 语义契约、HTTP 边界、HTTP handler（仅 stdlib，无需 upstream checkout）
组装            :app 与缓存模块的 assemble
```

**CI 不跑模拟器，也不跑 Live2D。** 这是架构声明，不是被跳过的覆盖：
需要 SQLite 行为的缓存测试、需要真实 `Dialogue` 的集成测试，都在模拟器/冻结上游上跑，
不会假装 GitHub runner 验证了它们。

---

## 治理

- **Trunk-based**：只有 `main` 一条长期分支，没有 `develop`。
- `main` 受保护：需要 PR、禁止强推、要求线性历史、必需检查 `fresh-clone`。
  合并方式是 **squash-only**（PR 里的历史不必整洁，落地的那一条必须整洁）。
- 提交遵循 [Conventional Commits](https://www.conventionalcommits.org/)，**保持单用途**
  —— 便于 bisect 分辨"行为改动"与"配套测试"。
- **提交身份是刻意匿名且固定的**（见 [`GIT_PRIVACY.md`](GIT_PRIVACY.md)）。
  有 pre-push 钩子会拦截可达提交中的隐私身份，**不要用 `--no-verify` 绕过**。
- 治理规则（冻结）：

  > **任何一项工作，如果连续两轮没有缩小 G1/G2 的交付缺口，就停下来重新评估，而不是继续深挖。**

  （设立原因：曾有一整个 session 花在 Live2D 上，而它当时是最不产品关键的一环。）

---

## 文档地图

| 文件 | 作用 |
|---|---|
| **[`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md)** | **项目记忆 / 需求正本** — 接手先读 |
| **[`CONTRIBUTING.md`](CONTRIBUTING.md)** | 边界、构建、测试、证据规则、分支与提交、许可证 |
| **[`ROADMAP.md`](ROADMAP.md)** | 冻结的版本线、Gate、架构决定、复用表、范围围栏、治理规则 |
| [`REUSE_AUDIT.md`](REUSE_AUDIT.md) | 每个参考项目的 LICENSE 与用法结论（**提依赖前必查**） |
| [`PROTOCOL_CONTRACT.md`](PROTOCOL_CONTRACT.md) | 协议契约、bootstrap/OTA、会话状态、服务端状态三元组 |
| [`SERVER_AUDIO_CONTRACT.md`](SERVER_AUDIO_CONTRACT.md) | 与 xiaozhi-esp32-server 的音频契约（16k 上行 / 24k 下行） |
| [`SECURITY.md`](SECURITY.md) | 安全与隐私边界 |
| [`GIT_PRIVACY.md`](GIT_PRIVACY.md) | 提交身份与隐私钩子 |
| [`DEVICE_COMPATIBILITY.md`](DEVICE_COMPATIBILITY.md) | 目标设备与 `C1 Device Probe`（**至今未连真机**） |
| [`RISK_REGISTER.md`](RISK_REGISTER.md) | 风险登记 |
| [`DEPENDENCY_DECISIONS.md`](DEPENDENCY_DECISIONS.md) / [`LICENSE_MATRIX.md`](LICENSE_MATRIX.md) | 依赖决策与许可证矩阵 |
| `evidence/` | 契约文件、服务端参考实现、证据脚本与截图 |
| `PHASE_*.md` | 各阶段报告（历史记录） |

---

## Banxuan Debug Preview

绿色 CI 构建提供 `banxuan-preview-<commit>` APK artifact，附 SHA-256、构建提交和签名摘要。
下载和验收边界见 [PREVIEW.md](PREVIEW.md)。这是调试预览，不是完整语音/记忆服务；普通安装
默认显示「记忆服务尚未连接」，不展示测试 fixture。不同调试证书不保证覆盖升级。

## Reference XiaoZhi build

**第三方参考实现，不是 Banxuan 的版本，也不由本仓库分发。**

有人想要一个能跑的腕上语音 / 角色参考实现来对比，这是合理需求。但本仓库**不镜像、不转存、不托管**
那个 APK：再分发权目前没有证据，而**举证责任在我们这边**。方便的代价不能由"别人作品的二进制"来付。
完整规则见 [`REUSE_AUDIT.md`](REUSE_AUDIT.md) 的 *Artifact retention → Distribution rule*。

下面只记录识别事实，不提供下载链接：

```text
This is a third-party reference application, not Banxuan.

Package:
com.huihongcloud.xiaozhi

Version:
1.8   (versionCode 1, label "XiaoZhi")

SHA-256:
42D20DF3DD5C663899BA0FD68F69185F2A21E1C6159F2449B8B173431774625E

Size / ABI:
31,476,468 bytes / arm64-v8a only

Purpose:
Reference implementation for wrist voice/avatar interaction.

Distribution:
Not redistributed by Banxuan.
Obtain it from its original publisher/source.
```

以上字段是从 APK 本身读出来的（`aapt2 dump badging`），不是从随附的安全报告抄来的。该 APK **只含 `arm64-v8a`**
native 库；这与报告的说法一致而非矛盾 —— 报告说的是 `arm64-v8a` 与 `armeabi-v7a` 是**两个独立 APK**，我们手上只有
`arm64-v8a` 那一个。是否存在 `armeabi-v7a` 的兄弟包，我们没有该文件，因此仍未验证。

即使将来找到了它某部分源码的 MIT/Apache 许可证，也**不能自动推出整个 APK 可以由我们托管**：二进制内嵌的
Live2D runtime/native 库、默认角色资产、KWS 模型各自可能有独立的许可条件。

> **不要叫它"基础版"。** `XiaoZhi APK = reference prototype`，`Banxuan APK = our product`。
> 否则装上的人会合理地认为它的 Rhino 脚本执行、Shizuku 权限、服务器工具权限、硬件身份策略也是 Banxuan 的
> 架构选择 —— 而这些恰恰是我们已经明确拒绝的设计。

**我们自己的可分发版本叫 Banxuan Preview**，且必须由我们自己的源码与许可证允许的资产构建。镜像别人的 APK
不能替代这件事：那会让用户停在一条我们无法更新、无法修复、也无法为之负责的升级路径上。

---

## 许可证与边界

本项目代码为 [Apache-2.0](LICENSE)。**仅覆盖本仓库的代码**，不覆盖：

```text
Live2D Cubism SDK         专有，不在本仓库
角色 / 模型资产            客户提供，不可再分发
第三方内置模型             各自条款
concentus / Opus          见 app/src/main/assets/licenses/
```

**IP 角色永远不是可再分发 APK 的默认资源。** 未获授权不得克隆特定真人声纹；
没有授权时产品只提供描述性音色（甜软 / 明亮 / 温柔 / 沉静）。TTS 在服务端，手表只收流式 PCM/Opus。
