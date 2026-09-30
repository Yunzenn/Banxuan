# Implementation Tasks

## 当前执行入口（2026-09-30）

- [x] W0–W4 Memory Trust 基础设施软件自动验证闭环（不代表真实服务上线）。
- [x] Preview `v0.4.0-preview` 发布；[普通用户安装指南](INSTALL.md)。
- [ ] S2：核查并复用小智后端，保护已有服务，明确部署配置。
- [ ] localhost HTTP / WS 验证。
- [ ] 公网 HTTPS / WSS 与认证。
- [ ] Banxuan Connected Voice：真实 PTT → ASR → LLM → TTS → AudioTrack。
- [ ] 真实 TTS 音频契约 runtime 验证；参考手机与 CD12Max 验收。

真实记忆服务尚未部署。优先级见 [ROADMAP.md](ROADMAP.md)，交接见 [HANDOFF.md](HANDOFF.md)。以下 Phase 清单保留为历史阶段记录，不覆盖当前执行顺序；旧“下一步”不得作为重开已完成工作的依据。

## Phase 0A（完成）

- [x] 建立任务书、复用矩阵和协议初始契约
- [x] 初审指定 GitHub 仓库、定位与许可证
- [x] 补齐 Concentus、rokid-xiaozhi、Cubism 的文件/class/commit/license 级证据

## Phase 0B（PASS / CLOSED）

- [x] 建立 `app`、`core-protocol`、`core-audio` 最小模块
- [x] 创建 Device / Audio / Graphics / Storage Probe skeleton 与 md/json 导出
- [x] 创建 960-sample `PcmFrameAccumulator` 与无补零/无丢余数测试
- [x] 安装 Android SDK Platform 35、Build Tools 35.0.0、Platform Tools/ADB 37.0.1
- [x] `clean test lint assembleDebug` 通过；lint 0 issues
- [x] 4 个 accumulator 测试在 debug/release variant 共执行 8 次，0 failure/0 skipped
- [x] Probe APK 已通过签名和 zipalign 结构验证，minSdk=28

Phase 0B 状态：**PASS / CLOSED，审查已通过。用户已授权 Phase 1 软件开发与目标设备验收并行。**

## Phase 0C（需要真机/服务端）

- [ ] C1 `TARGET VALIDATION PENDING`：ADB 无设备；连接 CD12Max 后完成 DeviceCapabilityReport.md/json
- [ ] C2 `TARGET VALIDATION PENDING`：需要 CD12Max；完成 AudioRecord → accumulator → Concentus → AudioTrack 10 分钟 Gate
- [ ] C3 `TARGET VALIDATION PENDING`：需要 CD12Max；运行冻结 Cubism sample + 合法模型 10 分钟
- [x] C4 `STATIC PASS`：冻结 checkout 上先以测试复现 bug，再完成最小 patch；10 个契约测试及语法编译通过

C4 最终补丁保存在 `evidence/patches/xiaozhi-server-audio-contract.patch`，回归测试保存在 `evidence/tests/test_hello_audio_contract.py`。真实 server/TTS provider 的下行 Opus 抓取与 24k 解码尚未执行，因此状态只能是 `STATIC PASS`，不得标记最终 `PASS`。

P0-SERVER-AUDIO-CONTRACT 失败时只允许修改服务端最小必要代码，Android 端不得猜测采样率。

Phase 0C 状态：**未完成。C1/C2/C3 TARGET VALIDATION PENDING，C4 STATIC PASS（Runtime pending）。与 Phase 1 软件开发并行。**

## Phase 1 vertical slice

状态：**IN PROGRESS**。JVM 单测、模拟器 API 28、参考手机与 CD12Max 四层证据独立记录；软件通过不代表目标机通过。

Phase 1A：PASS WITH CONDITIONS（用户审查）。Phase 1B：已完成 SessionCoordinator、有限退避/认证刷新、集中旧事件过滤、验证码激活轮询、确认后身份恢复、TLS 负向测试及 APK ABI 审计。38 个 core-protocol 测试通过；API 28 模拟器实际进程重启身份一致性通过。验证码模式仅本地自动测试，真实服务端激活、完整应用会话入口仍待联调；challenge-only 硬件 HMAC 模式不支持。详见 PHASE_1B_REPORT.md。以上为Phase 1B历史快照；当前Phase 1C本地链及模拟器长测已通过，真实设备/服务器待验收，见下文。

1. DeviceIdentity + DataStore 重启持久化测试
2. BootstrapRepository（OTA/activation/WS token）
3. ProtocolEvent 与 XiaozhiProtocol 接口
4. Transport / Conversation 双状态机
5. PcmFrameAccumulator + Concentus uplink
6. Server hello 动态 downlink decoder + AudioTrack
7. 本地优先 interrupt 与 stale audio gate
8. 集成测试：Mic → server → STT/LLM/TTS → speaker
9. API 28 构建、真机安装、10 分钟稳定性测试

Phase 1 明确不做：完整 UI、Live2D、Avatar importer、Character、MCP、本地 LLM/ASR、wake word、Root/Shizuku。

## Phase 1C — Audio vertical slice（IN PROGRESS）

Phase 1A 已通过；Phase 1B 用户审查通过，minor follow-up 已落地：稳定 Ready 60 秒恢复 retry budget，短暂 Ready 不恢复；HardInterruptPolicy / ActivationPolicy 已提取，未实现 SoftInterruptPolicy。

- [x] 稳定恢复/快速抖动/旧稳定计时器/可注入打断策略回归测试
- [x] 固定 Concentus 原始源码依赖与归档哈希、随包 LICENSE
- [x] OpusCodec / ConcentusOpusCodec；16k mono 960-sample 上行，显式 playback config 下行
- [x] 真实 Opus 编解码、不同采样率/声道、任意 chunk 分帧与 reset 测试
- [x] AudioCaptureSource / AudioRecord 权限、初始化与线程生命周期（API 28 模拟器验证；手机待测）
- [x] Coordinator 上行音频入口与 generation 隔离
- [x] encoded / PCM 有界队列、AudioTrack 输出与真实 pause/flush（JVM 竞态测试 + 模拟器 API 验证）
- [x] Mock WebSocket E2E：Hello → tts:start → 合法 Opus → PCM → tts:stop（末端为测试 sink）
- [x] 最小 Debug Session 页面（状态、按住说话、打断、包数、队列、generation/connectionId；启动关闭 smoke test）
- [ ] 参考手机 mic → codec → mock/server → codec → speaker

当前不是 Phase 1C 整体验收 PASS；本地软件纵向链已获用户认可。详见 PHASE_1C_REPORT.md。CD12Max 与 C4 runtime Gate 不变。

本轮补充：正常 PTT 松开仅补齐一个最终 960-sample 帧；取消/打断不补帧。新增 API 28 本地 HTTPS/WSS 音频长测，参考手机与真实服务器证据独立验收。

- [x] 正常结束尾帧0/1/100/959、重复结束及wire-order回归
- [x] Hello消费者初始化完成才发布Ready；集中同步采集失效通知
- [x] API28模拟器600770ms、738轮、73次打断重连，Android测试5/5；JVM执行84/84
- [ ] 参考手机诊断underrun和可听连续性（模拟器累计1556，未宣称零卡顿）
- [ ] 真实Xiaozhi STT/LLM/TTS联调，暂无端点

C-A本地软件PASS；C-B模拟器软件稳定性PASS；C-C参考手机PENDING；C-D真实服务器PENDING；C-E CD12Max TARGET VALIDATION PENDING。

## Phase 2A — Product Shell + Static Avatar（并行开发）

状态：**PASS（用户审查）**。不等于 Live2D、真实服务器或目标设备验收通过。

用户批准与Phase 1C设备/服务器验收并行。不是完整Phase 2或Live2D验收通过。

- [x] 产品Home成为启动入口；原Probe/Debug保留在设置的工程诊断入口
- [x] 静态形象、角色名、字幕与连接/录音/等待/回复状态；沿用现有前台会话
- [x] 角色名本地保存；Character与Avatar文件独立
- [x] SAF图片选择 → 大小/格式/像素校验 → 缩放 → 私有PNG原子写入
- [x] HTTPS Bootstrap地址保存/清除；禁止明文、userinfo、query、fragment；不存WS令牌
- [x] Voice/Personality/Live2D明确显示未接入或由服务器控制，不假装生效
- [ ] Live2D SDK集成、ZIP/AvatarPack、安全策略和三层映射配置
- [ ] 真实服务器主页对话/字幕/激活联调
- [ ] 手机与CD12Max屏幕、音频和性能验收

构建与模拟器证据见PHASE_2A_REPORT.md。维持三模块及minSdk28，未增加依赖。

## Phase 2B — Reuse-first Runtime / Avatar

Gate拆分：P2B-RUNTIME PENDING（见LIVE2D_RUNTIME_VALIDATION.md）与LIVE2D-ARM64-16K BLOCKED BY UPSTREAM / RELEASE BLOCKER。当前运行准备停在独立模型许可确认；未开始Home集成。交接见HANDOFF.md。

最新二进制审计见 LIVE2D_BINARY_AUDIT.md：官方R5制品已取得，Framework静态API编译通过，实际ABI为ARM64/x86/x86_64；16KB RELRO发现ARM64/x86异常。Runtime未执行，P2B-0仍NOT PASS，未进入P2B-1。

本轮仅完成源码复用与 Manager auth 静态审计，见 PHASE_2B_REUSE_GATE.md、MANAGER_API_AUTH_AUDIT.md。

- [ ] P2B-0 / P2B-ABI-GATE：源码 ABI 声明已核；用户 SDK/模型许可确认、Core AAR 哈希/ABI/版本配套与 runtime 仍待完成。目标仅 ARMv7 时审计旧官方版本，不自研 renderer。
- [ ] P2B-1：官方 Framework/Core + 合法测试模型；模拟器/参考手机运行
- [ ] P2B-2：官方 metadata 与参数 API → AvatarRuntimeInfo
- [ ] P2B-3：Wanyu 流程适配 + 有界输入/staging/安全策略/原子切换
- [ ] P2B-4：Live2D Home renderer，沿用官方生命周期
- [ ] P2B-5：三层配置持久化与本地展示/服务端人格边界
- [ ] P2B-6：EmotionMapper 导入时自动建议
- [ ] P2B-7：用户 Mapping Editor，保存结果为权威
- [ ] P2B-8：既有 PCM → RMS/shaping → 可配置嘴型；禁止第二播放链
- [ ] P2B-9：Xiaozhi emotion → 已保存的 expression/motion 映射
- [x] P2B-10：Manager 用户认证及 Agent/Voice API 静态审计；真实登录/权限/更新联调仍 PENDING

用户复审：P2B-0 复用设计、Manager 静态审计、Live2D source/API 审计 PASS；许可意识 PASS WITH RELEASE GATE；Core 制品与双模型生命周期 Runtime PENDING；overall NOT PASS。完整制品/Runtime 检查清单见 PHASE_2B_REUSE_GATE.md，取得官方 SDK 前不继续堆基础设施或改 App。

Phase 2B 的 Manager 范围仅 XiaozhiAgentRepository 接口契约。登录页、验证码/SM2、token 持久化和真实授权 UX 全部移入 Phase 2C，先决定手机配对/一次性授权/受限 adapter，再实施。

独立发布任务：EXPANDABLE_APPLICATION / RELEASE BLOCKER；核实 Live2D 分类并取得适用发布许可。不阻止合法开发验证，不随 P2B-0 PASS 自动关闭。

不迁移 Compose/Hilt/Room/NDK，不自写模型或动画引擎，不新建 Voice/Personality 后端。CD12Max C3 保持 TARGET VALIDATION PENDING。
