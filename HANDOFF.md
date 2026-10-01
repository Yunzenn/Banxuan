# Handoff — Banxuan 当前交接

## 当前状态（2026-10-01，产品契约迁移 PR A）

- 本次文档分支基于 main `0f692a1`，不是发布标签的 `0570308`；只改六份契约文档，不改 App、不替换 APK。
- 新定位：Android 9+ / API 28+ 手机与 Full Android 手表上的长期陪伴型 AI Agent。CD12Max 是参考认证设备，不是唯一目标或开发前置。
- `minSdk=28`、Native Views、薄客户端不变；不引入 Wear OS/Compose/新 UI 栈，不支持 Android 8 及以下，不开放任意 shell 或无限制自动化。
- G3 产品名称改为 Android Device Operator；typed tools、权限/确认/审计及 Native API → Accessibility → visual fallback 不变，代码/协议标识符不变。
- `410×502@320dpi = 205×251dp` 永久保留为模拟器回归 fixture，不是真机 density 证据。Live2D 是可选能力，不支持时静态 fallback，不阻塞基础产品开发。
- 后续 PR B 仅做一轮 Adaptive UI：tokens + 窗口驱动的 CompanionLayoutSpec；COMPACT 保住 PTT/字幕，EXPANDED 增加舞台/字幕空间并限制宽度。保留 ProductUi.page() 的 560dp maxWidth，不按型号分支。
- 新四窗口 × fontScale 1.0/1.3 矩阵全部 PENDING，见 [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md)。不改 Voice、Memory semantics、backend、Live2D integration 或 Operator。
- Daylight UI / 本地 Live2D 属于独立开发分支，本次没有将其并入 main，也不把其证据算作本次验证。

- Preview `v0.4.0-preview` 已发布，发布源码 `057030884f2c754a316ac52f7e1a84f75ece39fc`。下载见 [INSTALL.md](INSTALL.md)。
- Memory W0–W4 CLOSED / SOFTWARE AUTOMATED PASS。W4：28/28 instrumentation、32/32 app JVM；见 [验证报告](evidence/tests/W4_VALIDATION.md)。
- 真实记忆服务未部署；Connected Voice 等待真实后端；参考手机、CD12Max 和完整视觉验收没有通过声明。
- 当前顺序：PR A 契约迁移 → PR B 一轮适配 → 小智服务端 S2 → 本机 HTTP/WS → 公网 HTTPS/WSS → 真实语音。之后再做 Live2D polish；不继续扩展平板/折叠屏/旧 Android/Wear OS。
- 复用冻结的小智服务端，不另写语音服务。先核查现有部署、版本和端口归属，保护已有业务；未经授权不修改云端配置。生产客户端不放宽 TLS。
- 验证真实 Server Hello 与 TTS 音频契约；C4 仍为 STATIC PASS / runtime pending，不能只凭可解码就升级 PASS。
- 不新增 W5 内部工程，不把 Live2D 当下一步阻塞项。真实记忆服务另需部署、认证与对话集成。
- GitHub 提交身份使用 `yunzenn`；不上传个人路径、凭据、专有 Core 或第三方 APK。
- 发布 APK 使用 CI 调试证书，不保证覆盖升级。不要重新打包或替换现有标签下的制品来配合文档更新。

## 历史交接：Live2D Runtime Gate（2026-09-25，非当前待办）

以下保留当时的裁决和证据。下文的“下一步”只描述当时计划，当前执行顺序以上节和 [ROADMAP.md](ROADMAP.md) 为准。

### 历史冻结裁决（用户 2026-09-25 裁定；第二轮已调整 Gate）

用户第二轮裁定：**不再把「必须有 ARM64 真机」作为进入开发阶段的硬前置**，否则项目会被一个当前拿不到的外部资源无限卡住。

```
P2B-0
├ x86_64 API28 Runtime              PASS WITH OBSERVATIONS   证据提交 d76dd77，已冻结
├ Cubism feature attribution        PASS
├ lifecycle / GL recreation         PASS
├ Core binary audit                 PASS WITH CONDITIONS
├ x86_64 API35 16 KB runtime        ENVIRONMENT PENDING      见 evidence/reports/x86_64-16k-page-size.txt
├ ARM64 runtime                     DEFERRED / NO HARDWARE   ← 不再是开发阶段硬前置
├ CD12Max                           TARGET PENDING
├ ARM64 16KB                        RELEASE BLOCKER / UPSTREAM
└ overall                           PASS FOR DEV ONLY

DEV-ONLY P2B-1                      AUTHORIZED (2026-09-25 第二轮裁定)
```

**禁止的表述**：不得把当前状态写成「ARM64 已验证」或「目标设备已兼容」。ARM64/CD12Max 仍是后续 Target Gate，拿到硬件时补证据。

### P2B-1 授权范围（严格照此执行）

允许：把**已经验证过的** Runtime 结构接入产品，且只做最小一步。

```
Home
 ↓
AvatarView / Live2D container
 ↓
CubismRuntimeOwner
 ↓
Framework module
 ↓
local Core AAR
```

同时做：`static avatar ↔ Live2D avatar` 切换、model lifecycle、shader packaging assertion、
background/resume、surface recreation、clean release。

P2B-1 第一小步的完成判据：**Home 能稳定显示一个合法 Live2D 模型，且静态 Avatar 能正常 fallback。**

**暂时不做**：ZIP importer、三层高级配置 UI、Emotion mapping、LipSync、Voice/personality。
先完成最小闭环，不要并行铺开。

### 16 KB 实验结论（本轮已执行）

API35 / 16 KB x86_64 AVD 已建成并启动，`getconf PAGE_SIZE` 实测 **16384**。APK 安装成功、
`libLive2DCubismCoreJNI.so` **装载成功**（`nativeloader ... : ok`）、EGL/GLES 初始化成功，
且未观察到属于本 app 的 native 崩溃。但模拟器软件 GL 单帧最长 **25.5 s**
（`EGL_emulation app_time_stats max=25523.69ms`），harness 的 `queueEvent` 20 s 上限必然超时，
**行为级 16 KB 证据未取得**。因此不得记为 16 KB PASS 或 FAIL，按 `ENVIRONMENT PENDING` 处理，
且**不因此卡住 P2B-1 DEV**。完整记录见 `evidence/reports/x86_64-16k-page-size.txt`。

### 冻结边界 1：ARM64 上若出现同一 `GL_INVALID_VALUE`，不得沿用"模拟器驱动怪癖"解释

x86 软件 GL 上的偶发 `0x501` 已按 `SOFTWARE_GL_INTERMITTENT_0x501 / OBSERVATION` 处理，允许不阻塞取证，
但**不得永久忽略**。ARM64 上按此三分支裁定：

| ARM64 观察 | 裁定 |
|---|---|
| 无 GL error | 该风险可基本降为 emulator-specific observation |
| 出现 `0x501`，但无视觉/生命周期异常 | 继续调查，**P2B-0 暂不完全 PASS** |
| `0x501` 关联黑帧 / 缺 mask / 崩溃 / 恢复失败 | **BLOCKER** |

### 后续 Target Gate（拿到硬件时执行，不阻塞开发）

```powershell
D:\AIwatch\evidence\tests\run_cubism_smoke.ps1 -Serial <serial>
```

**不得临时重写 harness。** 重点取证：Core native load/`getVersion`、双模型、pixel output、expression、
physics、GL surface recreation、background/resume、clean release、**以及 GL error 日志**。
不要求把 x86 的四轮 attribution 在 ARM64 完整复制一遍。

### 冻结边界 2：产品禁止照搬官方 Sample 的 Activity 单例

官方 sample 用进程级 static singleton 持有 Activity（`LAppPal`/`LAppDelegate`/`LAppLive2DManager`），
已实测导致同进程内多测试生命周期互相污染（renderer / CubismShader / Delegate 状态残留）。
harness 用"一测试方法一进程"作为**证据手段**可以，**产品层不得靠重启进程解决生命周期**。

产品必须改成显式所有权链，Activity 销毁时明确释放：

```
Activity / View lifecycle
        ↓
CubismRuntimeOwner
        ↓
Renderer instance
        ↓
Model instance
```

明确禁止照搬 `LAppDelegate` 单例持有 Activity、`LAppLive2DManager` 绑定 Activity 的全局单例。

### 冻结边界 3：UTP 不再处理

```
connectedAndroidTest / UTP     INFRASTRUCTURE BROKEN
adb install + am instrument    VALIDATED WORKAROUND
Cubism Runtime Gate            NOT BLOCKED
```

除非将来正式 CI 必须依赖 UTP，否则不修——纯属消耗额度。

---

P2B-RUNTIME 已在**限定范围**内跑通并通过：x86_64 / 4 KB 页 / API 28 模拟器。ARM64 真实手表行仍 NOT RUN；P2B-0 整体未 PASS（ARM64 16 KB RELRO 静态 FAIL 仍是独立 Release Gate）。产品 Home 集成（P2B-1）**未开始**。

## 本次实际进展

1. **冒烟测试跑通了，并且证据强度提高了**。此前唯一阻塞点是 Gradle `connectedAndroidTest` 的 UTP runner 启动即死（`utp.0.log` 仅 267 字节，无 stack trace），与测试代码无关。改用 `adb install` + `am instrument` 后通过。
2. 把「没有 GL 报错」升级为**像素级 + 参数级**证据：新增 `PixelCopy` 取像素、帧间 diff、全参数时间序列采样。见 `LIVE2D_RUNTIME_VALIDATION.md`。
3. 实测页大小 = **4 kB**（此前 PENDING 且明确不假定），native `getVersion()` 实测返回 `06.00.0001`（此前只有文档版本）。
4. 双模型 Haru/Hiyori、真实渲染、眨眼、physics、motion、GL context 重建、clean release 全部取得证据。
5. 第二轮 GitHub 复用调研完成，**更正了一条已冻结的错误许可结论**，见 `REUSE_AUDIT.md` 末节。
6. **集成形态重构已落地**（本轮授权范围内）：验证工程改为「框架作 Gradle library module + 本地 Core AAR」，官方 SDK 未改，重构后重跑运行时 Gate 通过。
7. **逐特性归因已从推断升级为实测**：新增 A/B 对照实验（从 `CubismUpdateScheduler.cubismUpdatableList` 移除目标 updater 再复测）。blink / breath / physics / expression 均实测归因，4 次连续运行复现；expression 与 `.exp3.json` 声明值精确吻合。**pose 判定为 N/A BY ASSET**（官方所有模型的 `pose3.json` 的 `Link` 均为空，pose 按设计即 no-op），wiring 已验证。全过程与两次方法学更正记录在 `LIVE2D_RUNTIME_VALIDATION.md`。
8. **harness 修掉四个缺陷，最终在工作区 AVD 上完整复现通过**（`OK (1 test)` × 2、`smoke verification PASSED`）：整类单进程 flaky（sample 静态单例竞态）→ 改为每方法独立进程；`glGetError()` 断言语义错误 → 加前置 drain 使错误可归因；报告夹带本机账户路径 → 加 `Redact()`；adb daemon 重启导致的设备发现竞态 → 有界重试。
9. **两个必须知道的新情况**：(a) 原设备 A（1600×900 硬件 GL）被我一条 `adb reboot` 误关机且无法恢复，最终复现在 320×640 软件 GL 的工作区 AVD `aiwatch-api28` 上完成；(b) 软件 GL 下间歇出现 `GL_INVALID_VALUE`，像素同时正常，已改为记录项而非硬 Gate 并登记风险。

## 不重复研究

- 运行时证据与范围限制：`LIVE2D_RUNTIME_VALIDATION.md`；可复现脚本 `evidence/tests/run_cubism_smoke.ps1`。
- 复用边界：`PHASE_2B_REUSE_GATE.md`、`REUSE_AUDIT.md`（末节为 2026-09-25 第二轮调研）；Manager 认证：`MANAGER_API_AUTH_AUDIT.md`。
- 官方包路径/哈希：`LIVE2D_ARTIFACT_RECEIPT.md`。Core 只在忽略目录，禁止提交专有制品或模型。
- 二进制审计：`LIVE2D_BINARY_AUDIT.md`；可重跑 `evidence/tests/audit_cubism_binary.py`。

## 本轮最重要的三条复用结论

1. **不要 vendor 整个 Sample**。`CubismSdkForJava-5-r.5/Framework/framework/build.gradle` 本身就是 `com.android.library`，带完整 `rendering/android/` 渲染包。**此项已落地**：验证工程已改为「框架作 Gradle library module + 本地 Core AAR」，见下文与 `LIVE2D_RUNTIME_VALIDATION.md`。
2. **官方没有 Maven/JitPack 制品**（多方核实），所以本地 module 是唯一路径，不存在 `implementation("com.live2d:...")`。
3. **嘴型驱动不要自研框架**。官方 Java sample 的 lipsync 是硬编码 `return 0.0f;` 加一句 TODO，但框架层已提供 `CubismLipSyncUpdater` + `IParameterProvider` 及全套 updater。只需实现一个 `IParameterProvider` 算 PCM 的 RMS 包络（约 50 行），这就是官方源码里指名的做法。

## 集成形态重构（已完成于验证工程）

验证工程 `runtime-smoke` 已从「framework 源码摊平进 app 模块」改为 `:cubism-framework` Gradle library module，重构后重跑运行时 Gate 通过，证据与旧形态等价。官方 SDK 未做任何修改。

**但有一条环境约束必须知道**：官方 `Framework/framework` module **无法在本机直接 include**——它要 `compileSdk 36`（本机只有 android-35）且声明 `java.toolchain = 17`（本机只有 JDK 21），两者都无外网可下载，Gradle 实测报 `No locally installed toolchains match`。所以当前用的是**自己写的薄适配 module**，源码/资源仍指向官方未修改的树。

**给产品的建议**：如果补齐 android-36 平台与 JDK 17，应删掉适配 module、直接 include 官方 module；否则照抄适配 module 的写法。另外别踩这个坑：框架的 GLSL shader 在 `Framework/framework/src/main/assets/.../standardES/`，是**运行时**加载的，library module 只挂 `java.srcDirs` 会编译打包都正常、到设备上才炸。

## 下一次直接做

1. **产品侧采纳已验证的集成形态**：在 `app` 侧按 `evidence/tests/cubism_runtime_smoke/` 的形态引入 Live2D（框架作 Gradle library module + 本地 Core AAR + 自写渲染宿主）。验证工程已证明该形态可编译、可打包、运行时成立；把「验证形态」搬成「产品形态」仍需单独授权（本轮授权只覆盖重构动作本身，未覆盖进 P2B-1）。
2. 取得官方 NDK `llvm-readelf` 复核 ARM64 RELRO。**本环境无外网、无本地 NDK，此项目前无法推进**。
3. **下一件真正的事：找一台普通 ARM64 4 KB Android 手机，用完全相同的 harness 重跑**（不必等 CD12Max）。目标是证明同一份代码 / 同一 Core / 同一 Framework / 同一模型从 x86_64 迁移到 ARM64 仍然成立。命令：
   `evidence/tests/run_cubism_smoke.ps1 -Serial <手机序列号>`。重点重复 expression、physics、GL recreate、clean release（分别覆盖 framework scheduler、Core 参数更新、native/GL 生命周期、资源释放）；A/B 归因不必四项全做四遍。
   注意：harness 依赖反射读取 `CubismUpdateScheduler.cubismUpdatableList` 与 `CubismUserModel` 的 protected 字段，框架版本变化会破坏它；`GL_INVALID_VALUE` 需在真机上一并留意。
4. 处理 `connectedAndroidTest`/UTP 崩溃（工具链缺陷，未修，已成未决项）。有一个**未经证实**的猜想：与 sample 的 `testInstrumentationRunner` / androidTest 依赖配置有关；但冒烟工程自身确实需要 instrumentation 测试，删依赖不是正确解法，需实际诊断。
5. **音频 AEC 风险需尽早原型验证**。新增一条可能更省事的路：官方协议支持**服务端 AEC**（hello 里 `features.aec=true` + 二进制 v2 带 timestamp），若服务端支持则手表不必承担设备侧 AEC。见 `REUSE_AUDIT.md`。
6. 许可合规：确认是否触及 Cubism SDK Release License 的 >1000 万日元商业门槛。
7. 完成上述并复现证据后，才可请求 DEV-ONLY P2B-1 Home 集成。
8. **若需 pose 行为级证据**：另找一个 `pose3.json` 含非空 `Link` 的合法模型覆盖该项；获批的 Haru/Hiyori 不具备该条件，不应为了凑证据而改用未获批资产。
9. 归因实验目前只在 x86_64 模拟器上跑过；若在 ARM 设备上重复，注意 `CubismUpdateScheduler.cubismUpdatableList` 是私有字段，反射路径随框架版本可能变化。

## P2B-1 验收前置条件（先记在这里，避免被后续"清理 Gradle 配置"再次引入）

- **不能把「Framework Java sources compile」等同于「Cubism Framework packaged correctly」。** 官方框架的 GLSL shader 在
  `Framework/framework/src/main/assets/com/live2d/sdk/cubism/framework/shaders/standardES/`，由
  `CubismShaderAndroid` 在**运行时**加载。已实测证明：`Java 源码编译 PASS + AAR 打包 PASS + APK 安装 PASS`
  三者全绿，**仍然不等于** Cubism runtime PASS——只挂 `java.srcDirs` 会编译打包一切正常、到设备上才因缺 shader 崩溃。
- P2B-1 至少要三层断言，缺一层都不算覆盖：

  ```
  APK/AAB package assertion  → 所需 36 个 shader 全部存在
  Runtime assertion          → CubismShader 实际读取成功
  GL recreation              → shader reload / rebuild 成功
  ```

  第 1 层的校验方式可参照本轮 `evidence/reports/cubism-runtime-smoke.txt` 的做法；第 2、3 层必须在真机上跑，
  而不是只验证编译与安装。
- 产品侧禁止照搬官方 sample 的 Activity 单例生命周期，必须改成「冻结边界 2」给出的显式所有权链
  （`Activity/View lifecycle → CubismRuntimeOwner → Renderer instance → Model instance`）。

## Git 边界

只获授权提交文档、报告、审计脚本。现有 Phase2A App 代码与测试工作区改动**保留未提交**，不顺手打包。作者/提交者使用 yunzenn 及 GitHub noreply；禁止用户真名/本机用户目录进入提交内容。无强制推送、无 Core 上传、无新产品功能。

本轮新增/修改的文档与证据（**尚未提交**，等明确指示）：`LIVE2D_RUNTIME_VALIDATION.md`、`REUSE_AUDIT.md`、`HANDOFF.md`、`RISK_REGISTER.md`、`evidence/tests/run_cubism_smoke.ps1`、`evidence/tests/cubism_runtime_smoke/**`、`evidence/reports/cubism-runtime-smoke.txt`、`evidence/reports/cubism-feature-attribution.txt`。

**已知工作区卫生问题**：冒烟工程的源码位于 `third_party/live2d/sdk-r5/`，该路径被 `.gitignore` 整体忽略（因为同一目录含专有 SDK）。因此 `RuntimeSmokeTest.java` 的**工作副本不受版本控制**。本轮已把该 harness 镜像到受版本控制的 `evidence/tests/cubism_runtime_smoke/` 以保全，但根因（harness 与专有制品同处一个被忽略目录）尚未解决，建议后续把 harness 迁到独立路径并用可配置路径引用 SDK。
