# ROADMAP — xuanwearagent / 小智腕上陪伴

Plan of record. Kept short on purpose: it exists so the Gate is not forgotten and regressions are caught,
not as a development phase of its own.

## Target hardware (supplied by the customer, 2026-09-26)

| | |
|---|---|
| SoC | Unisoc W527, 12 nm, 1x Cortex-A75 + 3x Cortex-A55 (ARMv8, 64-bit silicon) |
| OS | 糯米OS, Android 9 / API 28, full Android (not Wear OS) |
| RAM / ROM | 4 GB + 32 GB |
| Panel | 2.06" AMOLED, **410 x 502 px**, 60 Hz |
| Battery | 1400 mAh, magnetic fast charge |

**Density derivation (this corrected an earlier wrong assumption).** 410x502 is 648 px on the diagonal;
648 / 2.06" = ~315 dpi, which Android buckets to **320 dpi / density 2.0**. Usable canvas is therefore
**205 x 251 dp**, not 410 x 502 dp. The P0-1 layout was built against the wrong basis and overflowed the
panel (transcript and push-to-talk pushed off-screen) until `CompanionDimensions` was re-derived.
`evidence/screenshots/p0-1-at-real-density-320.png` is the overflow; the re-scaled capture supersedes it.

## Gates

* **G1 — shippable, no Live2D required.** On the device: open app -> see the character -> hold to talk ->
  hear a reply -> correct state -> relaunch still works.
* **G2 — memory works.** A day later it raises something she said before.
* **G3 — Watch Operator.** Operating the watch through natural language. **V1 core, not optional.**

## Architecture: thin client

The watch does character UI, capture, playback and a small cache. LLM, long-term memory and TTS live on the
server. W527 should not run a local LLM, large embeddings, VITS or a reranker — that is battery and heat
spent to make the product worse. 1400 mAh belongs to the panel, the microphone and the radio.

```
watch:  Companion UI (IDLE/LISTENING/THINKING/SPEAKING)
      + core-audio (PCM / Opus / AudioTrack)
      + core-memory local cache (Room: recent turns, profile cache, event cache)
      + core-protocol  <-- WebSocket / HTTPS -->  backend
backend: ASR -> context builder -> LLM -> TTS (streamed PCM/Opus back to the watch)
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
Watch Operator `→ v0.6/v0.7/v0.9`, `P1 (Live2D) → Visual Enhancement Gate`, `P1+ (Jev-Mem) → v0.5 A/B`.

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
* TTS is server-side; the watch receives streamed PCM/Opus.

## User-visible trust surface

A "我的记忆" screen listing what the companion believes it knows — preferences, recent events, upcoming
commitments — **and letting her edit or delete them**, including confirming or rejecting staged memories.
For a companion product this is not a nice-to-have; it is the trust mechanism.

Personal-life and health-adjacent content ("腰疼", "去医院") lives on a server, so: encrypted at rest,
deletable, and raw audio is not retained longer than transcription needs it.

## Scope fences

```
do not keep chasing Cubism / editing the official Framework   <- until the two device gates below are read
no Compose, no Wear OS runtime, no new UI stack
no on-device ASR/LLM/TTS
no multi-character, no character store, no importer
no large animation library for polish
```

## Device gates that are still unread

`C1 Device Probe` has never been run — no CD12Max has ever been connected. Two readings decide Live2D's fate
and should be taken the moment a device is available, even though Live2D is P1:

```
getprop ro.product.cpu.abilist     # arm64-v8a present? W527 is 64-bit silicon, but the ROM may be 32-bit
GL_MAX_TEXTURE_SIZE                # Mahiro's atlas is 8192x8192; a 4096 cap makes Live2D impossible here
```

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
| v0.4 | Memory Companion | 小智记得住，第二次聊天会主动用过去信息 | 无手表 | CanonicalMemory、画像/事件/经历/关系、Memory Gateway、"我的记忆"可编辑删除 |
| v0.5 | Memory Beta | 记忆从"能存"到"会用" | 无手表 | 候选检索、时间衰减、去重、**条件式** Jev rerank、隔天回忆测试 |
| v0.6 | Native Watch Agent | 真正操作 Android：音量/亮度/闹钟/计时器/日历/App 启动/媒体 | **v0.3** | 5–8 个 typed native tools；Action Card；确认策略；审计日志 |
| v0.7 | Integrated Companion Agent | 陪伴+记忆+操作合进同一个 Agent Planner | v0.5, v0.6 | "那个事"→记忆消解→确认→创建提醒；对话与工具调用共用上下文 |
| v0.8 | CD12Max Hardware Beta | 真正适配目标手表 | **手表** | 麦克风/扬声器/网络/续航/后台/ABI/GL texture/ROM 权限全部实测 |
| v0.9 | UI Operator Beta | 尝试 Codex 式操作第三方 App UI | **真机 Accessibility** | `launch_app→inspect_ui→click/set_text→observe`；糯米OS 不可靠则明确降级 |
| v1.0 | First Product Release | 可交付的腕上陪伴智能体 | 以上 | G1+G2+G3 核心达标；**Live2D 不阻塞** |

### 没有手表也能连续推进

```text
v0.2 → (v0.3 若有 endpoint) → v0.4 → v0.5 → v0.6 → v0.7
```

v0.6 的 native tools 可先在 **API 28 模拟器或普通 Android 手机**验证，因为 `AudioManager`、
Alarm/Calendar、Intent/App launch、MediaSession 都是标准 API。CD12Max 到手时做的不是"第一次开发"，
而是**验证糯米OS 把这些标准能力允许到什么程度**。

**必须等手表的只有**：真实麦克风/扬声器、后台保活、`AccessibilityService` 可否启用且稳定、
厂商 App 的 UI tree 质量、`ro.product.cpu.abilist`、`GL_MAX_TEXTURE_SIZE`、真实电池与发热。

### v1.0 必需 / 非必需（写死，防止再次跑偏）

```text
必需：  Voice · Memory · Native Watch Operator · Agent Planner · 确认/权限/审计 · 角色 UI
非必需：Live2D · 视觉 GUI Agent · 复杂自动化 · 多角色商城
```

### Accessibility 单独成 v0.9

**v0.6 的"会操作手表"不依赖 Accessibility 才成立。** 先用 native tools 交付明确可感的 Agent 行为
（"声音小一点""十分钟后提醒我""打开网易云""暂停音乐""今天有什么安排"），v0.9 才挑战
"打开微信找到某个人"。Operator 分层顺序是硬约束：`Native API → Accessibility → Visual fallback`。