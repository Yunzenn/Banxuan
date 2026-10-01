# Local Live2D model preview — 2026-10-01

Scope: user-requested Mahiro model in the existing daylight Home. Local development only;
not a new public Release, generic model importer, target-device certification, or voice integration.

## Reuse, not a new engine

Following the `github-research` workflow, inspected actual frozen source rather than only READMEs:

| Source | Frozen revision / files | Use |
|---|---|---|
| [Official Java Samples](https://github.com/Live2D/CubismJavaSamples) | `8ce6803de7030a4816bccd8a3efcad69ea1d1186`; `Sample/src/full/java/com/live2d/demo/full/{LAppModel,LAppTextureManager,LAppDelegate}.java` | Model update ordering, texture mipmap generation, EGL shader lifetime |
| [Official Framework](https://github.com/Live2D/CubismJavaFramework) | `ed15cb21a466893381d1dbddce0da943c7fe9a0f`; `CubismModelSettingJson`, `CubismEyeBlinkUpdater`, `CubismPhysicsUpdater`, `CubismUpdateScheduler`, `CubismShaderAndroid.setUpTexture` | Compile unchanged local SDK sources; directly use official parser/updaters/renderer |
| [Wanyu Android](https://github.com/JieRobot/wanyu-ai-android) | `f873e137e224192fbac72021536054ed4a5ad044`; `wanyu-android/app/src/main/java/com/wanyu/ai/live2d/render/{Live2DView,Live2DRenderer}.kt` | Reference bounded texture decoding and per-view lifecycle semantics; no copied Compose, JNI bridge, EGL loop, or animation plugins |

Existing `core-live2d` remains the adapter. No second model3 parser, motion engine, physics engine,
audio player, Compose migration, or third-party Core binary. Official source retains vendor headers
in the local SDK; Wanyu is a design reference here, not a source copy.

## Model and packaging boundaries

User supplied `Mahiro_GG`; no license file was found. Possession is not redistribution permission.
The model, screenshots, APK and proprietary Core remain local/ignored, not GitHub assets.

| Input | SHA-256 |
|---|---|
| `Mahiro_V1.model3.json` | `158859382a7c2b5249e78c0168003f6c03040eb46d2088c1a5ed16c2e5030e9b` |
| `Mahiro_V1.moc3` | `2915521678d0c027f9380b666f7307da4e275a4ad7d00d20236d8d93eb806f14` |
| `Mahiro_V1.physics3.json` | `5c9e83aeb744f3105811cbff2d52b72a25e69d6e5876bb9b24b3a043a9ed0df4` |
| `texture_00.png` | `64c9e6b4d2be82c1bf382eb33bd14194862b2457f82916ec1a88b114a4b81174` |
| Official R5 Core AAR | `3f05da57ab855e803000e6353888dd561c47758598c6c0200dcd0109312705f8` |

The model declares physics and two EyeBlink parameters; no motion, expression, or pose files,
and an empty LipSync group. Blink/physics are wired through official updaters; this run does not
claim per-feature attribution tests, voice-driven lip sync, or authored idle motions.

8192×8192 atlas: bounded `BitmapFactory` decode to 2048×2048 (source untouched).
Base texture RGBA drops from 256 MiB to 16 MiB; mip chain requires roughly another third.
This is an allocation estimate, not a device power/performance certification.

`-PlocalLive2d=true -Plive2dModelDir=<directory-containing-model3.json>` selects the local adapter.
The opt-in build is debug-only and uses application ID `com.aiwatch.probe.live2ddev`, label
`Banxuan · Live2D 本地`; it can coexist with the normal Preview without resetting its data.
The source model is staged only into ignored build output. Official sample models moved to
`core-live2d`'s androidTest assets, not its main assets.

APK archive inspection:

| APK | Core native files | Local model files | Haru/Hiyori/Mao sample files |
|---|---:|---:|---:|
| Local model preview | 3 | 4 | 0 |
| Default public build, after switching back from local build | 0 | 0 | 0 |

## Black-frame root cause and correction

The previous adapter uploaded only texture level 0 with `GL_LINEAR` filtering. R5's
`CubismShaderAndroid.setUpTexture()` changes that to `GL_LINEAR_MIPMAP_LINEAR` on every draw.
Without mip levels, the texture is incomplete: valid GL IDs, valid bindings, visible drawables,
and no GL error did **not** imply visible output.

Reused the official Sample's `glGenerateMipmap(GL_TEXTURE_2D)` immediately after upload.
Before: `GLREADPIXELS distinct=1 nonBackground=0`. After: `distinct=4249 nonBackground=7483`
on the same 370×138 initial surface, `glError=0x0`; direct visual inspection confirmed the model.
The final local Home uses an opaque colour matching the light theme, avoiding this emulator's
black transparent-SurfaceView composition. No native/Core binary was modified.

Two mistakes introduced during this work were fixed and are not counted as passes:

- Shader invalidation initially called `getInstance()` before Framework startup, causing a GL-thread
  NPE. The cold-start path now leaves shader initialization until Framework is ready.
- First new test run failed in a nested `runOnMainSync` helper before its pixel assertion. The helper
  now executes directly if already on the main thread. This was **not** a mipmap regression-test RED.

## Verification

Device: **Android SDK emulator `emulator-5554`, API 28 / x86_64**, 410×502 override at 320 dpi.
Not the Lenovo instance. No CD12Max, ARM64 phone, 16 KB, audio, or real-server claims.

- Local `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: successful.
- `LocalLive2dTest`: **2/2 passed**, final rerun 44.089 seconds (earlier run 43.703 seconds).
  Actual SurfaceView PixelCopy, not fallback artwork:
  370×186 / 6072 distinct colours before and after pause/resume recreation; runtime released on finish.
  Missing model removes Live2D and restores a visible static image.
- Lifecycle test invokes Activity pause/resume hooks. Separate manual HOME/task-return retained PID
  14376 and produced model pixels again (`distinct=3967 nonBackground=7459`, no GL error).
- Cold shader setup is slow on this emulator (approximately 15 seconds in the observed resume run).
  This is a local integration result, **not production performance PASS**. FPS/power/10-minute soak
  and detailed visual polish remain pending; the full-model fit is small on the watch-sized stage.
- Default build + unit tests + lint: successful; **32/32 JVM tests**, zero failed/error/skipped;
  default lint **0 errors / 32 warnings**. Existing daylight/Home/product UI instrumentation
  **10/10 PASS**, 58.556 seconds; no test removal or weakened layout assertions.

Raw local-model test output: [LOCAL_LIVE2D_INSTRUMENTATION.log](tests/LOCAL_LIVE2D_INSTRUMENTATION.log).
Default UI regression: [LOCAL_LIVE2D_PUBLIC_UI_REGRESSION.log](tests/LOCAL_LIVE2D_PUBLIC_UI_REGRESSION.log).

Reproduction (using the existing Gradle/JDK/SDK environment):

```text
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PlocalLive2d=true -Plive2dModelDir=<local model directory>
adb -s emulator-5554 install -r <local debug APK>
adb -s emulator-5554 install -r <local androidTest APK>
adb -s emulator-5554 shell am instrument -w -e class com.aiwatch.probe.LocalLive2dTest com.aiwatch.probe.live2ddev.test/androidx.test.runner.AndroidJUnitRunner
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Local preview copy: `.tools/local-live2d-preview/banxuan-mahiro-local.apk`.
SHA-256: `5a625db1285ae03577765963889d238327f2ce91fa01ba6b63fd3cc143329dab`.
Public release `v0.4.0-preview` remains unchanged. Expandable Application licensing and the
ARM64 16 KB release blocker remain open. Do not upgrade historical full-runtime gates based on this
single-model local preview; motion/expression/pose and a second model were not exercised here.
