# ROADMAP — Banxuan / 伴星

## 当前用户主线（2026-10-09）

#38 Memory 真实认证、#39 产品测试隔离、#40 hosted emulator CI 已合并。
当前交付 Native Device Operator：设置内确认调节媒体音量、打开应用、系统时钟计时器交接。
本轮结果与未证范围以 [G3 Native 报告](evidence/G3_NATIVE_OPERATOR.md) 为准，不宣称语音工具链或真机通过。
后续独立做 Accessibility 安全闭环与后台本地提醒；服务器由用户暂缓，真实 Voice/Memory 仍 PENDING。
#37 发布接线需同步 main 后复核，不插队；公开下载仍为 0.4.1-preview，不包含本轮新动作。
下文旧执行顺序作为历史保留，本节优先。

Plan of record. Kept short on purpose: it exists so the Gate is not forgotten and regressions are caught,
not as a development phase of its own.

## 当前执行顺序（2026-10-02）

Preview `v0.4.0-preview` 已发布（源码 `057030884f2c754a316ac52f7e1a84f75ece39fc`），[下载与安装](INSTALL.md)。Memory W0–W4 已闭环：**SOFTWARE AUTOMATED PASS**，不是完整 v0.4 端到端验收。

PR A 产品契约迁移及 Daylight #33 已合并；**PR B Adaptive UI Round 1 本地软件验证通过，尚未发布**。
PR B 只将固定尺寸拆成 token + 基于可用窗口的 `CompanionLayoutSpec`，复用同一套 Native Views；
COMPACT 保住 PTT/字幕，EXPANDED 增加舞台/字幕并限制宽度，保留 ProductUi.page() 的 560dp maxWidth。
不改 Voice、Memory semantics、backend、Live2D integration 或 Operator，不引入机型分支或三套布局。
四窗口 × fontScale 1.0/1.3 **8/8 软件自动验证通过**，见 [报告](evidence/ADAPTIVE_ROUND_1.md)；不等于真机认证。

**停止 UI 泛化**。按最新冻结顺序，下一独立 PR 为 Release Infrastructure：单一版本来源、
受保护 release Environment 固定签名、tag 对应源码完整 gate、APK/BUILD_INFO/tag 的 provenance 校验。
PR/main 只产 Debug artifact，不自动发用户 Release；旧签名迁移须实测 PASS 或 EXPECTED BLOCKED。
该任务当前未实施。其后补轻量 About 更新入口，再回到 Connected Voice（P0），之后才是 Live2D polish。
不扩展平板、折叠屏、完整横屏、旧 Android 或 Wear OS。真实语音顺序不变：

1. S2：核查并复用小智服务端，明确配置、依赖和现有服务边界。
2. localhost HTTP / WS 联调（只用于本地开发验证）。
3. 建立经过认证的公网 HTTPS / WSS 入口，不降低客户端 TLS 要求。
4. Banxuan 真实 PTT → ASR → LLM → TTS → AudioTrack，验证打断、重连和实际音频契约。

真实记忆 authority 尚未部署，接通语音不会自动完成记忆集成。参考手机和 CD12Max 验收仍待完成；Live2D 为非阻塞增强。本轮不再扩展 W5 一类内部架构任务。

## Platform and reference watch profile

产品目标为 **Android 9+ / API 28+ 手机与 Full Android 手表**，minSdk 28 不变。
CD12Max 是参考认证设备，不是唯一产品目标或开发前置；普通 Android 手机做真实音频/网络验证，
SDK 模拟器做自动回归。下面是用户于 2026-09-26 提供的参考规格，不是真机证据或全平台最低配置。

| | |
|---|---|
| SoC | Unisoc W527, 12 nm, 1x Cortex-A75 + 3x Cortex-A55 (ARMv8, 64-bit silicon) |
| OS | 糯米OS, Android 9 / API 28, full Android (not Wear OS) |
| RAM / ROM | 4 GB + 32 GB |
| Panel | 2.06" AMOLED, **410 x 502 px**, 60 Hz |
| Battery | 1400 mAh, magnetic fast charge |

**模拟器布局基线，不是真机密度测量。** 当前测试采用 410×502 px、320 dpi / density 2.0，
对应 **205×251 dp**。屏幕物理密度约 315 dpi 不能证明 Android 的逻辑密度为 320；真机值待采集。
The P0-1 layout was built against the wrong basis and overflowed the
panel (transcript and push-to-talk pushed off-screen) until `CompanionDimensions` was re-derived.
`evidence/screenshots/p0-1-at-real-density-320.png` is the overflow; the re-scaled capture supersedes it.

## Gates

* **G1 — shippable, no Live2D required.** On the device: open app -> see the character -> hold to talk ->
  hear a reply -> correct state -> relaunch still works.
* **G2 — memory works.** A day later it raises something she said before.
* **G3 — Android Device Operator.** Typed, permissioned and auditable Android device actions. **V1 core, not optional.**

Only the product name changes; code/protocol identifiers stay intact. Preserve Native API → Accessibility →
visual fallback and per-action confirmation. No arbitrary shell or unrestricted automation.

## Architecture: thin client

Phones and Full Android watches do character UI, capture, playback and a small cache. ASR, LLM,
long-term memory and TTS live on the server. No on-device LLM, large embeddings, VITS or reranker;
this boundary applies to every target device, not just W527.

```
client: Companion UI (IDLE/LISTENING/THINKING/SPEAKING)
      + core-audio (PCM / Opus / AudioTrack)
      + core-memory semantics (typed canonical schema + gateway contract; JVM module)
      + local cache (recent turns, profile cache, event cache) - durable store is a later increment
      + core-protocol  <-- WebSocket / HTTPS -->  backend
backend: ASR -> context builder -> LLM -> TTS (streamed PCM/Opus back to the client)
                              ^
                    memory service (candidate retrieval -> rerank)
```

**ASR is a first-class backend component** and was missing from the first draft of this plan; it must be
chosen deliberately (streaming vs utterance) because it drives the latency budget.

## Roadmap

**Superseded.** The single roadmap is the frozen product version line `v0.1 → v1.0` further down this file.
The earlier `P0-1 … P1+` list that used to live here was a second, conflicting route and has been removed
on purpose: two roadmaps in one plan-of-record is how the G3 definition drifted apart in the first place.

For reference, the mapping is one-way: `P0-1 → v0.1`, `P0-2 → v0.2/v0.3`, `P0-3…P0-5 → v0.4/v0.5`,
Android Device Operator `→ v0.6/v0.7/v0.9`, `P1 (Live2D) → Visual Enhancement Gate`, `P1+ (Jev-Mem) → v0.5 A/B`.

## Memory model

Five fixed components of every turn's context:

```
recent turns + user profile + relevant long-term memory + time/events/promises + character relationship & mood
```

Memories are **typed**, not just embeddings:

```
EVENT      "下周三下午三点去医院" -> time, importance=high, source=conversation
PROFILE    "不喜欢香菜"           -> food.dislike = 香菜
EPISODE    "昨天和室友吵架了"      -> episode + emotional context + relation:室友
```

The point is to be able to say "你上次不是说和室友闹矛盾了吗" rather than surfacing a random old line.

Retrieval is two-stage, and the reranker is never the first layer:

```
query -> structured filters -> BM25 / embedding / entity / recent events
      -> 20-50 candidates -> Jev Recall rerank -> 3-8 memories -> LLM
```

Reranking is **conditional**, not unconditional: it adds a network hop, and the voice path has a hard
latency budget (target < ~1.2 s to first audio). Rerank only when the candidate set is ambiguous or when the
query is about events/preferences.

## Reuse decisions

Reuse-first. This table records the **decision**; `REUSE_AUDIT.md` is the authority on licence status and
is what the reuse gate checks before any code is adapted. The four memory/voice candidates v0.4 and v0.5
depend on (`wanyu-ai-android`, `mem0`, `jev-recall`, `Jev-Mem`) are recorded there as **VERIFIED**, each
read directly against the upstream LICENSE file. `Voine/ChatWaifu_Mobile` remains unverified and is
therefore REFERENCE ONLY.

| Project | Licence (see `REUSE_AUDIT.md`) | Use |
|---|---|---|
| `JieRobot/wanyu-ai-android` | MIT | **Primary schema/design reference**: `MemoryRepository`, `UserProfileEntity`, `MemoryEntity`, `MemoryLinkEntity`, `EmotionEngine`; importance, time decay, staged confirmation, dedup, character scoping |
| `mem0ai/mem0` | Apache-2.0 | Backend memory store, self-hosted |
| `samdotmak/jev-recall` | MIT | Second-stage reranker (P0-6) |
| `libingzheren/Jev-Mem` | MIT | P1+ A/B only; Python 3.11 research backend, never on-device, never a P0 blocker |
| `marce1994/OpenClaw-Companion` | MIT | PTT, streamed TTS, client state machine |
| `Voine/ChatWaifu_Mobile` | unverified | Product structure reference only; **no code, no models** |
| `moeru-ai/airi` | MIT | Character/persona/mood visual language |

We do **not** import the Wanyu project. We adopt its memory taxonomy and policies into a new `core-memory`
module while keeping the canonical store server-side, because Wanyu is a client-side app and our topology is
client + backend. Two systems must not both claim to be "the memory": one canonical typed schema, with the
vector store as substrate underneath it.

## Character and voice

* `CharacterProfile -> userSuppliedAvatarAsset`. The private custom build may use assets the customer has
  rights to; **the IP character is never a default resource of a redistributable APK**.
* `VoiceProvider { CloudTtsVoice, AuthorizedCustomVoice, GenericStyleVoice }`. If the corresponding licence
  is held, the licensed voice may be wired in; otherwise the product only offers descriptive styles
  (sweet/soft, bright, gentle, calm) and **does not clone a named voice actor's voiceprint**.
* TTS is server-side; the Android client receives streamed PCM/Opus.

## User-visible trust surface

A "我的记忆" screen listing what the companion believes it knows — preferences, recent events, upcoming
commitments — **and letting her edit or delete them**, including confirming or rejecting staged memories.
For a companion product this is not a nice-to-have; it is the trust mechanism.

Personal-life and health-adjacent content ("腰疼", "去医院") lives on a server, so: encrypted at rest,
deletable, and raw audio is not retained longer than transcription needs it.

## Scope fences

```
do not keep chasing Cubism / editing the official Framework; optional rendering is not a base-product blocker
no Compose, no Wear OS runtime, no new UI stack
no Android 8 or earlier; minSdk 28 unchanged
no on-device ASR/LLM/TTS
no multi-character, no character store, no importer
no large animation library for polish
```

## Device gates that are still unread

CD12Max `C1 Device Probe` remains TARGET VALIDATION PENDING. Reference phones can be validated independently.
ABI and GL readings are initial evidence, not sufficient for runtime certification:

```
getprop ro.product.cpu.abilist     # match the packaged Core and process ABI, not just silicon capabilities
GL_MAX_TEXTURE_SIZE                # compare actual uploaded texture sizes and runtime memory budget
```

An unsupported Cubism capability falls back to a static avatar; it does not make the base product unsupported.
Actual audio, protocol, Memory Trust and Operator acceptance remains mandatory for the claimed device support.
Neither a 64-bit SoC nor two readings certify Core runtime; an original 8192 atlas is not a platform-wide minimum.

## Governance rule

Adopted after a full session was spent on Live2D while it was the least product-critical component:

> If two consecutive rounds fail to shrink the G1/G2 delivery gap, stop and re-evaluate rather than dig
> deeper.


## P0-2A — 真实语音闭环（当前工作流）

**性质：产品化接通 + 真实端到端验证**，不是从零实现语音链路。

`DebugAudioSession`（185 行）已经跑通
`AudioRecord → PcmFrameAccumulator(960) → ConcentusOpusCodec → SessionCoordinator → ProtocolEvent →
PlaybackQueue + AndroidPcmPlaybackSink`，并带 tx/rx/readChunks/discardedTailSamples/paddedFinalFrames
等指标。**抽取，不重写。**

冻结范围（不扩）：

1. 把 `DebugAudioSession` 抽成产品可用的 `VoiceSessionAdapter`，复用现有 audio/protocol 代码。
   **`DebugAudioSession` 保留为薄包装**——它背后是 `DebugSessionActivity`、10 分钟模拟器 soak 证据和
   `core-audio` 的 codec 单测，删掉就等于扔掉唯一的 runtime 证据。
2. `CompanionActivity` 的 PTT 绑定真实 `beginCapture / endCapture`。
3. 协议 Conversation 状态映射到 UI 四态。协议侧已定义
   `Idle / Listening / Thinking / Speaking`，打断路径 `Speaking → Interrupting → Listening`。
4. STT 文本进用户气泡，TTS 文本进角色气泡。
5. binary audio 继续走现有 `PlaybackQueue + AudioTrack`。
6. Speaking 时再次按下走已有 `abort()`。"abort 先本地停止/flush/清空播放队列，再发送 abort"
   **已是协议契约**（见 `PROTOCOL_CONTRACT.md`），所以这条是**验证**，不是新设计。
7. 服务端沿用现有 Xiaozhi stack：**不引入 memory、不引入 Jev、不碰 Live2D**。
8. 做一次真实 E2E，记录 `t_release → first_audio`。

**唯一外部 blocker**：一个真实可访问的 `https://.../xiaozhi/ota/`（见 `PROTOCOL_CONTRACT.md`）。

### ASR：只做选型与压测，不自己造

服务端已有多 provider（`selected_module.ASR`，并已区分 `InterfaceType.STREAM`）。
**第一轮只测 4 个，不做全量 sweep** —— 目标是实时陪伴，不是做 ASR 论文：

```text
A. FunASR              本地 baseline：零外部网络下的 final 延迟与准确率
B. DoubaoStreamASRV2   明确流式：重点看首个 partial 与 final 延迟
C. AliyunBLStreamASR   paraformer-realtime-v2，max_sentence_silence 可到 200ms，适合低延迟交互
D. XunfeiStreamASR     另一个成熟中文流式基线
```

无云 API key 时退到 `FunASR + FunASRServer`，**不因选型阻塞产品**。

20 句基准固定记录字段：

```text
utterance_id / duration_ms / t_audio_end / t_first_partial / t_final
partial_latency_ms / final_latency_ms / expected_text / actual_text / CER / success / error
```

**真正决定 P0-2 的是端到端，不是单独的 ASR 分数**：

```text
PTT release → ASR final → LLM → TTS first packet → AudioTrack first audible sample
重点看：t_release → t_first_audio
```

## 产品版本线（2026-09-26 冻结）

不再用"P0 做一大坨"管理。每个版本必须**真的能演示、真的能验收**，而不是"完成了若干模块"。
G1/G2/G3 **都属于 V1**；Live2D 是增强，不占 Gate 编号。

| 版本 | 名称 | 用户能得到什么 | 前置 | 主要验收 |
|---|---|---|---|---|
| v0.1 | Companion Shell | 打开看到角色 / 最近消息 / PTT / 四态 / 设置 | — | ✅ 410×502@320dpi 无溢出；三测试通过 |
| v0.2 | Voice Core | 软件内部真正跑通 Session 状态、气泡、埋点、打断 | 无 | ✅ `1f32417`：P0-2B contract A–J **10/10**（emulator-5554 / API 28）；`releaseToFirstAudioMs=360`；两轮首写埋点恢复；打断顺序 `pauseAndFlush→abort→pauseAndFlush→disconnect→reconnect`、stale samples=0。**模拟器证据，非 CD12Max 真机** |
| v0.3 | Connected Voice | 真能"按住说话 → 听到回复" | **endpoint** | `PTT→ASR→LLM→TTS→AudioTrack`；`t_release→first_audio` ≈ <1.2 s |
| v0.4 | Memory Companion | 小智记得住，第二次聊天会主动用过去信息 | 不依赖指定机型 | CanonicalMemory、画像/事件/经历/关系、Memory Gateway、"我的记忆"可编辑删除 |
| v0.5 | Memory Beta | 记忆从"能存"到"会用" | 不依赖指定机型 | 候选检索、时间衰减、去重、**条件式** Jev rerank、隔天回忆测试 |
| v0.6 | Native Android Device Agent | 真正操作 Android：音量/亮度/闹钟/计时器/日历/App 启动/媒体 | **v0.3** | 5–8 个 typed native tools；Action Card；确认策略；审计日志 |
| v0.7 | Integrated Companion Agent | 陪伴+记忆+操作合进同一个 Agent Planner | v0.5, v0.6 | "那个事"→记忆消解→确认→创建提醒；对话与工具调用共用上下文 |
| v0.8 | Reference Device Certification | 按参考手机/手表分别认证 | **对应真机** | 麦克风/扬声器/网络/续航/后台/ABI/ROM 权限实测；GL/Live2D 单独验收；CD12Max 仍 PENDING |
| v0.9 | UI Operator Beta | 尝试 Codex 式操作第三方 App UI | **真机 Accessibility** | `launch_app→inspect_ui→click/set_text→observe`；对应 OEM 不可靠则明确降级 |
| v1.0 | First Product Release | 可交付的 Android 陪伴智能体 | 核心版本与声明支持的设备档验收 | G1+G2+G3 核心达标；不把 CD12Max 未认证变成其他设备的前置；**Live2D 不阻塞** |

### v0.4 执行状态（组合层队列）

先把记忆可信 UI 接到真实 gateway 上，再动任何新能力。队列顺序固定，W0 是其余各项的前置。

| | 内容 | 状态 |
|---|---|---|
| W0 | `MemoryTrustTest` 18 项基线，接线前、零代码改动 | ✅ **18/18 PASS**（`a913968`，emulator-5554 / API 28 / 410×502@320dpi，一方法一次 `am instrument`）；原始输出见 `evidence/reports/w0-memory-trust-instrumentation.txt` |
| W1 | identity：`deviceId` 作唯一 `subjectId`，解析失败即显式不可用（不留临时主体、不回落） | ✅ **19/19 PASS**（`0515a39`）；原始输出见 `evidence/reports/w1-identity-composition.txt` |
| W2-A | cache construction boundary：Room 构造收回 cache module、`ProbeApplication.memoryCache` 进程级单例、instrumentation 按 subject 清理 | ✅ **19/19 + 22/22 PASS**；见下方状态 |
| W2-B | Activity 持有单个 `MemoryTrustRepository`；load/confirm/reject/edit/forget **同时**迁移，成功后 re-list 彻底删除 | ✅ **22/22 PASS ×2**；原始输出见 `evidence/reports/w2b-repository-owner.txt` |
| W3 | `UNAVAILABLE` / `CACHED` / `STALE` / `NEVER_SYNCED` 呈现 + 陈旧横幅 | 软件自动验证 PASS：构建、28 项 JVM 测试、API28 模拟器 25/25 界面测试；见 `evidence/tests/W3_VALIDATION.md`，不代表真机验收 |
| W4 | 运行期与结构约束：信任 UI 生命周期内单实例 | 软件自动验证 PASS：4 项编译字节码约束测试 + API28 界面 28/28；见 `evidence/tests/W4_VALIDATION.md` |

W0 是**模拟器**证据而非 CD12Max 真机，且只覆盖接线前的 18 项行为：它不构成 W1/W2 新代码的证据。

#### W2 为什么要切成 A/B

原先的 W2 会构造一个 `MemoryTrustRepository`，却仍让 Activity 的真实读写全部走 `gateway.list()`。
那种实例只是**死 wiring**：它证明不了 Gate B，只会让源码看起来像已经 composition 了。所以先做边界
（W2-A），再在 W2-B 一次性建立 owner 并同时迁移全部真实路径。

```text
W2-A + W2-B 之后：
  process cache owner              ESTABLISHED
  Room hidden behind cache module  ESTABLISHED
  test cache isolation             VERIFIED
  Activity repository owner        ESTABLISHED
  五条真实路径经过同一实例          ESTABLISHED
  成功后 re-list                    REMOVED
  indeterminate 只读回、绝不重试    VERIFIED

  W2 当时未建立（现均已完成软件自动验证）：
  Gate B runtime / structural enforcement   W4 PASS
  CACHED 首帧与陈旧横幅呈现                  W3 PASS
```

#### W2-B 的两个关键取舍（历史记录）

以下描述 W2-B 当时的隔离策略；当前 W3 已接入 `snapshots()` 与 cache-first 呈现，不应退回 `refresh()` 首屏。

**load 用 `refresh()`，不用 `snapshots()`。** `snapshots()` 会先发 `CACHED` 首帧，那就必须同时把
freshness UI 做对 —— W3 会被偷偷并进 W2-B。用 `refresh()` 时，所有 remote→cache projection 已经全部
经过同一个 repository，而可见行为与原来一致，W2-B 的新变量就只剩「owner」这一个。`CACHED` 在 load
中属不可达，仍显式处理，以便将来接 cached-first 是一个**看得见的决定**而不是意外。

**Definite 与 Indeterminate 分开。** `DefiniteMutationFailure` 时 authority 明确拒绝了，投影不动、
也不回读（没有可学的东西）。`IndeterminateMutationOutcome` 时**绝不重发** mutation（若它其实已生效，
重试就会生效两次），改为读回；读回本身失败时也不把 cache 冒充成最终答案。对 **edit** 而言编辑器保持
打开、draft 保留 —— 用户那行字是"她想说什么"的唯一副本，一个未知结果不能把它丢掉。

W2-B 还修掉两个真问题，都是新测试逼出来的：一是 indeterminate edit 先报告再 reconcile，而 reconcile 会
重建编辑器那行 error TextView，于是**界面擦掉了自己刚说的"不确定"**；二是
`editingAStagedCandidateDoesNotConfirmIt` 是个既有 flaky 测试（同一构建上 4 次跑出 2 pass / 2 fail，
已实测），它等的是 gateway 而不是界面，现由 `awaitIdle()` 以 Activity 自身的 busy 标志作为同步点修掉。

W2-A 的边界不是声称而是**从依赖图证出来的**：`:app:dependencies --configuration debugCompileClasspath`
中不含 `androidx.room`，只含 `project :core-memory-cache-android`。Room 仍是 cache module 的
`implementation` 依赖，构造函数为 `internal`（模块自己的 androidTest 经 friend-path 仍可构造，
`:app` 不可），`RoomMemoryCache.open()` 返回 `MemoryCache` 接口。

组合层（(d) remote + cache）另获一条独立证据：`:core-memory-cache-android` 的 22 项 androidTest 此前长期是
**written / not executed**，现已在同一模拟器上逐方法执行并通过，含撕裂快照
（`recordsAndFreshnessAlwaysComeFromTheSameCommit`——即原子化修复此前只能靠 CI 反馈盲改的那一项）、
主体隔离与整体替换回归。见 `evidence/reports/memory-cache-instrumentation.txt`。

### 没有手表也能连续推进

```text
v0.2 → (v0.3 若有 endpoint) → v0.4 → v0.5 → v0.6 → v0.7
```

v0.6 的 native tools 可先在 **API 28 模拟器或普通 Android 手机**验证，因为 `AudioManager`、
Alarm/Calendar、Intent/App launch、MediaSession 都是标准 API。CD12Max 到手时做的不是"第一次开发"，
而是**验证糯米OS 把这些标准能力允许到什么程度**。

**必须在对应真机上测的项目**：真实麦克风/扬声器、后台保活、`AccessibilityService` 可否启用且稳定、
厂商 App 的 UI tree 质量、`ro.product.cpu.abilist`、`GL_MAX_TEXTURE_SIZE`、真实电池与发热。
手机证据不冒充手表认证，模拟器证据不冒充任何真机；缺少 CD12Max 不阻塞参考手机验证。

### v1.0 必需 / 非必需（写死，防止再次跑偏）

```text
必需：  Voice · Memory · Android Device Operator · Agent Planner · 确认/权限/审计 · 角色 UI
非必需：Live2D · 视觉 GUI Agent · 复杂自动化 · 多角色商城
```

### Accessibility 单独成 v0.9

**v0.6 的"会操作设备"不依赖 Accessibility 才成立。** 先用 native tools 交付明确可感的 Agent 行为
（"声音小一点""十分钟后提醒我""打开网易云""暂停音乐""今天有什么安排"），v0.9 才挑战
"打开微信找到某个人"。Operator 分层顺序是硬约束：`Native API → Accessibility → Visual fallback`。
