# PRODUCT_REQUIREMENTS — 项目记忆 / 需求正本

> **这份文件是项目记忆。** 任何人（或 AI 会话）接手这个仓库，先读它，再读 `ROADMAP.md`。
> 它记录的是"要做什么、边界在哪、哪些决定已经冻结"，不记录进度流水。进度看 `ROADMAP.md`。

---

## 一句话定位

> **Banxuan / 伴星是一款面向 Android 9+ 手机与 Full Android 手表的长期陪伴型 AI Agent。基础能力在兼容设备上保持一致，角色表现、动画和部分设备感知能力根据窗口与硬件能力渐进增强。**

内部称之为 **Companion Agent Runtime**／**Android 陪伴智能体**。这是产品目标，不是全设备兼容性声明。

不是"一个会聊天的二次元 App，后面顺便加一点设备控制"。三者缺一不可：

```text
陪伴 = 人格层
记忆 = 持续性
有边界的 Android 设备操作 = 行动能力
```

产品核心是**三个并列能力面**，由 Agent Planner 统一决定这一轮该不该调工具：

```text
                 小智 Agent
                    │
      ┌─────────────┼─────────────┐
      ▼             ▼             ▼
 Companion       Memory        Operator
 陪伴人格         长期记忆        设备操作
```

它更接近：

```text
Codex = 模型 + 上下文 + 工具 + 电脑执行器
小智  = 角色人格 + 长期记忆 + 模型 + 工具 + Android 设备执行器
```


---

## 1. 平台范围与参考设备（2026-10-01 更新）

目标为 **Android 9+ / API 28+ 手机与 Full Android 手表**，`minSdk = 28` 不变。
不支持 Wear OS runtime 或 Android 8 及以下，不承诺任意 OEM 已兼容。
CD12Max 是 reference / certification device；普通 Android 手机承担真实音频/网络验证；
SDK 模拟器承担自动回归。缺少 CD12Max 不阻塞其他设备的软件开发。

以下 **CD12Max 4+32G** 参数是用户提供的参考规格，不是真机采集，也不是产品全平台最低要求：

| | |
|---|---|
| SoC | 紫光展锐 W527，12nm，1×Cortex-A75 + 3×Cortex-A55 |
| 系统 | 新版糯米OS（Android 9 / **API 28**），**Full Android，不是 Wear OS** |
| 内存 / 存储 | 4 GB RAM + 32 GB ROM |
| 屏幕 | 2.06" AMOLED，**410 × 502 px**，60 Hz |
| 电池 | 1400 mAh，磁吸快充 |

**密度证据更正**：物理面板约 315dpi 不能推出 Android 逻辑 density 为 320。
**410×502@320dpi = 205×251dp** 只是显式配置的模拟器 compact regression fixture，永久保留；
CD12Max 真机 density 仍待 Probe。扣除系统栏/键盘后可用窗口还会变化，不把 fixture 写死成所有设备的内容区。
尺寸 token 与运行时 `CompanionLayoutSpec` 分离、根据实际可用窗口适配的工作留给独立 PR B。
四窗口 × fontScale 1.0/1.3 的待测矩阵以 [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md) 为正本。

---

## 2. 产品定位

不是"手表版 ChatGPT"，而是**长期陪伴型二次元 AI 角色**。

打开应用的第一感觉必须是「**这个角色住在我的设备里**」，而不是工具页、设置页或工程 Demo。

---

## 3. 角色形象

- 用户指定想要 **泳装绪山真寻** 这一类具体角色形象。
- 开发期**可以使用用户提供的 Mahiro 资产**做私有定制和测试。
- **但产品架构不能把任何 IP 写死**，必须通过 `CharacterProfile / AvatarSource` 表达，以后能换角色。
- **公共发行时要把"版权/授权"与"用户自备资产"区分清楚**：IP 角色不得成为可再分发 APK 的默认资源。
- 客户资产目录（`app/src/main/assets/character/`）**已在 gitignore 中**，只提交 `.gitignore` 本身。
  放一张干净、有授权的立绘进去即可自动生效，不需要改代码。

---

## 4. 声音

- 用户想要的听感是 **《奇迹暖暖》苏暖暖 / 陈奕雯配音版本那种**：甜软、元气、少女感、亲近。
- 技术抽象为 `VoiceProvider / VoiceProfile`。
- **合规边界（不可越）**：没有相应授权时，**不得把"陈奕雯/苏暖暖声纹"作为默认官方克隆音色**。
  若客户自己持有合法授权，再接授权语音。
- 无授权时产品层只提供**描述性风格**（甜软 / 元气 / 温柔 / 平静），不克隆特定真人声纹。
- **TTS 放服务端**，Android 客户端只接收流式 PCM/Opus。

---

## 5. 核心交互

- **以语音为主**。首页 = 二次元角色舞台 + 最近对话 + 大号「按住说话」。
- 状态必须**真正**存在并被驱动：

```text
IDLE → LISTENING → THINKING → SPEAKING → IDLE
```

- **Speaking 时用户再次按下必须能立刻打断**（barge-in），且**本地要先 flush**，不能有残留音频漏出。

---

## 6. 语音链路

```text
Android 客户端：录音 → Opus → 播放
服务端：ASR → Context/Memory → LLM → TTS
```

- **现有 `core-audio` 与 `core-protocol` 尽量直接复用，不重写协议和音频轮子。**
- 当前优先做**真实端到端**：`PTT → ASR → LLM → TTS → AudioTrack`。
- 服务端走现成的 `xinnan-tech/xiaozhi-esp32-server` 路线；**不新造 ASR 服务**，只做 provider 选型与压测。
- 关键指标：`t_release → first_audio`，目标 **< ~1.2 s**。

---

## 7. 陪伴感的核心：长期记忆

比 Live2D 重要得多。用户记忆力不太好，所以 AI 要替她记住：

- 喜好 / 厌恶
- 重要人物
- 过去发生的事
- 约定、计划、未来事件
- 情绪上下文
- 角色与用户之间的长期关系状态

**长期记忆不能只是向量库。**

---

## 8. 记忆模型

唯一正本是**类型化 schema**：

```text
CanonicalMemory
  type      = PROFILE / EVENT / EPISODE / RELATION
  content
  importance
  timestamp
  source
  characterScope
  provenance
```

举例：

```text
"我下周三下午三点要去医院" → EVENT，带 time + importance=high
"我其实不太喜欢香菜"       → PROFILE，food.dislike = 香菜
"昨天和室友吵架了很难受"   → EPISODE + 情绪上下文 + RELATION:室友
```

目标效果是能自然说出「你上次不是说和室友闹矛盾了吗，现在好一点了吗」，
而不是随机命中一句旧聊天。

**每轮上下文固定由五部分组成**：

```text
最近对话 + 用户画像 + 相关长期记忆 + 时间/事件/承诺 + 角色关系与情绪状态
```

### Mem0 / Jev 的角色分工（不能含糊）

```text
CanonicalMemory (类型化唯一正本)
        ↓ 持久化 / 候选检索
Mem0 / 向量库 / BM25            ← 只是实现底座
        ↓ 条件式重排
Jev Recall                      ← 只做二阶段 rerank
```

- **绝不允许** Wanyu memory + Mem0 memory + Jev memory 三套各自自称"记忆"。
- **Jev Recall 是条件式的，不是每轮都调**（会吃掉语音首字延迟预算）：

```text
普通寒暄 / 当前轮上下文已足够        → 不重排
PROFILE / EVENT 查询                 → 重排
候选 top scores 接近、歧义高          → 重排
需要跨事件关联                        → 重排
```

- `Jev-Mem` 只做后期 A/B（用我们自己的真实陪伴对话数据），**不做 P0 blocker，不上手表**。
- Wanyu 的**记忆分类、时间衰减、去重、暂存确认、用户画像、情绪连续性**重点借鉴，
  但**不搬整个 Wanyu 工程**。

---

## 9. 记忆必须可见、可控

后续需要「**我的记忆**」页面，让用户看到 AI 记住了什么，并能**修改、删除、确认或拒绝**。

这是陪伴产品的**信任机制**，不是可选功能。

涉及个人生活与健康相邻内容（"腰疼""去医院"）时：服务端加密存储、可删除、原始音频不做长期保留。

---

## 10. UI 设计原则

- 用户偏二次元，**不能是默认 Android Button / TextView 风格**。
- 目标：**明亮、克制的二次元风格**，角色为视觉中心，简洁但精致；这是产品方向，不宣称相关 UI 分支已合并。
- 参考 GitHub 成熟项目的**布局、气泡、PTT、状态反馈**，不重复造轮子。
- 现有 token 与尺寸见 `app/src/main/kotlin/com/aiwatch/probe/theme/`；窗口自适应留给 PR B，不在本次文档迁移中实现。

---

## 11. 技术栈约束（硬）

继续使用当前 **Native Android Views**。**不为了 UI 引入**：

```text
Compose / Wear Compose / Wear OS runtime
新渲染栈 / 大型动画库 / 新的网络库 / 新的数据库
```

手机与手表都以 Full Android 为范围，**兼容优先**。不降低 minSdk，不引入无限制设备自动化。

---

## 12. Live2D 的定位

- 从**主线 blocker 降级为 P1 可选增强**。**P0 用静态立绘也必须能完整交付陪伴体验。**
- 真机一接上，**提前**读取这两个值（不等 P1）：

```text
ro.product.cpu.abilist     # 与实际打包 Core/进程 ABI 核对，不能仅凭 SoC 推断
GL_MAX_TEXTURE_SIZE        # Mahiro 原始 atlas 是 8192×8192；若上限 < 8192，原图不可能直接上传
```

- 判定规则：
  - 无匹配 Core ABI → 禁用 Live2D 并回退静态角色，不把 Banxuan 基础产品判不适配。
  - 纹理上限须与**实际上传尺寸**、内存预算一起判断；不能将原始 8192 atlas 作为全平台门槛。
    降采样是否可行仍需独立验证；本轮不实现新的渲染或导入系统。
  - 能力存在不等于 runtime PASS。Live2D 另验帧率、内存和生命周期；基础音频/协议/记忆/Operator 另验。
  - 第一轮只需简单 runtime capability 与静态 fallback，不先造复杂 capability framework。
- 读法：debug build 内已有的 GLES capability probe（`PlainShaderProbe`），
  比 `dumpsys` 可靠。**只读值，不做任何 Live2D 调试。**

---

## 13. 整体架构：薄客户端

```text
Android 手机 / Full Android 手表
├── Companion UI（角色舞台 / 最近对话 / PTT / 四态）
├── core-audio（PCM / Opus / AudioTrack）
├── core-memory 本地缓存（Room：最近会话、画像缓存、事件缓存）
└── core-protocol ──WebSocket/HTTPS──┐
                                     │
                         Companion Backend
                    ASR → Context Builder → LLM → TTS
                              ↑
                    Memory Service（候选检索 → 条件重排）
```

**所有目标设备均不跑端侧 ASR/LLM/TTS、大 embedding、VITS、Jev reranker。**
电池和热预算优先留给屏幕、麦克风、网络和播放。

---

## 14. 复用原则

凡是 GitHub 已有成熟方案：**先审 LICENSE，再决定 `DIRECT / ADAPT / REFERENCE ONLY`**。

重点参考：`WristAssist`、`ECSDevs/Messenger`、`Wanyu AI Companion`、`OpenClaw Companion`、
`Mem0`、`Jev Recall` 等。**不自己重造聊天气泡、PTT、记忆 schema、ASR provider、语音状态机。**

许可证状态与用法见 `REUSE_AUDIT.md`（已核实的标注为 VERIFIED）。

---

## 15. 当前优先级

```text
PR A  产品契约迁移（仅六份文档，无代码）
PR B  Adaptive UI Round 1（仅窗口布局与回归）
P0    Connected Voice：真实 Xiaozhi endpoint → 真实语音闭环
后续  Live2D polish（可选增强，不挤占 G1/G2）
```

唯一产品版本线和进度以 [ROADMAP.md](ROADMAP.md) 为准。只做一轮适配，不扩到折叠屏、平板、旧 Android 或 Wear OS。
PR B 不改 Voice、Memory semantics、backend、Live2D integration 或 Operator。长期记忆仍是核心，Live2D 仍是增强。

### G3 Android Device Operator —— V1 核心能力之一（不是可选增强）

`G1 / G2 / G3` **三者都属于最终 V1**。只有**工程顺序**是 G1 → G2 → G3，因为 Agent 连"听懂并正常对话"都还没稳定时，
先让它乱点系统没有意义。**Live2D 才是 optional。**

```text
G1 真语音闭环  →  G2 长期记忆生效  →  G3 Android Device Operator
```

> G3 Android Device Operator is gated behind G1 and G2. No MCP capability advertisement, Accessibility
> integration, or device-control dependency may enter the production path before the real P0-2 voice
> E2E is measured.

第一批工具只做 **5–8 个**最高频、native API 最稳的：

```text
get_battery / set_volume / launch_app / create_alarm·timer
read_calendar / media play·pause / vibrate / （可选 brightness）
```

**Contacts / SMS / Location / Notification / Camera 第一批全部不进。** 这一条同时解决两个问题：隐私姿态，
以及 tool schema 膨胀 —— 工具 schema 每轮都进 prompt，工具越多选错率越高、首字延迟越长，而
`t_release → first_audio < ~1.2 s` 是 P0-2 的判定指标。因此**不允许全量 `tools/list` 暴露**。

能力分档（硬规则）：

```text
自动执行     调音量 / 打开 App / 读本地电量
首次授权     读取日历
需要确认     创建日历事件 / 回复通知 / 修改设置
强制确认     发短信 / 拨号 / 任何"离开设备"的动作
```

敏感能力（通知 / 通讯录 / 短信 / 位置 / 相机）必须：用户主动开启 + 明确说明哪些数据离开设备 + 高风险动作逐项确认
+ 调用日志用户可查 + 随时撤权。

**协议现状（不要误解为"已经支持 MCP"）**：`core-protocol` 目前只有 `type="mcp"` 的**入站信封解析**
（`ProtocolModels.kt:15`、`XiaozhiProtocol.kt:42`），**没有** device-MCP 会话实现 ——
`initialize / tools/list / tools/call / response / list_changed / 出站构造` 全都仍是实际工作量。
且打开 `features.mcp` 会改动 client hello，而那条 hello 正是**尚未在真实服务端验证过**的语音链路所依赖的。


---

## 16. 范围围栏（明确不做）

```text
不继续死磕 Cubism、不改官方 Live2D Framework；可选渲染不能阻塞基础产品开发
不引 Compose / Wear OS runtime / 新 UI 栈
不做端侧 ASR/LLM/TTS
不支持 Android 8 及以下，不开放任意 shell 或无限制设备自动化
不做多角色、角色商城、importer
不为"好看"引入大型动画库
不把某个 IP 角色作为公共发行版默认资源
不克隆未授权的特定真人声纹
```

---

## 17. 治理规则

> **任何一项工作，如果连续两轮没有缩小 G1/G2 的交付缺口，就停下来重新评估，而不是继续深挖。**

（设立原因：曾有一整个 session 花在 Live2D 上，而它当时是最不产品关键的一环。）

### Gates

- **G1**（不依赖 Live2D）：真机上打开 App → 看到角色 → 按住说话 → 听到回答 → 状态正确 → 重进仍在。
- **G2**：记忆生效——隔天它能提起她之前说过的事。
- **G3**：Android Device Operator——通过有权限、确认与审计边界的工具操作设备。**V1 核心，不是增强项。**
- **Visual Enhancement Gate**（不占 G 编号）：Live2D / Mahiro 形象。**永不阻塞 G1/G2/G3。**

> 2026-09-26 修正：本节此前把 G3 写成"Live2D / Mahiro 形象"，与第 18 节冲突。**G3 = Watch Operator**，
> Live2D 降为独立的 Visual Enhancement Gate。2026-10-01 将该产品 Gate 更名为 **Android Device Operator**；安全边界不变，以本节 + 第 18 节为准。


---

## 18. 三面能力架构与 Android Device Operator（2026-09-26 定义，2026-10-01 平台迁移）

本节取代此前把 G3 描述为"未来边界"的措辞。**G1/G2/G3 都是 V1 核心**，Live2D 才是增强项。

### 18.1 产品完成度

```text
核心 V1
──────
G1  Voice / Conversation     会自然交流
G2  Long-term Memory         会长期记住
G3  Android Device Operator  通过受控工具替用户操作 Android 设备

增强
────
Live2D / 视觉 GUI fallback / 主动陪伴 / 更复杂自动化
```

工程顺序仍是 G1 → G2 → G3（`Phase A → B → C`），但 A+B+C 才是第一版产品，不是"A+B 做完再说"。

### 18.2 Android Device Operator Runtime 三层（优先级即顺序，不可颠倒）

```text
1. Native Tools        直接 Android API —— 永远优先
2. Accessibility       通用 UI 操作（观察 → 决策 → 操作 → 再观察）
3. Visual fallback     截图 → VLM → 坐标点击（后期，不带进第一版）
```

**有 API 就绝不模拟点击。** 用户说"声音太大了"→ `watch.set_volume(30)`，不是"打开设置→声音→拖滑块"。
第一层最可靠、最省电、也最不容易坏，是第一版最重要的控制能力。

本次只更名产品 Gate，不改代码/协议标识符；上述 `watch.set_volume` 保留为历史命名示例，不限定目标设备。

第二层才是 Codex 式循环：`launch_app → inspect_ui → click → inspect_ui → set_text → …`

### 18.3 工具必须分层披露，但核心集必须常驻

不能每轮给模型 145 个 tool。默认只暴露**能力域**，模型判断属于某域后再展开：

```text
device / apps / calendar / media / communication
```

**但要注意一个延迟陷阱**：渐进披露会多一次往返（模型先要域、再拿工具、再调用）。语音路径的预算是
`t_release → first_audio < ~1.2 s`，多一次 LLM 往返可能就吃掉了。

因此：

```text
核心 6–10 个工具     每轮常驻，不付额外往返
长尾工具             才走渐进披露
```

第一版核心集：

```text
set_volume / set_brightness / launch_app / create_timer
create_alarm / read_calendar / media_play_pause / get_battery
```

### 18.4 记忆必须参与"操作"，这是与普通 Computer Use 最大的区别

普通 Agent 只执行；小智要先**用记忆消解指代**再决定工具参数：

```text
"我明天下午别让我忘了那个事"
   ↓ Memory Resolution
EVENT: 交课程材料
   ↓
create_reminder(...)
```

**由此产生一条新的安全约束（本节新增）**：当工具参数是**由记忆消解指代**得出、且动作**不可撤销**时，
必须在执行前把消解结果给用户确认。

例如"那个事"被解析成"交课程材料"→ 创建提醒前先显示：

```text
你是说：交课程材料，明天
对吗？
```

理由：错误的记忆消解在聊天里只是一句错话，落到工具调用上就是**一个真实且不可逆的世界动作**。
这是 G2 与 G3 叠加后才出现的失败模式，单独的 G2 或 G3 都不需要这条。

### 18.5 UI：极简 Agent Action Card

首页不能只是聊天页，还要有"**Agent 正在替你做事情**"的感觉：

```text
小智
"好呀，明天别睡过头。"

✓ 已设置闹钟
  明天 07:00
```

多步任务：

```text
正在帮你处理…
✓ 打开设置
✓ 找到声音
● 调整媒体音量
```

**205×251dp 仍是最严格的小屏 fixture，而不是所有设备的固定画布。**
COMPACT 优先保住 PTT 与字幕，必要时缩小舞台、减少可见历史；EXPANDED 增加舞台与字幕空间，
为 PTT/内容设置合理 maxWidth，保留 `ProductUi.page()` 的 560dp 上限。由窗口驱动同一套 Views，
不按机型分支、不复制 Watch/Phone/Tablet 三套布局。Action card 与消息共享有限空间。

因此决定：**action card 不做独立面板，而是作为消息流里的附着元素**（紧跟触发它的那条回复），
随消息一起滚动。这样不需要额外的垂直预算，也不会在只有一条消息时把页面撑空。

### 18.6 架构定位

```text
Android 客户端 = Thin Agent Client + Tool Runtime + Companion UI + Local Cache
服务端 = ASR + LLM + Memory + Planning + TTS
```

**客户端不持有 LLM。** 这是手机和手表共同的架构边界，而非仅针对 W527 的特例。

### 18.7 命名

文档与内部讨论使用 **Companion Agent Runtime**／**Android 陪伴智能体**。
不改 `applicationId` 与包名——那是产品化步骤，不应在开发中途动。

---

## 19. 产品定位与 Context Awareness 定义（2026-09-28 冻结）

### 19.1 一句话定位

> **Banxuan / 伴星面向 Android 9+ 手机与 Full Android 手表：长期认识用户、理解可获得的当前情境，并通过有权限与确认边界的工具操作设备。**

内部定位使用 **Companion Agent Runtime / Android 陪伴智能体**；本节平台范围按 2026-10-01 契约更新。

它不是"手表版 ChatGPT"，也不是"给聊天机器人套一个二次元角色"。价值来自三个用户可感知的能力面：

```text
Companion   人格、声音、角色存在感、自然交流
Memory      长期认识用户，记住关系、偏好、事件与过去
Operator    通过受控工具替用户操作 Android 设备
```

在三者之下增加一个**横向基础能力**：

```text
Context / Awareness
知道"现在是什么时候、设备是什么状态、用户大概在做什么"
```

```text
                         小智 Agent
                             │
                      Context / Awareness
                    当前时间 · 设备 · 活动
                             │
             ┌───────────────┼───────────────┐
             ▼               ▼               ▼
        Companion          Memory          Operator
          陪伴              持续性            行动力
```

**Context 不是第四个 Gate，也不是独立产品面。** 它是 Companion / Memory / Operator 共同使用的感知底座。
G1 / G2 / G3 定义不变，**不另设 G4**；Context 服务于三个 Gate，并在 G1/G2 稳定后逐渐进入产品路径。

### 19.2 手机与手表的不同使用情境

产品差异来自持续关系、可信记忆与有边界的行动能力，不来自必须佩戴某款硬件。
手机与手表共享基础产品语义；手表可额外利用以下佩戴情境，但不把这些条件作为手机使用前提：

```text
Persistent Presence      它一直戴在用户身上
Immediate Context        它离用户的身体、时间、行为与设备状态更近
Immediate Action         它就是当前需要被操作的设备
Low-friction Interaction 抬腕、说一句话、得到反馈，不需要拿出另一台设备
```

因此最终形态不是"用户 → 打开 App → 提问 → 收到回答"，而是：

```text
时间 / 日历 / 活动 / 设备状态
             │
      Context Snapshot
             │
      ┌──────┴──────┐
用户主动说话      有意义的事件
      └──────┬──────┘
             ▼
         小智 Agent ── Memory + Persona ── 判断是否回应
             │
    对话 / 提醒 / 操作设备
```

目标是“角色住在自己的设备里”。传感器或权限不存在时保持 absent，不将可选感知变成产品准入门槛。

### 19.3 产品核心公式

```text
Companion Agent = Persona + Long-term Memory + Situational Context + Device Agency
```

```text
Persona   "她是谁？"
Memory    "她认识我吗？"
Context   "她知道我现在大概在干什么吗？"
Agency    "她能替我做什么？"
```

任何一个都不能替代其他三个。只有 Persona 是角色聊天机器人；只有 Memory 是有历史记录的聊天机器人；
只有 Context 是传感器 dashboard；只有 Agency 是语音遥控器。四者结合才是完整产品。

### 19.4 Context 的定义

> **为了让 Agent 正确理解"此时此刻"，而提供的一小组有界、结构化、可解释的当前状态。**

第一版可接受的 `ContextSnapshot`：

```text
time       localTime · dayOfWeek
device     batteryLevel · charging · screenInteractive
activity   foregroundApp? · steps? · coarseMotion?        optional
schedule   nextCalendarEvent?                             optional
health     heartRate?                                     optional / hardware-gated
```

**重点是问号。** Context 必须允许 `sensor unavailable` / `permission denied` / `feature disabled` /
`ROM does not expose capability`。**不存在的上下文必须保持 absent，不得猜测。**

### 19.5 Context 与 Memory 必须严格分离

```text
Context = "现在是什么状态"
Memory  = "她长期相信什么"
```

```text
Context: heartRate = 135 bpm          ×→  Memory: "用户心率经常很高"
Context: foregroundApp = Bilibili
         localTime = 02:17            ×→  Memory: "用户喜欢熬夜刷 B 站"
```

从 Context 形成长期 Memory **必须经过独立的 memory extraction / staging 规则**；涉及敏感属性时默认更保守。

```text
Context Provider → Context Snapshot → Conversation / Agent Decision
                                            ×  不允许直接写入
                                            ▼
                                      CanonicalMemory
```

CanonicalMemory 仍然只有 server-side authority 能决定。**Context 永远不能成为第二套 memory authority。**

### 19.6 不做 Always-on Microphone

"陪伴"不等于"全天监听"。明确不采用：

```text
24h microphone capture · continuous ambient speech recording
continuous cloud ASR · third-party conversation collection
```

主动陪伴优先使用：时间、日历、屏幕状态、设备状态、App 使用事件、步数、低频运动事件、可选健康读数，
并且只在有意义变化出现时触发：

```text
廉价本地事件 → Local Gate → 有必要？
                             ├─ 否 → 静默
                             └─ 是 → 上传最小 Context Summary → Agent
                                                              → NO_ACTION / 回复 / 提醒
```

模型必须允许 **`NO_ACTION`**。"每次检测到上下文都找用户说话"本身就是产品失败。

### 19.7 Proactive Companion 的正确形态

必须同时满足：

```text
Relevant         真的和用户当前状态有关
Sparse           频率克制
Non-repetitive   避免一天重复提醒同一件事
Interruptible    用户可以关闭或降低主动频率
Explainable      用户可以知道"为什么现在提醒我"
```

典型场景：`14:47` 下一日历事件 `15:00` →"你三点不是还有课吗？"；`22:40` 明天 `07:00` 有重要 EVENT
→"明早还有那个安排，要不要早点休息？"；刚完成一段运动（真机可靠可获得时）→"刚运动完？缓一会儿再继续吧。"；
低电量 + 用户要求执行较长任务 →"现在只剩 8% 了，这个任务可能比较耗电。"

**不是**：每 20 分钟强行找一次话题。

### 19.8 数据最小化原则

```text
collect less · store less · send less · retain less
```

Raw sensor stream 尽量留在设备侧。服务端优先收到 `walking` 而不是 `10 分钟 × 50 Hz accelerometer raw stream`；
优先收到 `heart_rate = 88, measuredAt = ...` 而不是长时间连续 PPG；优先收到 `screenInteractive = true`
而不是持续屏幕录制。**长期保存必须与"临时用于理解当前情境"分开。**

### 19.9 复用审计：Context / Awareness

#### Android platform APIs — DIRECT

第一选择不是第三方框架，而是 Android 自带能力：

```text
SensorManager · BatteryManager · PowerManager · UsageStatsManager
BroadcastReceiver · CalendarContract · AlarmManager · PackageManager feature detection
```

理由：API 28 可用、零额外 runtime、体积最小、行为最容易审计，符合手机/手表共用的薄客户端约束。

#### RADAR-base / radar-commons-android — ADAPT（Apache-2.0）

不引入完整 RADAR framework。重点借鉴：

```text
PhoneUsageManager.kt     UsageStatsManager · 增量 queryEvents · foreground/background event
                         ACTION_USER_PRESENT · ACTION_SCREEN_OFF · shutdown/boot state
BatteryLevelReceiver.kt  ACTION_BATTERY_CHANGED
PhoneSensorManager.kt    SensorManager · sampling interval · sensor availability
```

特别有价值的是 **保存 `lastTimestamp`，下一次只 query 新增 usage events**，而不是反复扫描全部历史。

```text
full RADAR dependency      REJECT
specific collection code   ADAPT
architecture               REFERENCE
```

#### Home Assistant Android — ADAPT（Apache-2.0）

轻量 sensor donor，与本项目设计原则高度一致：

```text
HeartRateSensorManager.kt  FEATURE_SENSOR_HEART_RATE · TYPE_HEART_RATE · BODY_SENSORS
                           accuracy check · 拿到有效读数后立即 unregisterListener()
StepsSensorManager.kt      FEATURE_SENSOR_STEP_COUNTER · TYPE_STEP_COUNTER · 单次读取后 unregister
```

> 不持续采集可以按需取得的信息。

只借采样模式与异常处理，不引入 HA 整个应用架构。`whole HA wearable stack` = REJECT。

历史 issue 也提醒：手表系统不一定按预期及时返回有效心率，因此**必须真机 probe，不能把"API 存在"当成"设备可用"**。

#### AWARE Framework — REFERENCE（Apache-2.0）

已完整解决 hardware/software context、前台服务、采集、数据库、同步、Accessibility-based context，
但完整 Android stack 过重、工程结构较老、持续运行需要较强后台保活。价值在 **Context source inventory、
permissions inventory、后台运行失败模式、OEM battery optimization 经验**。`full dependency` = REJECT。

#### RADAR-pRMT — REFERENCE（Apache-2.0）

成熟 passive monitoring architecture（长期运行、插件式 sensor provider、Android 7+），但带 Firebase、
Kafka / remote configuration、研究数据采集导向，以及大量本产品不需要的模块。不作为 product dependency。

#### Beiwe Android — REFERENCE ONLY（BSD-3-Clause）

成熟的 digital phenotyping 采集器。值得参考：长期后台任务恢复、sensor frequency configuration、
accelerometer / gyro collection、battery / idle state handling、data minimization by product flavor。
同时是**重要的反面参考**：background location、ambient audio、大量持续行为采集 —— 技术上可行，
不代表 Banxuan 应该做。

#### TOM-Client-WearOS — REFERENCE（MIT）

值得借鉴 `Wear sensor → local Room → WebSocket → server` 这条结构。但它 `minSdk 30`，依赖 Wear OS、
Wear Health Services、Wear Compose、Hilt、WorkManager、Ktor；本项目是 API 28 / Full Android / Native Views。
`architecture` = REFERENCE，`code / direct dependency` = REJECT。

#### `Cheiineeey/always-here` — REFERENCE ONLY（**许可证矛盾**）

产品思想值得参考：dynamic proactive interval、quiet hours、activity-aware triggering、health-aware tone、
LLM may choose `NO_ACTION`、anti-repetition。

但仓库存在**许可矛盾**：

```text
README : MIT
LICENSE: AGPL-3.0
```

因此在上游澄清前：`copy code` = **REJECT**，`product idea` = REFERENCE。
**不得按照 README 的 MIT 声明复制实现。**

#### `OPPO-Mente-Lab/X-OmniClaw` — REFERENCE（Apache-2.0）

最值得学习的是把 Agent runtime 定义为 `Perception → Reasoning → Execution → Verification`，
并让 UI state、real-world context、speech、scheduled trigger、memory、action 进入同一个 runtime。
但它的 Android 工程同时使用 Compose、Chaquopy/Python、ONNX Runtime、ML Kit、Retrofit、NanoHTTPD、
多模态本地推理与大量权限，**不符合本项目手机/手表共用的薄客户端边界**。

```text
Agent architecture   REFERENCE
dependency           REJECT
copy whole runtime   REJECT
```

未来 G3 的 `observe → decide → act → observe` 设计可继续参考。

#### `stixez/droid-mcp` — 已审计，不重复

沿用既有结论 `REFERENCE / CANDIDATE`。`droid-mcp-core` 会引入 Ktor / Netty / SSE 等运行时成本，
G1/G2 完成前不进入 production path。它属于 **Operator reuse audit**，
**不重新包装成 Context framework**。

### 19.10 Context 第一阶段只做 capability probe

**现在禁止直接建设完整 Context subsystem。** 后续在各参考真机分别执行 **`C2 Context Capability Probe`**，不以 CD12Max 到货作为其他设备的前置。
C2 不实现产品能力，只回答事实：

```text
SensorManager.getSensorList(TYPE_ALL)
TYPE_HEART_RATE / TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR / TYPE_SIGNIFICANT_MOTION 是否存在
UsageStats 是否可授权并正确返回
后台 BroadcastReceiver 行为
screen on/off/user-present 行为
Calendar Provider 是否存在
后台进程实际存活情况
```

然后测功耗，至少比较 `baseline` → `+ screen/device event only` → `+ step counter` →
`+ low-frequency motion sampling` → `+ periodic heart-rate request`，记录
`battery drop/hour · CPU · RSS · wakeups · temperature · background survival`。

**没有真机数字，不宣称"低功耗 Context 可行"。**

### 19.11 Context 第一版建议范围

真机 Gate 通过后，按价值/成本排序：

```text
Tier 0  time · battery · charging · screen state
Tier 1  calendar · foreground app / app transition
Tier 2  step counter · significant motion
Tier 3  heart rate
Tier 4  raw accelerometer-derived activity recognition     默认不做
```

如果 OEM 已提供 step counter / significant motion / heart rate，**就不自己训练 HAR 模型**。

> 有系统传感器语义，就不从原始 accelerometer 重新推断同一个东西。

### 19.12 不允许为了 Context 引入的东西

在真机数据证明必要之前，不引入：

```text
AWARE full framework · RADAR full framework · Wear Health Services
Google Activity Recognition dependency · TensorFlow Lite HAR · ONNX HAR
WorkManager framework dependency · 连续 GPS · continuous microphone · continuous camera
raw sensor cloud streaming · 新的 vector DB · 新的 state framework
```

尤其：**"为了判断用户是不是在走路" → 加一个神经网络**，在设备已有 `TYPE_STEP_COUNTER / significant motion`
时属于重复造轮子。

### 19.13 Context 的本地接口应保持极薄

```text
ContextProvider   snapshot()
ContextSnapshot   timestamp · fields
```

不同来源独立：`DeviceContextProvider` / `UsageContextProvider` / `CalendarContextProvider` /
`MotionContextProvider` / `HealthContextProvider`。**不存在"大一统 ContextEngine"。**

Aggregator 只负责读取、组合、过期判断、最小化；**不负责**记忆 dedup、人格逻辑、医疗推断、
Agent planning、tool execution。

### 19.14 Context 的服务器输出必须是结构化摘要

不把大量 raw data 塞进 prompt：

```text
<context>
local_time: 22:41
battery: 31%
charging: false
screen: interactive
activity: recently_active
steps_today: 6842
next_event:
  title: 算法课
  starts_in: 39m
</context>
```

而不是"过去两小时每秒 sensor records"。**Context 必须有 TTL**；过期信息不得继续冒充"现在"。

### 19.15 Context 与主动触发之间必须有 Local Gate

不要每获得一次 Context 就调用 LLM。先用确定规则过滤：

```text
if quietHours:                          stop
if userSpokeWithin(10 min):             stop
if sameReasonNotifiedWithin(2 h):       stop
if noMeaningfulContextChange:           stop
otherwise:                              ask Agent
```

最后 Agent 仍然可以输出 `NO_ACTION`。这样主动陪伴才不会退化成通知骚扰器。

### 19.16 产品隐私姿态

原则不是"知道用户越多越好"，而是：

> **用尽可能少的数据，让角色拥有足够的情境理解。**

默认：`raw sensors local · derived state preferred · health optional · foreground-app awareness optional ·
location off · camera off · ambient microphone off`。敏感 Context 必须单独授权。

用户必须能看到"她现在可以感知什么"，并随时关闭某一类来源。**关闭后必须是真正停止采集，
而不是只从 UI 隐藏。**

### 19.17 与普通聊天 AI 的最终区别

普通聊天产品的基础交互模型是"用户来找 AI"；Banxuan 希望建立的是"AI 长期在用户自己的设备里"。

二次元角色不是 moat，语音本身不是 moat，LLM 本身更不是 moat。真正难复制的是：

> **长期关系状态 + 当前情境 + 设备行动能力，在同一个可信任角色中连续存在。**

### 19.18 冻结后的产品定义

以后所有新功能都应该回答：

```text
它是否让小智更会陪伴？
它是否让她更了解这个用户？
它是否让她更理解此刻？
它是否让她更能替用户做事？
```

四个问题全部为否 → **不做。**

> **Banxuan / 伴星是面向 Android 9+ 手机与 Full Android 手表的长期陪伴型 AI Agent：
> 以角色持续陪伴、可信记忆与受控设备操作为基础，视觉与可选感知按窗口和硬件能力渐进增强。**

```text
Voice      让她能交流
Memory     让她认识你
Context    让她理解现在
Operator   让她能行动
Persona    让以上能力属于"同一个人"
```

这就是 **Companion Agent Runtime**。

### 19.19 两个执行决策（本轮冻结）

```text
1. Context 不单独立项。
   实现 = Android platform APIs DIRECT + RADAR / Home Assistant 小范围 ADAPT。
   AWARE / RADAR-pRMT / Beiwe / TOM 整套引进 = 过度工程。

2. Context 不插队。
   先让 (d) 组合同步层收绿、把 remote / cache / UI 真实接起来。
   Context 现在只作为产品定位与未来硬件 Gate 写入本正本。
   在各参考真机上分别做 C2 Context Capability Probe，不能把一个 OEM 的结果推广到所有设备：
   若系统已暴露 step counter / heart rate / significant motion，连 HAR 都不必写。
```
