# Risk Register

## 当前 Preview 与联调风险（2026-09-30）

| 风险 | 当前状态与处理 |
|---|---|
| 真实语音后端未接通 | Connected Voice 当前首要阻塞；先 S2、本地验证，再认证 HTTPS/WSS 与真实语音联调 |
| 真实记忆服务未部署 | W0–W4 是软件自动验证，不把测试数据或缓存冒充已上线服务 |
| CI 调试签名不稳定 | Preview 仅供测试，不保证覆盖升级；卸载会清空身份、设置与缓存，须提前告知 |
| 硬件与视觉证据不足 | 模拟器通过不代表参考手机、CD12Max 或完整视觉验收通过 |
| Live2D 后续发布审查 | 当前 Preview 不包含 Core/模型；未来启用时仍需单独解决许可证及 ARM64 16 KB 兼容风险，不阻塞当前无 Live2D 的开发 |

以下保留各阶段风险记录；历史条目中的待办应结合当前路线图与对应验证报告阅读。

| 风险 | 等级 | 处置 |
|---|---|---|
| LIVE2D-ARM64-16K | BLOCKED BY UPSTREAM / RELEASE BLOCKER | ARM64 RELRO静态FAIL；与P2B-RUNTIME分离，不无限阻止4KB开发。llvm-readelf复核待做；不改闭源SO、不从其他runtime偷换Core |
| R5 Core ARM64/x86 RELRO末端未16KB对齐 | OPEN / STATIC FAIL | LOAD段均通过但RELRO余数非0；未做运行复现，不修改闭源SO、不自动换版。详见 LIVE2D_BINARY_AUDIT.md；最终APK及16KB环境仍待验 |
| CD12Max 实际 ABI/GL/音频能力未知 | P0C TARGET VALIDATION PENDING | 2026-09-25 ADB 无设备；先完成 Device Probe，禁止提前锁定方案 |
| Cubism Core 或模型授权不适合分发 | P0 | Gate B 前完成 SDK/资产条款审查 |
| 当前 Cubism Java 已移除 ARMv7 | P2B-ABI-GATE OPEN | 官方声明 ARM64/x86/x86_64；实际 AAR 未审计，CD12Max ABI 未知；若仅 armeabi-v7a，审计配套旧官方 release，禁止自研 renderer |
| Cubism Core 制品配套未核验 | P2B-0 OPEN | 用户独立复核 Samples 冻结 commit 与 R5 tag 的 tree 均为 b48b56c447adc7738b84a36047ae635a9f384f59，Framework gitlink 相同，无须迁移源码；Core 仍须独立核验官方来源、ZIP/AAR哈希、API/minSdk、ELF/ABI、16KB及许可 |
| EXPANDABLE_APPLICATION | RELEASE BLOCKER | 用户导入任意模型涉及可扩展应用分类；发布前由 Live2D 确认分类并取得适用特殊 Publication License，不套用个人/小规模一般豁免。独立于开发和 Runtime Gate；见 PHASE_2B_REUSE_GATE.md 官方来源 |
| 模型导入覆盖旧 Avatar / ZIP 资源耗尽 | DESIGN MITIGATED / IMPLEMENTATION PENDING | 不原样复制 Wanyu 先删除旧目录逻辑；staging、有界输入/展开、路径/引用/纹理检查、Core load 后原子激活 |
| SDK 与自定义驱动重复更新 / 嘴型超前播放 | DESIGN MITIGATED / IMPLEMENTATION PENDING | 官方 updater 单一所有权；既有 PCM 唯一音源；嘴型对齐播放并随 flush/generation 清空 |
| Manager 与设备 token 混用 / 本地人格双事实源 | STATIC AUDIT COMPLETE / Runtime pending | Manager 查用户 token 与 Agent 所有权；HTTP200仍可能业务认证失败；本地只展示覆盖，voice/systemPrompt以服务端为准 |
| Server hello 与实际 TTS Opus 采样率不一致 | P0C STATIC PASS / Runtime pending | 冻结 commit 的 bug 已由先红后绿测试复现并修复；Server Hello 与 TTS encoder 均由 `conn.sample_rate` 驱动，预建 encoder 有一致性守卫；仍需真实 TTS Opus 24k 解码验证后关闭 |
| OTA/activation 字段与参考客户端漂移 | P0 | 从 rokid-xiaozhi 抽取并做契约测试 |
| WebSocket v1 二进制无 turn id 导致旧音频回放 | MITIGATED / Phone pending | SessionCoordinator 中央连接/epoch 过滤；abort 先 flush、再发送、再换连接。encoded/PCM 队列竞态与模拟器 AudioTrack pause/flush 已验证；手机听感仍待测，TTS stop 不等于播放完毕 |
| 激活模式与真实服务端不匹配 | Runtime pending | 支持验证码展示/有界 Bootstrap 轮询至 credentials；challenge-only 明确失败，不伪造 ESP32 HMAC；暂无服务器地址 |
| 身份存储损坏导致重新绑定 | MITIGATED | 默认报错不换号；只有明确确认后重置，保存原始文件。API 28 两次独立进程持久化通过 |
| 真实会话入口尚未联调 | Runtime pending | 已接 Debug Session、AudioRecord/AudioTrack 和 Coordinator；本地 Mock 到 PCM 与模拟器设备 API 分别通过，仍无真实服务端/参考手机会话证据 |
| 长期运行累积耗尽重试预算 | MITIGATED | 连续 Ready 60 秒才恢复预算；短暂 Ready 不恢复，旧 epoch/connection 的稳定计时器无效。8 次稳定恢复循环回归测试通过 |
| Codec 自回环不足以证明服务端/设备音频质量 | Phase 1C OPEN | 已补 Mock WebSocket 音频 E2E、queue 竞态和模拟器 AudioTrack flush；仍须参考手机、10 分钟稳定性与 C4 runtime 验证 |
| 采集结束时不足一帧的尾音 | MITIGATED / Phone pending | 录音中不补零、不丢跨 chunk 余数；正常松开只补齐一次最终960帧，并在 listen stop 前发送；取消/打断不补帧。覆盖余数0/1/100/959和重复结束；可听尾音仍须手机验证 |
| 音频焦点/来电/慢设备输出 | OPEN | 当前是前台 Debug Session，离开即关；非阻塞写和有界队列避免无限堆积，但没有 stall watchdog/音频焦点策略，过载终止会话 |
| 模拟器播放underrun | OPEN / Phone pending | 十分钟738轮软件测试通过、队列过载0，但各连接累计underrun1556；未分离段间空闲和有效播放中饥饿，不宣称无卡顿；参考手机须定位并验证听感 |
| Ready早于播放初始化/异步停止采集竞态 | MITIGATED | 首次长测失败证据保留；Hello消费者初始化先于Ready发布，采集失效改为Coordinator同步通知；新增回归及重新运行完整十分钟通过 |
| 本地无 Android 工程基线 | CLOSED | Phase 0B 三模块 skeleton、clean build、单元测试及 lint 已通过，审查已通过 |
| GitHub 仓库动态变化 | P1 | 记录审计日期、commit/ref；依赖版本固定 |
| 本机构建环境缺失 | CLOSED | 已安装 Platform 35、Build Tools 35.0.0、ADB 37.0.1；Gradle 8.9 clean build 已通过 |
| targetSdk 35 在 Android 9/新系统上的行为差异 | P0C | minSdk 已固定 28；真机 Gate 同时验证 Android 9 行为，产品 targetSdk 在后续发布策略中复核 |
| IndexTTS 固定 24k 与可配置 server playback rate 再次分叉 | MITIGATED / Runtime pending | `open_audio_channels()` 已在启动音频线程前校验预建 encoder rate 与 `conn.sample_rate`；不一致明确失败，不再静默错配；长期可增加 provider 显式输出契约 |

## 2026-09-25 新增 / 更新

| 风险 | 等级 | 处置 |
|---|---|---|
| P2B-RUNTIME 仅覆盖 x86_64 / 4 KB / API 28 模拟器 | SCOPE LIMITED / 其余 NOT RUN | 该行已取得像素级与参数级证据（`LIVE2D_RUNTIME_VALIDATION.md`）。但 1600×900 模拟器不是 410×502 手表；设备虽宣告 ARM ABI 却未执行。ARM64 4 KB 与 x86_64 16 KB 两行仍 NOT RUN，模拟器不得替代参考手机 |
| 实时语音 AEC 在廉价全 Android 手表上可能不可用 | P0 / 未验证 | 无免费 Kotlin 软件 AEC 可直接依赖；`AcousticEchoCanceler` 依赖设备 HAL，可能 `isAvailable()==false` 或空转。兜底为 WebRTC AEC3（NDK，体积大）。这决定实时对话与打断能否成立，应在 CD12Max 上尽早原型验证，不要等到集成后才发现 |
| Live2D 集成形态：框架源码曾摊平进 app 模块 | VERIFICATION DONE / PRODUCT ADOPTION OPEN | 已核实 `Framework/framework` 本身就是 `com.android.library`。验证工程已改为「框架作 Gradle library module + 本地 Core AAR」，重构后重跑运行时 Gate 通过。把该形态搬到产品 `app` 侧仍需单独授权（不属本轮），且不得借此进 P2B-1 |
| 官方 Framework module 在本机不可直接 include | ENVIRONMENT OPEN | 官方 module 要求 `compileSdk 36`（本机仅 android-35）与 `java.toolchain = 17`（本机仅 JDK 21），两者均无外网可下载，Gradle 实测 `No locally installed toolchains match`。现以薄适配 module 消费官方未修改源码。补齐 android-36 + JDK 17 后应改用官方 module |
| 框架 GLSL shader 为运行时加载资源 | MITIGATED | shader 在 `Framework/framework/src/main/assets/.../standardES/`，由 `CubismShaderAndroid` 运行时读取。library module 只挂 `java.srcDirs` 会编译打包正常、设备上才失败。适配 module 已挂 `assets.srcDirs`；实测 APK 含全部 36 个 shader 且成像正常。产品侧集成时必须重复此检查 |
| 官方无 Maven/JitPack 制品 | CONSTRAINT / 已确认 | 多方核实均不存在（JitPack 各 tag 构建失败、官方仓库无 `maven-publish`）。本地 module 是唯一路径，不要在设计里假设可 `implementation` 依赖 |
| `connectedAndroidTest` / UTP runner 启动即死 | UTP_RUNNER_BROKEN / workaround validated / non-blocking | `utp.0.log` 仅 267 字节、无 stack trace，在安装任何东西前退出。已用 `adb install` + `am instrument` 绕过并由脚本固化，两方法各独立进程均 `OK (1 test)`。**明确不修**：这是基础设施债，不是 Cubism runtime 失败，继续投入无收益 |
| 冒烟 harness 位于被 `.gitignore` 的目录 | HYGIENE OPEN | `third_party/live2d/sdk-r5/` 因含专有 SDK 被整体忽略，导致 `RuntimeSmokeTest.java` 工作副本不受版本控制。已镜像到 `evidence/tests/cubism_runtime_smoke/` 保全；根因未解决，建议 harness 迁出该目录并以可配置路径引用 SDK |
| Cubism SDK Release License 商业门槛 | RELEASE COMPLIANCE OPEN | 据许可证原文，年营业额超过 1000 万日元的商业使用者须另行取得 Release License。另有 EXPANDABLE_APPLICATION 独立 blocker。发布前必须确认，不得默认套用个人/小规模豁免 |
| 第三方 Android 小智客户端许可不可核实 | REUSE CONSTRAINT | 技术最完整的 `douo/xiaozhi-android` 与 `hlk16/android-xiaozhi` 均**无许可证文件**（逐个文件名 + main/master 全 404），只能 REFERENCE ONLY。可用的 MIT 候选为 `weijia/android-zhi`、`LRchangyu/xiaozhi-esp32-ble`。技术价值高 ≠ 可复制 |
| 逐特性归因已补对照实验 | CLOSED | blink / breath / physics / expression 已由「从 `CubismUpdateScheduler` 移除目标 updater」的 A/B 对照实测归因，4 次连续运行复现；expression 与 `.exp3.json` 声明值精确吻合。**第一版实验曾产生一个 pose 假阳性，已在复跑中识别并撤回**，现结论要求 A/A 空对照 + 多次复现 |
| pose 无行为级证据（资产限制） | N/A BY ASSET / OPEN | 官方 sample **所有**模型的 `pose3.json` 其 `Link` 数组均为空（Haru 4 parts、Hiyori 2、Mao 4、Natori 8），无关联参数即无法据参数决定显隐，对这些模型 pose 按设计就是 no-op。实测一致：6 秒窗口 part 零变化，同一 GL 事件内强制 `ParamArmLA` 跨量程 160 步仍 `maxOpacityDelta=0.0000`。wiring 已验证（CubismPose 实例 + 4 partGroup + updater 注册）。**补测需另找 `pose3.json` 含非空 `Link` 的合法模型** |
| 归因方法不能依赖「变化范围」 | METHOD CONSTRAINT | 该 sample 的 idle motion 播完会随机重选动作，且每帧 `loadParameters()/saveParameters()` 造成跨臂状态携带，导致 A/A 空对照下 42 个参数有 9–17 个范围差异 >0.02。有效判据只有相位无关的「完全不再被写入」（范围恰为 0）。「重启同一 idle motion」已试并否决：会抑制眨眼 updater 并使信号消失 |
| 软件 GL 下间歇出现 `GL_INVALID_VALUE (0x501)` | `SOFTWARE_GL_INTERMITTENT_0x501` / **OBSERVATION**（用户 2026-09-25 裁定） | 在 SwiftShader 模拟器上两次出现于 motion 阶段的 GL 读取处，**同时像素输出完全正常**（3.2 万+ 色、前景 94%、帧间变化 17.8%，无黑屏/崩溃）；硬件 GL 设备从未出现。加前置 drain 后最终一轮 `glErrors=0`，说明是间歇而非必现。最可能来自 clipping mask 的 FBO 设置。降级为记录项而非硬 Gate，理由：`glGetError()` 是全局 sticky state，不先 drain 就无法把错误归因给当前阶段；而像素已独立证明渲染成功。**冻结边界：ARM64 硬件上若出现同一 error，不得沿用「模拟器驱动怪癖」解释** —— 无 GL error ⇒ 可降为 emulator-specific observation；出现 `0x501` 但无视觉/生命周期异常 ⇒ 继续调查且 **P2B-0 暂不完全 PASS**；`0x501` 关联黑帧/缺 mask/崩溃/恢复失败 ⇒ **BLOCKER** |
| sample 静态单例导致整类单进程运行 flaky | MITIGATED / 产品需注意 | 同一顺序曾 `OK (2 tests)`、下一次崩溃于 `LAppPal.loadFileAsBytes → CubismShaderAndroid.getInstance → LAppDelegate.onSurfaceCreated`（Activity 引用失效）。根因是官方 sample 用静态单例持有 Activity。harness 已改为每测试方法独立进程绕过；**产品侧不得照搬该单例生命周期模式** |
| 报告可能夹带本机账户路径 | MITIGATED | Gradle 输出含 `C:\Users\<账户>\.android\...`，会被写进证据报告，违反 `GIT_PRIVACY.md`。已在脚本 `Write-Step` 加 `Redact()`，提交前扫描两份报告确认无残留 |
| 设备 A（1600×900 硬件 GL）已丢失 | PROCESS / 不可恢复 | 我为清理退化 GL 状态执行 `adb reboot`，该命令对这台模拟器触发完整关机并退出进程而非重启，随后无法恢复（本机无监听端口、无第三方模拟器安装），且它不是工作区 AVD。**教训：不要对不属于本工作区的设备执行 `adb reboot`**。其历史证据保留在 `cubism-feature-attribution.txt`，结论已在工作区 AVD 上重新复现 |
| ARM64 runtime 无硬件 | **DEFERRED / NO HARDWARE**（用户 2026-09-25 第二轮裁定） | 不再作为进入开发阶段的硬前置，避免被拿不到的外部资源无限卡住。**但不得写成「ARM64 已验证」或「目标设备已兼容」**：ARM64 与 CD12Max 仍为后续 Target Gate，拿到硬件时用同一 harness 补证据（`run_cubism_smoke.ps1 -Serial <serial>`，不得临时重写） |
| x86_64 API35 16 KB runtime | `X86_64_16K_RUNTIME` / **ENVIRONMENT PENDING** | 16 KB AVD 已建成启动，`getconf PAGE_SIZE` 实测 **16384**；APK 安装成功、`libLive2DCubismCoreJNI.so` **装载成功**（`nativeloader ... : ok`）、EGL/GLES 初始化成功、无属于本 app 的 native 崩溃。**但行为级证据未取得**：模拟器软件 GL 单帧最长 25.5 s，harness `queueEvent` 20 s 上限必然超时。**不得记为 16 KB PASS 或 FAIL**，也不得因此卡住 P2B-1 DEV。详见 `evidence/reports/x86_64-16k-page-size.txt` |
| 页大小指标易被读错 | MITIGATED | `getconf PAGE_SIZE`（用户态/ELF 对齐，16 KB 设备为 16384）与 `smaps KernelPageSize`（内核基页，x86_64 上仍 4 kB）是**两个不同指标**。harness 原先只读后者，在 16 KB AVD 上会误报 4 kB；已改为优先 `getconf`、仅不可用时回退 smaps 并标注来源 |
