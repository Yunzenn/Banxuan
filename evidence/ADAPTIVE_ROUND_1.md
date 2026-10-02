# Adaptive Round 1 — 2026-10-02

## Scope and baseline

Base: merged Daylight `2b4239203fc48bc39df0128dde820fb6d3a46e03`.
Branch: `codex/adaptive-daylight`. Native Views, minSdk 28, existing design and business semantics retained.
No model-name branching, Compose, new dependencies, release workflow, signing change or updater.

`CompanionDimensions` contains fixed tokens; `CompanionLayoutSpec` takes measured inset-free Home
width/height. Home caps content at 480dp and PTT at 320dp. Compact transcript uses 48–64dp, reserving
48dp header, 48dp PTT, 14dp gaps and the existing 60dp stage where the tested window permits.
Larger windows use 128–240dp transcript and 8 messages versus compact's 4. Stage uses remaining space.
Fonts remain sp; long PTT names ellipsize instead of shrinking. Bubble width uses its actual container.
System-managed non-edge-to-edge content excludes visible bars. Compact immersive policy uses measured
decor window height, not device identity; unrelated Daylight system-icon flags are preserved.
Settings retains its existing 560dp maximum width. Voice/Memory/Live2D/Operator logic unchanged.

## Executed matrix — SOFTWARE AUTOMATED PASS

SDK emulator `emulator-5554`, API 28. No Lenovo emulator or physical device evidence.

| Logical display | Physical test pixels / density | fontScale 1.0 | fontScale 1.3 |
|---|---|---|---|
| 205×251dp | 410×502 / 320dpi | PASS | PASS |
| 240×320dp | 480×640 / 320dpi | PASS | PASS |
| 360×640dp | 360×640 / 160dpi | PASS | PASS |
| 411×891dp | 411×891 / 160dpi | PASS | PASS |

Final evidence: [ADAPTIVE_MATRIX_VERIFIED.log](tests/ADAPTIVE_MATRIX_VERIFIED.log).
Every cell actually executes one instrumentation test (zero tests is failure): real pointer PTT
press/release and scripted state transitions, transcript, stage minimum, non-overlapping visible text,
PTT/Settings/transcript inside system visible frame, no control clipping, unchanged sp scaling,
Daylight status icon flag, Activity recreation to usable IDLE Home, Settings → Memory navigation.
Expected display width AND decor height are asserted, not inferred from requested emulator settings.
Additional four-side host padding tests layout recomputation; it is not a physical cutout simulation.

Actual Home sizes are recorded in the log: compact startup can be 205×227dp before immersion,
then 205×251dp; 240×296dp, 360×616dp, 411×867dp with this AVD's visible status bar.
No keyboard/input matrix, rotation matrix, hardware navigation-bar variants, full visual sign-off,
real phone, CD12Max, audio or real server certification is claimed. Memory mutation regression is
separate at the compact fixture, not asserted across all eight cells.

## Failures retained, not hidden

1. [First matrix](tests/ADAPTIVE_MATRIX_FIRST.log): 4/8. At 320dpi the AVD clamped requested phone
   pixels to 640×1280, so tests correctly rejected 320dp width instead of 360/411dp. Runner uses 160dpi
   for phone fixtures, retaining exact requested logical dimensions and 320dpi permanent compact fixture.
2. [Intermediate matrix](tests/ADAPTIVE_MATRIX_FINAL.log): 8/8 before final code changes; NOT final provenance.
3. [Daylight regression](tests/ADAPTIVE_DAYLIGHT.log): 1/2, existing stage >=60dp assertion failed while
   startup system bars reduced space. Fixed runtime transcript budget, not the assertion or font sizes.
4. [Daylight recheck](tests/ADAPTIVE_DAYLIGHT_RECHECK.log): 2/2; Home regression
   [ADAPTIVE_HOME.log](tests/ADAPTIVE_HOME.log): 4/4. Final matrix above includes strengthened stage,
   height and system-icon assertions and passes all eight again.

## Build and reproduction

```powershell
$env:JAVA_HOME='D:\JDK'
$env:GRADLE_USER_HOME='D:\AIwatch\.tools\gradle-home'
.tools/gradle-8.9/bin/gradle.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug '-Pkotlin.compiler.execution.strategy=in-process'
.android-sdk/platform-tools/adb.exe -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
.android-sdk/platform-tools/adb.exe -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& evidence/tests/run_adaptive_matrix.ps1 -Log evidence/tests/ADAPTIVE_MATRIX_VERIFIED.log
```

Build successful; app JVM **34/34**, failures/errors/skipped 0; lint **0 errors, 31 warnings**.
Instrumentation: **46/46 distinct cases/cells in final/recheck evidence**, not a first-attempt claim:
matrix 8, Daylight 2, Home 4, [Product](tests/ADAPTIVE_PRODUCT.log) 4,
[MemoryTrust](tests/ADAPTIVE_MEMORY.log) 28. Regression fixtures use restored 205×251dp / fontScale 1.0.
App APK SHA-256: `0B0FCF17FCDA1CCD1BD0CEF553E6D9D1338ED4F0ADD6A413F7DDD30285DAFC4A`.
Local APK: `app/build/outputs/apk/debug/app-debug.apk`. Paired androidTest APK rebuilt and installed.
Both installed with `-r`; no uninstall/data-clear used. This is NOT cross-signature migration evidence.
Runner restores original window/density/font settings in `finally`.

The public `v0.4.0-preview` APK is unchanged. This local Debug APK is not a new published Preview.
Stop UI generalization after this round. Next is a separate release-infrastructure task, then lightweight
About/update check and Connected Voice; no release credentials or cloud configuration changed here.
