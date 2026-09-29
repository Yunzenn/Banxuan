# Reuse Audit — Evidence Freeze

审计日期：2026-09-25。所有证据均固定到 commit SHA；后续升级必须重新审计差异。

| 能力 | 冻结证据 | Class / function | Decision | 许可与采用方式 |
|---|---|---|---|---|
| Protocol | `78/xiaozhi-esp32@64b57d0ba5c2f11a30974a0216dc28221c5b9d5b`：`docs/websocket.md`、`main/protocols/websocket_protocol.cc` | `WebsocketProtocol::OpenAudioChannel`、`ParseServerHello` | ADAPT | MIT；把协议行为重写为 Kotlin，不复制 ESP-IDF transport。若复制实质代码，保留原 MIT copyright/permission notice。 |
| Backend | `xinnan-tech/xiaozhi-esp32-server@788f5301fdd60cc3a8ef74025bfeece9b82b94ce`：`main/xiaozhi-server/core/handle/helloHandle.py`、`core/connection.py` | hello handler 写入 `conn.welcome_msg["audio_params"]`；connection 从 welcome 读取 `sample_rate` | REFERENCE | MIT；只做 P0-SERVER-AUDIO-CONTRACT 验证，失败时才允许最小后端 patch。 |
| OTA / Activation | `mdloverm/rokid-xiaozhi@8e3c920808b113e6f26726db80cdf33f6370dee2`：`app/src/main/java/com/rokid/xiaozhi/network/XiaozhiWebSocketClient.kt` | `XiaozhiWebSocketClient`、`setDeviceInfo`、`fetchConfig`、`ActivationInfo`、`OtaResult` | ADAPT | MIT；抽取为独立 `BootstrapRepository`，不复制 conversation/WebSocket 巨类。复制片段时保留 `Copyright (c) 2026 DLOVER` 和 MIT notice。 |
| Android audio | `DayanJ/xiaozhi-android-native@5fdc51e376fd89b4db0491066635b27663f66e09`：`service/AudioUtil.kt`、`service/XiaozhiWebSocketManager.kt` | AudioRecord/AudioTrack 生命周期实现 | REFERENCE | MIT；只对照权限、线程、初始化和故障处理，不直接复制。 |
| Opus | `lostromb/concentus@3885c4e46513ef0fc81fca100189e54f1714c6ca`：`Java/Concentus/src/main/java/org/concentus/OpusEncoder.java`、`OpusDecoder.java` | `OpusEncoder`、`OpusDecoder` encode/decode API | DIRECT DEPENDENCY candidate | BSD 风格许可；优先依赖发布制品/固定源码版本，不复制 codec。分发物必须复现 LICENSE 中版权、条件与免责声明。 |
| Live2D | `Live2D/CubismJavaSamples@8ce6803de7030a4816bccd8a3efcad69ea1d1186`：`Sample/src/full/.../LAppDelegate.java`、`LAppLive2DManager.java`、`LAppModel.java` | lifecycle、model loading、render loop、motion | SDK / REFERENCE | Sample 受 Live2D Open Software License；Core 受 Proprietary Software License；商业发布可能需要 Release License；样例模型另受 Free Material/各模型条款。不得按 MIT/Apache 处理。 |
| Native Live2D integration | `Voine/ChatWaifu_Mobile@14092ac66c2afd51de06bb126fd102cec869eb8e` | Android/Native Live2D 集成 | ADAPT（2026-09-25 更正，原判 REFERENCE ONLY 有误） | **MIT**，Copyright (c) 2023 weirdseed。原文照写「未发现 LICENSE」是错的：许可证文件名为英式拼写 `LICENCE`，`LICENSE` 才会 404。已直接抓取 `main/LICENCE` 全文（HTTP 200，1066 B，"MIT License / Copyright (c) 2023 weirdseed"）核实。MIT 只覆盖其自有代码；其 vendored `Live2D/src/SDKRoot/**` 仍受 Live2D 许可，内置 ATRI/Amadeus/Yuuka 模型属第三方 IP，不得沿用。 |
| Product behavior | `TOM88812/xiaozhi-android-client@30a0c80446a3a88772945244739c0e69b79c647c` | Flutter Android/iOS 行为 | REFERENCE | Apache-2.0；不引入 Flutter runtime。 |
| MCP | `stixez/droid-mcp@aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103` | `DroidMcp` builder、`ToolRegistry`、device/settings/vibration/alarms/apps modules | **P1 ADAPT 已收紧为 REFERENCE / CANDIDATE（2026-09-26，见下方专条）** | Apache-2.0 与 minSdk 28 均已核实；但**每个 native tool 模块都依赖 `droid-mcp-core`**，而 core 直接依赖 Ktor Server + Netty + SSE，所以「模块可拆」只是 PARTIAL PASS。白名单选模块、禁止 `addAll()` 仍然成立，但**未做体积/Dex 实验前不得引入**。 |
| Avatar import | Android SAF + `ZipInputStream` 或成熟 ZIP library | select → validate → copy → private storage | DIRECT PLATFORM REUSE | 默认复制进 app private storage，导入完成后不依赖原 URI。只自研 canonical path、数量、压缩/解压体积、嵌套深度等安全策略，不自研压缩引擎。 |

## Freeze rules

Phase 2B 增量审计见 PHASE_2B_REUSE_GATE.md（精确路径、函数、SHA、采用方式）与 MANAGER_API_AUTH_AUDIT.md：

- Cubism Framework `ed15cb21a466893381d1dbddce0da943c7fe9a0f`：DIRECT 官方 metadata、参数枚举与动画更新器；Core AAR 尚未取得，不把 Samples SHA 当成 Core 版本。
- Wanyu `f873e137e224192fbac72021536054ed4a5ad044`：ADAPT importer 流程、EmotionMapper 建议、纯 lipsync shaping；不复制先删旧模型行为、不搬 Native runtime 或第二播放链。
- AIRI `a142a053fdc304666ba7caf8462678b79187f8ea`：REFERENCE 更新顺序/单一 updater 所有权，不迁移插件技术栈。
- 冻结 Xiaozhi Manager：DIRECT API / ADAPTER，复用 Agent/Voice/systemPrompt；用户认证独立于 WS 设备认证。未新增 `/companion/*` 服务。
- 本轮未复制以上源码或模型；后续复制时需落实 LICENSE_MATRIX 中的原始版权与许可义务。

Phase 1C 落地：上述 Concentus candidate 已转为固定源码依赖，冻结 SHA 不变。`third_party/concentus` 保存 `git archive` 导出的未修改 Java 源码及 LICENSE；构建哈希校验、编译并依赖生成 JAR，未重写 codec。细节见该目录 README 与 LICENSE_MATRIX.md。

- 依赖或参考升级到不同 commit/tag 前必须重新审计。
- 没有明确 LICENSE 的仓库一律 REFERENCE ONLY。
- 任何复制的 MIT/Apache/BSD 源码都保留文件版权头，并在发行包中附带相应许可证/NOTICE 要求。

---

# 2026-09-25 第二轮调研（「去 GitHub 找现成的」）

## 方法与局限（先读，它约束下表每一个许可结论）

- `web_fetch` 在本环境**无法访问 github.com**：`github.com` 解析到 `198.18.0.15`（保留段地址，等同 DNS 沉洞），`raw.githubusercontent.com` 被直接拒绝。
- `api.github.com` 返回 **HTTP 403**（限流/受限），无法用 API 的 license 字段核实。
- 有效路径是 **Node.js `https` 直接抓 `raw.githubusercontent.com` 原文**。对每个候选，按 `main`/`master` × `LICENSE`/`LICENSE.md`/`LICENSE.txt`/`LICENCE`/`COPYING`/`license` 逐个探测，命中即停。**未命中即判「无许可证文件」，未抓到的内容一律标 UNVERIFIED，不得采用。**
- 因此下表的许可结论是**读了许可证全文**得到的，不是搜索结果标签；但**最后提交时间、星数未逐一核实**，不作为判定依据。

## 许可证核实结果

| 候选 | 分支/文件 | 许可（读原文核实） | 判定 |
|---|---|---|---|
| `Voine/ChatWaifu_Mobile` | `main/LICENCE` 200, 1066 B | **MIT**, (c) 2023 weirdseed | ADAPT（**更正**，见上表） |
| `weijia/android-zhi` | `main/LICENSE` 200, 1080 B | **MIT**, (c) 2025 Android-Zhi Contributors | **REFERENCE ONLY**（深入审查后由 ADAPT 候选降级，见第三轮） |
| `LRchangyu/xiaozhi-esp32-ble` | `main/LICENSE` 200, 1140 B | **MIT**, (c) 2025 Shenzhen Xinzhi Future Technology Co., Ltd. | **REFERENCE ONLY**（无 Android 源码；OTA 契约可移植，见第三轮） |
| `Nexthubs/VTuber` | `main/LICENSE` 200, 1216 B | **MIT**, (c) 2025 Yi-Ting Chiu | REFERENCE（桌面平台） |
| `gameswu/NyaDeskPetAPP` | `main/LICENSE` 200, 1070 B | **MIT**, (c) 2026 gameswu | REFERENCE |
| `soniqo/speech-android` | `main/LICENSE` 200, 10757 B | **Apache-2.0** | 待评估（VAD/ASR） |
| `douo/xiaozhi-android` | 全部 404 | **无许可证文件** | **REFERENCE ONLY** |
| `hlk16/android-xiaozhi` | 全部 404 | **无许可证文件** | **REFERENCE ONLY** |
| `Live2D/CubismJavaFramework` | `master/LICENSE.md` 200 | Live2D Open Software License | DIRECT（本地 Gradle module） |
| `Live2D/CubismJavaSamples` | `master/LICENSE.md` 200 | Live2D Open Software License | REFERENCE |

**注意**：`douo/xiaozhi-android` 技术上是目前最完整的 Android 小智客户端（双传输抽象：WebSocket + MQTT、有 Releases），但**没有许可证**。按既定规则，它只能读思路，一行代码都不能抄。技术价值高 ≠ 可用。

## 重大发现 1：不需要 vendor 整个 Sample（本地已核实）

`Framework/framework/build.gradle` 本身就是：

```gradle
plugins { id 'com.android.library' }
android { namespace = "com.live2d.sdk.cubism.framework" ... }
dependencies { compileOnly(fileTree(dir: '../../Core/android', include: ['Live2DCubismCore.aar'])) }
```

并带 `src/main/AndroidManifest.xml` 与完整的 `rendering/android/` 渲染包：`CubismRendererAndroid`、`CubismShaderAndroid`、`CubismClippingManagerAndroid`、`CubismRenderTargetAndroid`、`CubismOffscreenManagerAndroid`、`CubismClippingContextAndroid`、`CubismDrawableInfoCachesHolder`、`CubismRendererProfileAndroid`。

**结论**：框架应作为 Gradle module 依赖，而不是把 `Framework/framework/src/main/java` 摊进我们的模块。**此项已于 2026-09-25 在验证工程落地**：新增 `runtime-smoke/cubism-framework/build.gradle`（薄 `com.android.library` 适配 module，源码/资源指向官方未修改的树），app 模块改为 `implementation project(':cubism-framework')`，重构后重跑运行时 Gate 通过。

**重要环境约束（实测）**：官方 `Framework/framework/build.gradle` **无法在本机直接 include**——它要求 `compileSdk 36`（本机只有 android-35）且声明 `java.toolchain = 17`（本机只有 JDK 21），两者均无外网可下载；Gradle 实测报 `Cannot find a Java installation ... {languageVersion=17}` / `No locally installed toolchains match`。因此当前用适配 module。若补齐 android-36 + JDK 17，应删掉适配 module 直接 include 官方 module。

**配套坑（必须记住）**：框架的 GLSL shader 位于 `Framework/framework/src/main/assets/com/live2d/sdk/cubism/framework/shaders/standardES/`，由 `CubismShaderAndroid` 在**运行时**加载。library module 只挂 `java.srcDirs` 会编译、打包一切正常，到设备上才失败。适配 module 因此同时挂了 `assets.srcDirs`，实测 APK 内含全部 36 个 shader 文件且截图正常成像。

## 重大发现 2：官方没有 Maven/JitPack 制品（否定结论，已多方核实）

未找到任何官方或第三方的 Cubism Java/Android Maven 制品；JitPack 对 Live2D 各 tag 全部构建失败；官方仓库不含 `maven-publish`。因此**必须**以本地 module + 本地 `Live2DCubismCore.aar` 的方式集成，不存在 `implementation("com.live2d:...")` 这条路。

## 重大发现 3：官方 Java 侧没有 lipsync，但框架层已经给好了挂钩（本地已核实）

`Sample/src/full/java/com/live2d/demo/full/LAppWavFileHandler.java` 第 91-95 行：

```java
public float getParameter() {
    // Lip sync is not implemented in this sample.
    // To enable lip sync, compute the RMS from the audio buffer
    return 0.0f;
}
```

而 `Framework/framework/src/main/java/.../framework/motion/` 下已存在完整更新器集合：`CubismLipSyncUpdater`、`CubismEyeBlinkUpdater`、`CubismExpressionUpdater`、`CubismPhysicsUpdater`、`CubismPoseUpdater`、`CubismUpdateScheduler`、`IParameterProvider`。

**结论**：嘴型驱动**不需要**自研效果框架，也**不需要** MotionSync（无 Java/Android 变体）。只需实现一个 `IParameterProvider`，从 PCM 算 RMS 包络并归一化后喂给 `CubismLipSyncUpdater`——约 50 行，且这是官方在源码里明确指名的做法。这就是「不重复造轮子」在本项目的具体落点。

## 重大发现 4：AEC / 打断是最大风险，但服务端 AEC 可能使其不必在设备侧解决

- 没有免费的 Kotlin 软件 AEC 可直接依赖。
- `android.media.audiofx.AcousticEchoCanceler` 依赖设备音频 HAL 实现，在廉价全 Android 手表上可能 `isAvailable()==false` 或形同空转。
- 兜底为 `webrtc-sdk/android` 的 AEC3（NDK 集成，体积大）；低延迟播放兜底为 `google/oboe`（C++/NDK，Apache-2.0）。
- **第三条路，可能是最适合手表的一条**：官方协议支持**服务端 AEC** —— 客户端在 hello 里声明 `features.aec = true`，并改用二进制协议 v2（帧内带 `timestamp`）让服务端做回声消除。若该路径可用，则廉价手表不必承担设备侧 AEC，风险性质从「可能做不了」变为「服务器是否支持」。需与所用服务端确认，见第三轮。
- `Voine/ChatWaifu_Mobile` 内的 OVRLipSync 模块是 **Meta Oculus 专有许可**，不在该仓库 MIT 覆盖范围内，且作者已放弃该路径，**REJECT**。
- `DanielSWolf/rhubarb-lip-sync` 为 MIT，但是桌面离线批处理（PocketSphinx），**非实时**，运行时 REJECT，只可作离线预烘焙参考。

## 重大发现 5：EGL context lost 的现成参考

`Live2D/CubismAndroidLiveWallpaper`（2021）中 vendored 的 `net/rbgrn/android/glwallpaperservice/GLWallpaperService.java` 手写了 `EglHelper`，在 `eglSwapBuffers` 后检查 `EGL11.EGL_CONTEXT_LOST` 并重建 context/surface。属 REFERENCE；若复制该文件需另行遵守其 Apache-2.0 归属要求。

## Live2D 合规条款（据许可证原文，非第三方摘要）

两个许可必须分清，不可混为一谈：

- **Live2D Open Software License**（Framework + Samples）：§2.1 授予使用/复制/演示/修改的权利；§2.2.1 允许在「已并入自己作品」的前提下分发；§5.1 禁止超出明示范围的修改，且不得删除或改动许可标识；§2.3 若向 §2.2.1 之外分发衍生作品，需另行签署 Live2D Publication License。**不是 OSI 认证许可，GitHub 不显示 license 标签，绝不可当 MIT/Apache 处理。**
- **Live2D Proprietary Software License**（Cubism Core）：§6.1 禁止修改/移植/改编；§6.2 除明示许可外禁止分发、披露、捆绑。Core 是不透明二进制，只固定版本，永不修补。
- **两者共同**：年营业额超过 **1000 万日元**的商业使用者须取得 Cubism SDK Release License。这是发货前的合规待办，应尽早确认。

## 本轮未做的事

未复制任何第三方源码，未引入任何新依赖，未修改 `app`/`core-protocol`/`core-audio`，未变更已冻结的 SHA。上表中除 ChatWaifu 许可更正外，其余均为新增候选，采用前需逐条落到 `LICENSE_MATRIX.md` 的版权与 NOTICE 义务。

---

# 2026-09-25 第三轮：候选深入审查（donor audit）

对第二轮筛出的两个 MIT「Android 客户端」候选做了逐文件审查（下载 tarball、解析完整文件树、读全部源码）。结论**推翻了第二轮的 ADAPT 候选判断**：

## `weijia/android-zhi` → REFERENCE ONLY（原判 ADAPT 候选，现撤回）

MIT 属实，但**不是可用代码供体**：全仓库仅 65 个文件、**4 次提交**、**从未编译通过**。判据：

1. **没有 Gradle wrapper**（无 `gradlew`/`gradle/wrapper/`），但其 CI 却执行 `./gradlew assembleDebug` —— 说明该 CI 从未成功过。
2. `PreferencesDataStore.kt` 声明 `Flow<Boolean>` 却用 `stringPreferencesKey` 读取，类型不成立。
3. `MainViewModel` 构造函数注入 Android `Service`，Hilt 依赖图无效。
4. `Theme.kt` 使用 `Color.White` 但该文件未 import（Kotlin import 是文件级的）。
5. 依赖 `io.github.tans5:opus:0.10.0` 在 Maven Central **不存在**，且 `settings.gradle.kts` 未配置 JitPack。

更关键的是**协议实现是错的**，不能当参考实现用：

- **JSON 大小写错误**：用 Gson 默认命名输出 `audioParams`/`sampleRate`/`frameDuration`，而真实 v1 线格式是 snake_case（`audio_params`/`sample_rate`/`frame_duration`）。
- **根本没有 Opus**：hello 里宣告 `format="opus"`，但录音端产出裸 PCM16 并原样 `sendAudio()` 发出去；依赖声明了却从未 import。`openAudioChannel()` 只是翻了个布尔量，从不等待服务端 hello。
- 声明了 `enableAutoReconnect()` 但该标志**从未被读取**；`aecEnabled` 开关**从未被消费**（安慰剂开关）；`isActivated` 从未被写入。README 宣称的「Opus 编解码 / 低延迟 / 设备激活 / 自动重连」**全部未实现**。
- 设备 ID 用 `System.currentTimeMillis()+random`，不是稳定 MAC/UUID，会破坏服务端激活绑定。

**结论**：读作一次性 LLM 脚手架，不是可移植的移植版。其价值仅在于**反面教材**：本项目应避免「声明了但没接线的开关」这类安慰剂配置。

## `LRchangyu/xiaozhi-esp32-ble` → REFERENCE ONLY，但 OTA 契约是本次调研最有价值的产出

**该仓库里没有任何 Android 源码**：1218 个文件中 `.kt`/`.java`/`.gradle` 数量为 **0**。README 所说的「附带安卓版的 APP」是 `main/ble/ble_wifi_cfg_1.3.1.apk` —— 一个 **21.9 MB 预编译 Flutter 二进制**（用于 BLE Wi-Fi 配网，不是语音客户端），仓库内无源码，许可不可核实。**二进制 blob 不能作为代码供体。**

它是 ESP-IDF/CMake 固件工程（`idf >= 5.5.0`），其协议/音频/OTA 核心同步自上游 `78/xiaozhi-esp32`（同样 MIT，需一并署名）。

**真正有价值的是 `main/ota.cc`（477 行）—— 激活/OTA 的完整契约，可直接译成 Kotlin**：

- 默认端点 `https://api.tenclass.net/xiaozhi/ota/`。
- 请求头：`Activation-Version`（有 eFuse serial 时为 `2`，否则 `1`）、`Device-Id` = MAC、`Client-Id` = board UUID、`Serial-Number`、`User-Agent`、`Accept-Language`、`Content-Type`。
- 响应字段：`activation{message, code, challenge, timeout_ms}`（用户输入控制台的激活码）、`mqtt{}` / `websocket{url, token, version}`（服务端下发传输配置）、`server_time{}`、`firmware{version, url, force}`。
- 激活：`POST <ota_url>activate`，body `{algorithm:"hmac-sha256", serial_number, challenge, hmac}`；HTTP `202` = 待激活继续轮询，`200` = 成功；重试 10 次，超时退避 3 s、错误退避 10 s。

不可移植的部分：`esp_ota_*`、eFuse/HMAC 计算、FreeRTOS event group、AFE —— 这些必须用 Kotlin 重写，**不是复制**。

其余结论：

- **协议参考更权威**：`docs/websocket.md`（495 行）+ `main/protocols/websocket_protocol.cc` 是规范级参考，可用来审计我们既有 `core-protocol` 是否漏了 `stt`、`llm.emotion`、`tts.sentence_start`、`system{reboot}`、`alert`、以及 `listen.state="detect"`。另有一条对本项目有用的协议事实：**`type:"iot"` 已被废弃，改用 `mcp`**。
- **音频帧格式**：v1 = 二进制帧内裸 Opus 负载（靠 WS text/binary 区分）；v2 = `BinaryProtocol2{version,type,reserved,timestamp,payload_size,payload}`（`timestamp` 供服务端 AEC）；v3 = `BinaryProtocol3{type,reserved,payload_size,payload}`。
- **打断策略可直接借用**（这是本项目要回答的核心设计问题）：`SetListeningMode(aec_mode == kAecOff ? AutoStop : Realtime)` —— AEC 可用则 `realtime` 全双工可打断；AEC 不可用则 `auto_stop` 半双工。播放中触发唤醒词则 `abort{reason:"wake_word_detected"}`。
- UI 是 LVGL/C，与 Android 无关；无测试。

## 本轮采用清单（保守，未执行）

以下均为「移植契约/审计既有实现」，**不是复制代码**：

1. 把 `ota.cc` 的激活/OTA **契约**译成 Kotlin（端点、请求头、响应字段、`202` 语义、重试退避）。
2. 用 snake_case hello schema 作为 `core-protocol` 的一致性校验。
3. 对照上游补 `stt` / `llm.emotion` / `tts.sentence_start` / `system{reboot}` / `alert` / `listen.detect`，以及 `iot`→`mcp` 的废弃关系。
4. 采用「AEC 可用 ⇒ realtime 可打断；不可用 ⇒ auto_stop 半双工」的听音模式策略；并优先确认**服务端 AEC**（`features.aec` + 二进制 v2 带 timestamp）是否可用。
5. 把 `docs/websocket.md` 作为协议测试 oracle（若 vendor 需署名）。
6. 不要采纳 `weijia/android-zhi` 的 `minSdk 24` / AGP 8.8.0 / Compose BOM，也不要复制它的安慰剂开关反模式。
7. MIT 义务：`android-zhi` 为 `Copyright (c) 2025 Android-Zhi Contributors`；`xiaozhi-esp32-ble` 为 `Copyright (c) 2025 Shenzhen Xinzhi Future Technology Co., Ltd.` 与 `Copyright (c) 2025 Project Contributors`，且其协议/OTA/音频核心同步自上游 `78/xiaozhi-esp32`，需一并署名。


## Companion-UI references (2026-09-26)

Candidates reviewed for the P0-1 product shell. **The licences below are as reported by the user during
review and have NOT been independently verified against each repository's LICENSE file** — web search and
fetch were unavailable in this session. No code may be reused until that verification lands.

| Repository | Reported licence | Intended use | Must NOT copy |
|---|---|---|---|
| `DevEmperor/WristAssist` | Apache-2.0 (unverified) | wrist interaction and UI patterns; standalone watch manifest; RECORD_AUDIO usage | its hard dependency on `com.google.android.wearable` — it is Wear-OS-bound and is not a base for CD12Max |
| `dudu-Dev0/WearGPT` | MIT (unverified) | plain-Android small-screen page shell (new chat / history / settings / about) | Gradle files embed signing credentials |
| `Namakamoto/WearGPT` | Apache-2.0 (unverified) | speak -> answer -> TTS interaction on a watch | Wear OS specifics |
| `crackedpotato007/ChatGPT-WearOS` | unverified | page organisation | — |
| `SayccBr/WearOS_Android_Bidirectional_Chat` | unverified | technical reference only | not a product base |

Decision: build the Xiaozhi shell ourselves on `core-audio` + `core-protocol` + the Phase 2A Home, taking
**patterns only** — no code, no architecture. CD12Max is Full Android rather than Wear OS, so
Wear-OS-bound bases are excluded by construction, not by preference.

## Memory / voice references (2026-09-26, verified)

Unlike the table above, the licences below were checked **directly against each repository's LICENSE file**
by the user, not merely reported. Recorded so the reuse gate can cite a verified source rather than a claim.

| Project | Licence | Status | Intended use |
|---|---|---|---|
| `JieRobot/wanyu-ai-android` | MIT | **VERIFIED** | Primary schema/design reference for `core-memory`: `MemoryRepository`, `UserProfileEntity`, `MemoryEntity`, `MemoryLinkEntity`, `EmotionEngine`; importance, time decay, staged confirmation, dedup, character scoping |
| `mem0ai/mem0` | Apache-2.0 | **VERIFIED** | Backend memory store and candidate-retrieval substrate |
| `samdotmak/jev-recall` | MIT | **VERIFIED** | Conditional second-stage reranker (P0-6) |
| `libingzheren/Jev-Mem` | MIT | **VERIFIED** | P1+ A/B experiment only; Python 3.11 research backend, never on-device, never a P0 blocker |

`Voine/ChatWaifu_Mobile` stays **REFERENCE ONLY**: its README states the bundled models may not be used
commercially, and its code licence was not confirmed.

**One canonical schema, not three memories.** The typed `CanonicalMemory` (PROFILE / EVENT / EPISODE /
RELATION, with importance, timestamp, source, character scope and provenance) is the semantics. Mem0 and any
vector store are implementation substrates underneath it; Jev Recall is a conditional reranker on top. No
component other than `CanonicalMemory` may describe itself as "the memory".

Reranking is conditional by hard rule, not by future optimisation — the voice path has a latency budget:

```
small talk / the current turn already carries the needed context   -> no rerank
PROFILE or EVENT query, near-tied candidate scores, or cross-event association   -> Jev Recall
```

## AndroidX Room and the official cache samples (2026-09-27)

Checked while building `:core-memory-cache-android`, because "add a cache" is exactly the kind of task
where a hand-rolled persistence layer gets written by accident.

| Project | Licence | Status | Intended use |
|---|---|---|---|
| AndroidX Room (`androidx.room:room-runtime/room-ktx/room-compiler`) | Apache-2.0 | **DIRECT** | The persistence layer itself, via KSP. No custom SQLite wrapper |
| `android/nowinandroid` — `OfflineFirstNewsRepository`, `NewsResourceDao`, `NiaDatabase` | Apache-2.0 | **ADAPT** | Cache topology: reads come from local storage, the network result is written back, DAO uses `@Upsert`, schema is exported |
| `android/architecture-components-samples` `NetworkBoundResource` | Apache-2.0 | **REFERENCE** | The classic cache-then-network shape. Repository is archived, so it is read and not imported |
| `android/architecture-samples` Todo repository | Apache-2.0 | **REJECT (mutation pattern)** | It writes locally and pushes to the network asynchronously, and its own comments say a real app needs more robust sync. That is precisely the offline-mutation model rejected here |
| `JieRobot/wanyu-ai-android` Room/staging | MIT | **REFERENCE** | Its staging experience is readable, but it lets the client own memory authority, which is the opposite of this architecture |

Where Banxuan is deliberately more conservative than Now in Android: memory is not a news cache. NIA's
local repository can act as the app's source of truth; here the server stays the canonical authority and
Room holds only a last-known copy. There is no offline mutation queue, no dirty flag, no retry worker and
no optimistic local confirm, because "she has stopped believing this" is not a state a product about
trust may be in before the authority has accepted it.

Room is also used exactly as published - official dependency, KSP, `@Upsert`, exported schema - with no
custom SQLite layer on top.

## Context / Awareness sources (2026-09-28)

Checked before writing any Context code, because "give the agent situational awareness" is exactly the
kind of goal that turns into adopting a research-grade instrumentation framework.

**The first choice is not a framework at all.** Android's own APIs cover the Tier 0–2 sources, are present
on API 28, add no runtime, and are the easiest thing to audit:

```text
SensorManager · BatteryManager · PowerManager · UsageStatsManager
BroadcastReceiver · CalendarContract · AlarmManager · PackageManager feature detection
```

| Project | Licence | Status | Intended use |
|---|---|---|---|
| Android platform APIs | — | **DIRECT** | Everything at Tier 0–2. No third-party instrumentation framework |
| `RADAR-base/radar-commons-android` | Apache-2.0 | **ADAPT** | `PhoneUsageManager`'s incremental `queryEvents` with a saved `lastTimestamp` rather than rescanning history; battery receiver; sensor availability handling |
| `home-assistant/android` | Apache-2.0 | **ADAPT** | `HeartRateSensorManager` / `StepsSensorManager`: read a `TYPE_HEART_RATE` or `TYPE_STEP_COUNTER` value and **unregister immediately**. Sampling pattern only |
| `AWARE Framework` | Apache-2.0 | **REFERENCE** | Context source and permission inventory; background-run failure modes; OEM battery-optimisation experience. Whole stack rejected as too heavy |
| `RADAR-base/RADAR-pRMT` | Apache-2.0 | **REFERENCE** | Long-running passive-monitoring architecture. Carries Firebase, remote configuration and a research pipeline this product does not need |
| `beiwe/beiwe-android` | BSD-3-Clause | **REFERENCE ONLY** | Background-task recovery and frequency configuration. Also the **anti-pattern reference**: background location, ambient audio and continuous behaviour collection are technically possible and explicitly not what this product does |
| `TOM-Client-WearOS` | MIT | **REFERENCE** | The `sensor → local Room → WebSocket → server` shape. `minSdk 30` plus Wear OS / Wear Health Services / Compose / Hilt / WorkManager / Ktor, against API 28 Full Android with native Views |
| `Cheiineeey/always-here` | **README says MIT, LICENSE is AGPL-3.0** | **REFERENCE ONLY** | Proactive interval, quiet hours, activity-aware triggering, `NO_ACTION`, anti-repetition. **No code may be copied until upstream reconciles the licence** — the README's MIT claim is not the repository's licence |
| `OPPO-Mente-Lab/X-OmniClaw` | Apache-2.0 | **REFERENCE** | `Perception → Reasoning → Execution → Verification` as one Agent runtime. Its Android side ships Compose, Chaquopy/Python, ONNX Runtime, ML Kit, Retrofit and NanoHTTPD, which is far outside this device's budget |

Two things this audit settles, both recorded in `PRODUCT_REQUIREMENTS.md` §19:

* **Context does not become its own project.** Platform APIs direct, plus small ADAPT from RADAR and Home
  Assistant. Pulling in AWARE, RADAR-pRMT, Beiwe or TOM wholesale would be over-engineering.
* **No HAR model.** If the device already exposes a step counter, significant motion or heart rate, deriving
  "is the user walking" from raw accelerometer data would be rebuilding something the system already
  provides. Tier 4 is off by default until a real device says otherwise.

The licence discrepancy on `always-here` is the second time a README and a LICENSE file have disagreed in
this audit; the LICENSE file governs, and an unverified licence is written as unverified.

## XiaoZhi Differential Audit (2026-09-28)

XiaoZhi (`com.huihongcloud.xiaozhi` v1.8, a watch-focused modification of the open XiaoZhi AI protocol
ecosystem) is treated here as a **working prototype of the wrist form factor**, not as a base to fork. It
demonstrates that "animated character + voice agent + device tools on a watch" is a viable product shape,
which removes that question from our risk list. It does **not** solve long-term memory, situational
awareness or a trust surface, which remain this project's own work.

### Evidence levels, kept separate from the decisions

Three levels appear below and must not be conflated:

```text
DIRECT ARTIFACT INSPECTION
  We opened the file ourselves: zip layout, model3.json contents, APK entry listing, hashes.

SOURCE-VERIFIED STATIC AUDIT
  We read the security review report in full, including its method and its own stated limits.
  It is a static reverse-engineering audit (androguard manifest/signature, DEX disassembly with
  call-chain tracing, manual smali review of named classes, native string extraction).
  Static analysis is not dynamic behaviour, and the report says so itself.

INDEPENDENT APK REVERSE-ENGINEERING VERIFIED
  NOT REACHED. We have not re-decompiled the APK, traced the DEX call chains or reproduced the
  findings ourselves. Scope decisions therefore rest on the report's static evidence.
```

Cross-check that the artifacts are the ones the report audited:

```text
report lists  app-arm64-v8a-release.apk  SHA256 42D20DF3DD5C663899BA0FD68F69185F2A21E1C6159F2449B8B173431774625E
we measured  app-arm64-v8a-release.apk  SHA256 42D20DF3DD5C663899BA0FD68F69185F2A21E1C6159F2449B8B173431774625E
-> the file we inspected is the file the report audited
```

The report also establishes a fact our own inspection could not: the arm64-v8a and armeabi-v7a builds are
two separate APKs whose `classes.dex` is byte-identical, differing only in native libraries. Our
inspection saw a single APK, so the dual-ABI claim is carried at the report's evidence level, not ours.

### Facts established by direct artifact inspection

```text
Mahiro_V1_Lite/ and Mahiro_V1/ zips both contain a doubly nested directory of the same name
  Mahiro_V1_Lite/Mahiro_V1_Lite/Mahiro_V1_Lite.model3.json
Neither zip contains any Motion or Expression file.
model3.json: Version 3; FileReferences = DisplayInfo, Moc, Physics, Textures
  -> no Motions, no Expressions, no LipSync reference
Groups: EyeBlink = ParamEyeLOpen,ParamEyeROpen ; LipSync = (present but empty)
HitAreas: none
  -> the model can blink and swing via physics, but cannot lip-sync and has no motions at all, so
     "wiring up voice will not move the mouth" is confirmed from the asset rather than inferred

APK arm64-v8a native libraries: libonnxruntime.so 15.29 MB, libsherpa-onnx-jni.so 3.23 MB,
  libsaba.so 1.34 MB, libc++_shared.so 1.27 MB, libwebrtc_apm.so 1.16 MB, libopus.so 0.40 MB,
  libeasyopus.so 0.24 MB, libLive2DCubismCoreJNI.so 0.09 MB
KWS asset: assets/kws/encoder-epoch-12-avg-2-chunk-16-left-64.onnx 11.59 MB
The bundled default Hiyori model uses the 2048 texture set, not 8192.
MMD assets carry motion files (welcome.vmd 1.38 MB, idle.vmd 1.06 MB); the Live2D model does not.
```

Not established: the presence or absence of VRM assets (only entries above 1 MB were listed) and the
completeness of the ABI set (a single APK was inspected).

### The matrix

Every row carries a decision **and** the evidence level behind it.

| Capability | Decision | Evidence level | Independent reproduction |
|---|---|---|---|
| Voice / audio path (Opus, full-duplex, AEC) | **ADAPT / BENCHMARK** | source-verified static audit | NO |
| Live2D Cubism loading | **ADAPT** | direct artifact inspection | NO |
| Character behaviour (state-driven motion / expression / lip-sync) | **ADAPT — the SDK already has the mechanism** | direct artifact inspection | NO |
| sherpa-onnx local KWS | **PENDING HARDWARE EVIDENCE** | direct artifact inspection (footprint only) | NO |
| Foreground voice service | **REFERENCE** | source-verified static audit | NO |
| MCP tool schema / taxonomy | **REFERENCE** | source-verified static audit | NO |
| Native app / media tools | **ADAPT** | source-verified static audit | NO |
| Rhino JS plugin execution | **REJECT** | source-verified static audit | NO |
| Shizuku shell execution | **REJECT** | source-verified static audit | NO |
| Server-authorised device capability | **REJECT (authority model)** / REFERENCE (taxonomy) | source-verified static audit | NO |
| General-purpose remote HTTP tool | **REJECT** | source-verified static audit | NO |
| Generic broadcast tool | **REJECT** | source-verified static audit | NO |
| Cleartext `ws://` in production | **REJECT** | source-verified static audit | NO |
| Unsigned plugin installation | **REJECT** | source-verified static audit | NO |
| Server-supplied camera upload target | **REFERENCE capability / REJECT uncontrolled egress** | source-verified static audit | NO |
| Model import rules | **REFERENCE — importer rewritten** | direct artifact inspection | NO |
| Hardware MAC / Android ID as identity | **REJECT** | source-verified static audit | NO |
| Power and background survival | **PENDING HARDWARE EVIDENCE** | — | NO |
| APK / native footprint | **BENCHMARK** | direct artifact inspection | NO |
| Long-term memory, context awareness, trust UI | **not addressed by XiaoZhi — our own main line** | direct artifact inspection | NO |

### Why the security rows are REJECT rather than "use carefully"

The report's own summary is that it found no trojan, no SMS or contact theft, no silent install and no
independent data exfiltration. The risk is different in kind: the design hands a **remote-reachable
channel the ability to execute arbitrary code**.

```text
XiaoZhi:   remote server -> tools/call -> unsandboxed Rhino JS -> injected Java objects -> device capability
           (report: Rhino Context.enter() + initStandardObjects(), with Java objects for
            http/app/media/adb/core/mcp/intent exposed to the script, and no ClassShutter set)
           (report: an optional Shizuku path that executes shell commands when enabled)
           (report: usesCleartextTraffic=true; visionUrl and visionToken supplied by the server at
            initialise time; the plugin store is TLS but performs no signature verification)

Banxuan:   remote intent -> typed tool request -> local policy -> confirmation if required
           -> bounded Android capability -> observed structured result
```

The distinction this audit freezes is therefore one sentence:

> **The remote model or server proposes intent. The watch decides whether anything is allowed to happen.**

That is stronger than "trust our server", and it is what makes the Operator gate (G3) safe to build at
all: a compromised or maliciously deployed server must not by itself become device compromise. It also
means Banxuan's tools are finite, enumerable and auditable - `LaunchApp(packageName = ...)`, never
`app.startByName(arbitraryName)` and never `execShell(anything)`.

### Live2D: the mechanism already exists, the asset is what is missing

The report and the asset point at the same conclusion, and it argues **against** adopting any third-party
animation framework. The official Cubism Framework this repository already holds implements motion
playback, expression selection and lip-sync parameter driving. The Mahiro package contains none of the
data those mechanisms consume.

```text
already present    Cubism LAppModel: motions, expressions, lip-sync parameter driving
missing in asset   any Motion file, any Expression, and LipSync parameter ids
missing in ours    a thin Agent state -> Character behaviour mapping
```

So the work is asset-side plus a thin mapping layer - `LISTENING` to an attentive expression, `THINKING`
to a thinking idle, `SPEAKING` to the lip-sync parameter, `INTERRUPT` to immediate cancellation - not a
second animation stack. The official SDK's own lip-sync tutorial is the specification.

The 2048 texture choice in the Lite package is the right direction: the bundled default already ships at
2048, and a 2048 RGBA texture is on the order of 16 MB against roughly 256 MB for 8192.

### Model import must not assume a well-formed zip

Direct inspection found a doubly nested directory inside both provided zips, which a naive importer
copies to the wrong depth. The importer is therefore specified as:

```text
zip -> scan recursively for *.model3.json -> validate every referenced asset
    -> normalise to a single model root -> copy into private app storage
```

rather than asking the user to know what `model3.json` is or to arrange directories by hand.

### Identity must not regress

The report describes a client that sends device-identifying material (Android ID / MAC class) with its
session. Banxuan already does better: a random, locally administered `DeviceIdentity` persisted locally,
with `deviceId` as the subject partition and no hardware identifier. This audit does not change that; it
records that XiaoZhi is the counter-example that makes the current design worth defending.

### Artifact retention

The APK, the two model zips, the install manual and the security report are **third-party or unlicensed
material, with no LICENSE anywhere among them**. They stay out of this repository: publishing them would
redistribute someone else's application build and character assets, which the frozen boundaries in
`CONTRIBUTING.md` forbid. They are held locally as review inputs only, and this section carries the
conclusions rather than the files.

## Donor audit for the two gaps the XiaoZhi audit left open (2026-09-28)

The XiaoZhi audit narrowed the search to exactly two things: an Agent-state-to-Cubism behaviour mapping,
and a zip/model3 importer that does not assume a well-formed archive. Both were searched directly rather
than generally, and **the reuse gate now has an answer for both**. Further repo-hunting would not change
either decision.

### Evidence levels, again kept separate

```text
LICENCE VERIFIED BY US      queried the GitHub licence endpoint, which reports the SPDX identifier
                            from the repository's own LICENSE file
LICENCE REPORTED ONLY       a reading performed outside this session that we could not re-check
CODE-LEVEL FINDING REPORTED a defect identified by reading the donor's code outside this session;
                            we have not reproduced it and it is not load-bearing for a REFERENCE verdict
```

### Licence verification

| Repository | SPDX (verified by us) | Earlier reported as | Consequence |
|---|---|---|---|
| `ShirokamiRyzen/Mirai-AI` | **MIT** | MIT | the only ADAPT candidate that survives |
| `FatPanda8885/NekoWeather` | **GPL-3.0** | GPL-3.0 | REFERENCE ONLY |
| `miaoxworld/NativeTavern` | **GPL-3.0** | GPL-3.0 | REFERENCE ONLY |
| `J88-cx/AI--smartdock` | **could not verify** — licence endpoint unavailable | GPL-3.0 | REFERENCE ONLY either way |

The one that mattered is the first: an ADAPT decision rests on a permissive licence, so it was verified
rather than recorded from a report. The GPL verdicts only ever supported REFERENCE, and the unverifiable
one stays REFERENCE regardless, so no decision in this section depends on an unverified reading.

### Gap 1 — Agent state to Cubism behaviour

```text
official Cubism motion / expression / lip-sync runtime     DIRECT
third-party animation framework                            REJECT
NativeTavern orchestration semantics                       REFERENCE ONLY / GPL-3.0
AI--smartdock Android state mapper                         REFERENCE ONLY / licence unverified
permissive Android donor for this problem                  NOT FOUND
```

No permissive donor exists for this problem, and that is the expected outcome rather than a search
failure: the execution machinery is already in the official Cubism Framework this repository holds, so
what is missing is not a framework but two things we must supply ourselves - the model asset data, and a
mapping from our semantic state to that model's motions and expressions.

The donors are still valuable as **semantics** references. NativeTavern's orchestrator is worth reading
for its handling of priority, transient actions, cooldown, pending actions, generation cancellation and
restoring a base state after a transient finishes - the parts that are easy to get wrong and that our own
version must handle. To be explicit: that is a description of behaviour to re-derive, not code to adapt,
because the licence forbids copying it.

The layer we write stays deliberately small:

```kotlin
enum class CharacterState { IDLE, LISTENING, THINKING, SPEAKING }

data class CharacterBehavior(val motion: MotionRef?, val expression: ExpressionRef?)

interface CharacterBehaviorMapper {
    fun resolve(state: CharacterState, emotion: Emotion? = null): CharacterBehavior
}
```

Everything below that - playing a motion, setting an expression, driving the mouth parameter from audio -
stays with the official Framework, which already reads the lip-sync parameter mapping from
`.model3.json`. We do not build a second animation stack, and we do not adopt a GPL one.

### Gap 2 — Zip and `model3.json` import

```text
Android SAF + ZipInputStream                  DIRECT (platform)
ShirokamiRyzen/Mirai-AI                       ADAPT candidate / MIT
FatPanda8885/NekoWeather                      REFERENCE (safety semantics) / GPL-3.0
xiaojingyu-likes-you                          REFERENCE ONLY / MIT + no-commercial-use restriction
Whale-Live2D-DeskPet-Android                  REFERENCE ONLY / no LICENSE found
```

`Mirai-AI` is the first donor that matches on all three axes that matter - permissive licence, Android
and Kotlin, and the same problem - and it already implements the shape worth adapting: a SAF Uri into a
temporary zip, recursive discovery of `.model3.json` at any depth, `FileReferences` parsing, moc and
texture validation, extraction into app-private storage, and a canonical relative model path out.

It is **not** to be copied wholesale. Reported defects to fix in our own implementation:

```text
zip-slip check uses startsWith(rootCanonical) with no trailing separator
  -> /data/foo would accept /data/foobar/...
no entry-count, expanded-byte, per-file or depth limits
validates only the first texture rather than every reference
deletes the existing model before installing the new one
```

`NekoWeather` is the reference for the opposite reason: its bounds are the stricter ones
(`MAX_LIVE2D_ENTRIES`, `MAX_LIVE2D_BYTES`, a canonical check with the separator, all textures validated,
staging followed by install). Its licence is GPL-3.0, so it is a **safety oracle** - read the limits and
the pipeline shape, write our own.

### The importer Banxuan freezes

```text
SAF Uri
  -> bounded staging copy
  -> bounded zip extraction: canonical path, entry count, expanded bytes, depth, duplicate path
  -> recursive *.model3.json discovery
       0 found  -> reject
       >1 found -> deterministic selection, or ask the user
  -> parse FileReferences
  -> validate ALL referenced assets (Moc, Textures, Physics?, Pose?, DisplayInfo?, Motions?, Expressions?)
  -> normalise to a single model root
  -> atomic install into private storage
  -> only after success, switch the previous selection
```

The last two steps are deliberate and differ from both donors: a failed or interrupted import must leave
the previously working model untouched, so the user is never left with no character. That also removes
any dependency on the current XiaoZhi convention of "directory name equals `model3.json` file name",
which the doubly nested directories in the provided packages already break.

### What this section does not claim

No donor code has been adapted yet, so no `ADAPT` decision here has produced code. The two
`REFERENCE ONLY` verdicts on GPL and non-commercial licences are decisions **not to read further for
adaptation**, not an assessment that the code is unusable in principle.

## `stixez/droid-mcp` — device capability layer (2026-09-26)

Candidate for the future **G3 Watch Operator**. Verified by the user directly against the repository
(Gradle version catalog, module build files, LICENSE) — not from its README. This supersedes the one-line
summary in the table above, which read as though the SDK were ready to adopt.

```text
LICENSE:
VERIFIED Apache-2.0  (real Apache License 2.0 at the repository root)

PLATFORM:
VERIFIED minSdk 28   (gradle/libs.versions.toml) - aligns with CD12Max / Android 9

MODULARITY:
PARTIAL PASS
  Individual capability modules are separable (not only an `all` artifact):
  device / calendar / settings / apps / alarms / accessibility are separate Gradle modules.
  But every inspected native tool module depends on droid-mcp-core.

IMPORTANT COST:
  droid-mcp-core directly depends on Ktor Server Core + Netty + SSE + ContentNegotiation +
  kotlinx.serialization + coroutines + androidx.core. So even a handful of lightweight native tools
  drags an HTTP/Netty server stack onto the compile classpath. The README calls the HTTP server
  runtime-optional, but the Gradle dependency is not optional, and how much R8 removes cannot be
  assumed - especially for the debug/test builds we currently run.
  Shizuku/root are isolable: `droid-mcp-all` does not include them and requires explicit opt-in.

STATUS:
REFERENCE / CANDIDATE - NOT YET APPROVED FOR PRODUCT DEPENDENCY.
```

Not adopted. The accurate conclusion is not "droid-mcp can be dropped in" but "its tool implementations
are very much worth reusing, while whether the SDK suits a watch APK must be measured first". Reuse-first,
not dependency-first.

Required spike before any decision, and only after G1/G2:

```text
measure  baseline APK -> + droid-mcp-core -> + 5 native modules:
         APK size, Dex/method count, cold start, resident memory
```

Then choose between a real dependency and ADAPTing only the tool implementations.

Still PENDING and only provable on hardware: the APK/Dex delta itself, CD12Max ROM behaviour
(especially background survival), and whether the Accessibility module is usable on 糯米OS at all.