# Banxuan / 伴星

> Banxuan / 伴星是一款面向 Android 9+ 手机与 Full Android 手表的长期陪伴型 AI Agent。基础能力在兼容设备上保持一致，角色表现、动画和部分设备感知能力根据窗口与硬件能力渐进增强。
>
> 内部代号：**Companion Agent Runtime**（Android 陪伴智能体）。以上为产品目标，尚未全部实现；不代表所有设备已通过兼容性验证。

## 下载测试版：不需要编程

**[⬇ 下载 Banxuan 0.4.1-preview 安装包（Daylight + 窗口适配）](https://github.com/Yunzenn/Banxuan/releases/download/v0.4.1-preview/banxuan-0.4.1-preview.apk)**

[下载安装教程](INSTALL.md) · [发布页与校验文件](https://github.com/Yunzenn/Banxuan/releases/tag/v0.4.1-preview) · [本版能力与限制](PREVIEW.md)

> **新版包含 Daylight 浅色界面与 Adaptive Round 1。** 仍是 CI Debug 签名，不保证覆盖升级；不要直接卸载有数据的旧版。

1. 用 Android 9 或以上设备的浏览器点击下载。
2. 在「下载」中打开 `banxuan-0.4.1-preview.apk`，按系统提示安装。不需要下载 Source code 或测试 APK。
3. 安装后打开 **「小星陪伴 · 预览」**（当前桌面显示名称），体验首页和设置。

**安装前请注意：这是调试测试版，不是正式版。** 不保证与其他构建覆盖升级；遇到签名冲突不要直接卸载旧版，卸载会清空身份、设置和缓存。不要存放敏感数据。CD12Max 真机兼容性仍待验证。

| 现在可以体验 | 现在还不能承诺 |
|---|---|
| 首页、角色占位区域、设置、PTT 控件与状态展示 | 开箱即可和线上 AI 语音聊天 |
| 「我的记忆」入口；未连接时明确提示 | 真实聊天记忆：记忆服务器尚未部署 |
| 自有源码构建的可安装 Preview | Live2D、CD12Max 真机通过、稳定覆盖升级 |

当前里程碑：**Preview 0.4 已发布，Memory W0–W4 软件自动验证闭环；Daylight 已合并，Adaptive Round 1 软件验证通过。**
窗口适配开发提交 `de56be2`：八格矩阵 8/8、既有界面回归 38/38、JVM 34/34、lint 0 错误 / 31 警告。
这不是参考手机、CD12Max、云端或完整视觉验收。发布版本号也不代表完整 Memory Companion 已验收。

**源码界面与已发布 APK 要区分：** Daylight 浅色界面已随 PR #33 合并；本轮在此基础上完成窗口适配，保留 Native Views 和用户字体大小。
新版下载对应独立 `v0.4.1-preview`；旧版标签及附件保留归档，不覆盖旧制品。
本轮验证范围、首轮失败及修复记录见 [Adaptive Round 1 报告](evidence/ADAPTIVE_ROUND_1.md)。

**下一步停止 UI 泛化：** 独立完成固定签名与 tag 发布流程 → 稳定签名 Preview → 关于页检查更新 → Xiaozhi Connected Voice。
PR/main 继续只生成 Debug 测试包；不自动发布用户版本，不承诺旧调试签名可直接升级。

---

## 这不是什么（先说清楚，能省掉大部分误解）

| 常见的误判 | 实际 |
|---|---|
| 手表版 ChatGPT | 不是。目标是一个**长期记住这个用户、会自然说话、能被打断、有角色感**的陪伴体。记忆和人格是核心能力，不是加在聊天框上的装饰。 |
| 支持所有 Android 设备 | 不是。目标为 **Android 9+ / API 28+ 手机与 Full Android 手表**，每类设备仍需验证；不承诺任意 OEM 兼容，不支持 Android 8 及以下。 |
| Wear OS 应用 | 不是。我们**不使用** Wear Compose / Wear OS runtime，也不为兼容性引入新 UI 框架。 |
| 又一个 Live2D 看板 | 不是。Live2D 是可选视觉增强，**不占 Gate 编号、不阻塞任何版本**。 |
| 能跑 shell 的语音助手 | 不是。**永远不给模型 shell。** 工具是 typed 的，`exec_shell("anything")` 不是设计选项。 |
| Banxuan 的"基础版"安装包 | 不是。**本仓库不分发任何第三方 APK。** 被当作参考的那个第三方构建与 Banxuan 无关，见 [Reference XiaoZhi build](#reference-xiaozhi-build)。 |

项目需求正本见 **[`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md)**（接手必读），排期与 Gate 见 **[`ROADMAP.md`](ROADMAP.md)**。

---

## 平台范围与参考设备

`minSdk = 28` 不变，采用 Native Android Views。普通 Android 手机承担真实音频/网络验证，
SDK 模拟器承担自动回归；CD12Max 是参考小屏手表与 OEM 认证设备，不是唯一产品目标或开发前置。
下面是用户提供的参考手表规格，不是所有设备的最低要求：

```text
CD12Max 4+32G
  SoC        Unisoc W527 (12nm, 1x A75 + 3x A55)
  系统       Full Android 9 / API 28
  内存/存储  4 GB + 32 GB
  面板       2.06" AMOLED, 410 x 502 px, ~315 dpi
             -> 模拟器测试采用 320 dpi (density 2.0)
             -> 逻辑显示 fixture 205 x 251 dp；内容可用空间还需扣除 insets
  电池       ~1400 mAh
```

> 上述硬件信息为目标规格，并非真机采集。屏幕物理密度不能直接确定 Android 逻辑密度。
> 当前小屏回归基线是 410×502@320dpi；命令见 [CONTRIBUTING.md](CONTRIBUTING.md#target-hardware)。

手机和手表均采用薄客户端：**不跑**端侧 LLM / ASR / TTS、大 embedding 或 reranker。
Live2D 能力不足时回退静态角色，不让可选渲染决定基础产品可用性；基础能力仍须验收。
四种窗口与两档字体的已执行软件矩阵及未验证边界见 [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md)。

---

## 现在做到哪一步（诚实版）

版本线已冻结为 `v0.1 → v1.0`（见 ROADMAP）。`A+B+C = V1`。

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.1 | Companion Shell — 打开看到角色 / 最近消息 / PTT / 四态 / 设置 | ✅ **完成** |
| v0.2 | Voice Core — 软件内部真正跑通 Session 状态、气泡、埋点、打断 | ✅ **完成**（10/10 契约测试，含精确 `releaseToFirstAudioMs=360`；**模拟器证据**） |
| **v0.3** | Connected Voice — 真能"按住说话 → 听到回复" | 🚫 **BLOCKED：缺一个可达的 HTTPS endpoint** |
| v0.4 | Memory Companion — 记得住，第二次聊天会主动用过去信息 | 🚧 **进行中**，见下 |
| v0.5 – v1.0 | Memory Beta / Native Android Device Agent / Integrated Agent / 参考设备认证 / UI Operator / 首发 | ⬜ 未开始 |

### v0.4 具体进度

记忆可信基础设施 **W0–W4 已关闭 / 软件自动验证 PASS**。完整 v0.4 仍需真实记忆服务和对话集成，不能把基础设施通过等同产品端到端通过。

```text
W0–W2   基线、identity 分区、Room owner、统一 repository 接线   PASS
W3      cache-first、CACHED / STALE / FRESH / NEVER_SYNCED     25/25 instrumentation，28/28 app JVM
W4      单 owner、无绕过、成功不 re-list、不确定结果不盲重试    28/28 instrumentation，32/32 app JVM
```

证据：[W3](evidence/tests/W3_VALIDATION.md)、[W4](evidence/tests/W4_VALIDATION.md)。测试环境为 API28 SDK 模拟器，不能扩大为完整视觉审查、参考手机、CD12Max 或云端验收。普通安装没有注入测试数据，默认显示「记忆服务尚未连接」。

发布基线：`v0.4.0-preview` → `057030884f2c754a316ac52f7e1a84f75ece39fc`；W4 PR #30、分发 PR #31 均已合并。

### 尚未完成的目标设备验收

`C1 Device Probe` 至今未跑 —— **CD12Max 真机从未连接过**。因此下列全部未验证：

```text
麦克风 / 扬声器 / 真实网络 / 续航 / 后台保活
ro.product.cpu.abilist        # arm64-v8a 是否存在
GL_MAX_TEXTURE_SIZE           # 检查实际上传纹理尺寸、预算与 runtime，不以原始 atlas 大小一票否决
糯米OS 厂商 ROM 行为 / AccessibilityService 是否可启用且稳定
```

这是 **CD12Max 专项认证风险**，不是其他设备开发的阻塞。模拟器只能证明自身行为，不能代替参考手机或手表的 ABI、GPU、音频、后台和 ROM 验收。

### Live2D 的现状

当前 main 的 `P2B-1A = NOT COMPLETE (2/5)`，且 Live2D **不在默认产品构建里**：`:app` 对 Live2D 零编译依赖，
`app/src/main` 无任何 `com.aiwatch.live2d` 引用。新版 APK 大小和校验值以对应 Release 制品为准。

专有 Cubism SDK 不随本仓库或本次 Preview 分发。`settings.gradle.kts` 只在 SDK 根目录真实存在时才 include `:core-live2d`；未来启用前仍需独立许可证和兼容性审查。

---

## 三道 Gate

```text
G1  Voice          按住说话 → 听到回复，状态正确，重启仍可用
G2  Memory         隔一天，她主动提起你之前说过的事
G3  Android Device Operator 通过有权限、确认与审计边界的工具操作 Android 设备
```

**G1 / G2 / G3 都属于 V1，都不是可选项。** 当初把 G3 降级成"加分项"是这个项目走过的一次方向漂移，
现在写死在这里防止再次发生。

Live2D 是一个独立的 **Visual Enhancement Gate**：不占 G 编号，不阻塞任何一个版本。

G3 更名不改变 `Native API → Accessibility → visual fallback` 顺序，也不授权任意 shell 或无限制自动化。
这次仅改产品用语，不改代码与协议中的标识符。

---

## 架构：Android 设备是薄客户端

```text
Android phone / Full Android watch    backend
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
device Room cache                 ← 只是 last-known copy，无任何语义
        ↑
Memory Trust UI
```

反面形态是被明确拒绝的：`DefaultMemoryGateway(RoomMemoryStore)`。它会让去重、生命周期和 identity 冲突
判定回到客户端本地执行。缓存模块因此**不实现 `MemoryStore`、不是 `MemoryGateway`**，并且有测试用反射
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

### 2. 参考 Android 手机，以及后续 CD12Max 认证

普通 Android 9+ 手机可先验证真实语音、网络和生命周期；CD12Max 后续独立验证。ABI/GL 是初步事实，
不能仅凭两条读数就宣称 Live2D runtime 或整机兼容性通过：

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

永久保留 **205 x 251 dp** 小屏回归，并扩展手机窗口（完整待测矩阵见 DEVICE_COMPATIBILITY）。请挑刺：

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
  模拟器只能报告自身 ABI/GL 和行为，不能认证参考手机或手表的音频、后台、耗电或 ROM 行为。

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
- 模拟器永久保留 compact fixture（`wm size 410x502` / `wm density 320`）；PR B 扩展矩阵见 DEVICE_COMPATIBILITY，当前仍待执行。
  改几何前记录原值，使用明确 serial；**每个测试方法单独一次 `am instrument`** —— 同类同进程整跑会因静态状态污染而出现假失败。
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
| [INSTALL.md](INSTALL.md) | 普通用户下载、安装、首次打开与常见问题 |
| [PREVIEW.md](PREVIEW.md) | 已发布测试版的能力、证据和限制 |
| [HANDOFF.md](HANDOFF.md) | 当前交接与下一步；旧阶段内容明确标为历史 |
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
没有授权时产品只提供描述性音色（甜软 / 明亮 / 温柔 / 沉静）。TTS 在服务端，Android 客户端只收流式 PCM/Opus。
