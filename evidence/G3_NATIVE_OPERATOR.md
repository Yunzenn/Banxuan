# G3 Native Device Operator — 2026-10-09

Native policy/user-surface: **PASS / SOFTWARE EMULATOR**. Accessibility: PENDING separate spike.
This is not full G3 certification; timer completion, arbitrary third-party apps and hardware remain PENDING.

User entry: Home → Settings → 设备操作. New source build only, not public 0.4.1 APK.
No server, microphone, accessibility service or autonomous planner required.

Three typed actions: SetMediaVolume(level), LaunchApp(packageName), SetTimer(seconds).
The UI shows a confirmation dialog for each. Volume is verified against Android readback;
fixed-volume devices disable it. Apps are chosen from launcher-resolvable packages, no unrestricted
package inventory permission. Timer uses system Clock UI with EXTRA_SKIP_UI=false and 1..86400s.
Missing handler/permission denial/invalid input fail explicitly. No automatic retries.

Intent dispatch returns HANDOFF, never claims target activity or timer completion.
Audit records include typed action, timestamp and status; bounded 20-entry in-memory history
belongs to this page. No upload/durable personal activity log; recreation clears it.

Reuse: official platform API calls, informed by the three frozen droid-mcp implementations,
reference-only (no MCP runtime/dependency or copied source); REUSE_AUDIT records precise paths.
Existing Daylight ProductUi reused, no framework migration or adaptive generalization.

Validation: app build PASS; JVM **41/41** (7 new policy tests), zero failure/error/skipped;
lint **0 errors**. Local SDK API28 x86_64 emulator-5556, 410x502px/320dpi/fontScale1.0,
no audio: selected whole-run suite **46/46**, 93.704s.
[Hosted run 37935718852](https://github.com/Yunzenn/Banxuan/actions/runs/37935718852)
at `e661560`: fresh-clone PASS, product-emulator **46/46**, 25.765s, no skipped tests.
Existing 40 tests retained; 6 new Android tests cover user confirm/cancel, actual media volume
set/readback (original restored), real own-launcher Activity handoff, absent package, malformed timer,
and timer Intent duration/visible UI. UiAutomation in the test is not an AccessibilityService spike.

Commands (repo root):

```powershell
# JAVA_HOME=D:\JDK; GRADLE_USER_HOME=D:\AIwatch\.tools\gradle-home
.tools/gradle-8.9/bin/gradle.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pkotlin.compiler.execution.strategy=in-process --console=plain
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
./evidence/tests/run_product_suite.ps1 -Serial emulator-5556 -Log evidence/tests/G3_NATIVE_PRODUCT_FINAL.log
```

Raw logs: `tests/G3_NATIVE_BUILD_FINAL.log`, `tests/G3_NATIVE_PRODUCT_FINAL.log`,
`tests/G3_NATIVE_HOSTED_PRODUCT.log`. No uninstall/clear-data on the existing emulator.
Fresh emulator Clock resolves to `com.google.android.deskclock/com.android.deskclock.HandleSetAlarmApiCalls`;
that capability check is not timer completion proof.
Physical phone/CD12Max, real timer provider execution, arbitrary app launch foreground verification,
voice Agent integration, Accessibility and background proactive notifications are not claimed PASS.
Follow-up: separate Accessibility observe→one safe action→observe spike, then local scheduler spike.

Initial local install onto emulator-5554 was rejected due to different Debug certificate. The shell
mistakenly continued into instrumentation on the stale installed APK; that run FAILED and is not
evidence for this change (`G3_NATIVE_STALE_APK.log`). No uninstall or clear-data was performed.
Fresh dedicated `banxuan-operator-api28` / emulator-5556 created instead; installs are now fail-fast.
