# System alarm operator — 2026-10-17

Base: `55aa5a67b74baa14ce36f69588d166bc636f47ca` (merged local-reminder fixes).
Scope: user-driven native device tool, not voice parsing, Agent Planner or server work.
Public `0.4.2-preview` APK and its download links are unchanged.

## User flow and reuse

Settings → 设备操作 → 设置系统闹钟 → platform time picker → Banxuan confirmation
→ `AlarmClock.ACTION_SET_ALARM` with hour/minute and `EXTRA_SKIP_UI=false`.
The page also offers 查看系统闹钟 via `ACTION_SHOW_ALARMS`.
Both actions check the actual available handler; unsupported controls are disabled with an explanation.

Reuses existing ProductUi, typed DeviceAction policy, capability adapter, confirmation dialog,
bounded in-memory audit and Android's Clock app. No new module/library, custom alarm database,
alarm scheduler/player, accessibility service, exact-alarm permission or second audio system.
The existing normal `com.android.alarm.permission.SET_ALARM` permission is reused;
the manifest adds only the two package-visibility queries for Android 11+.

Official API reference (consulted 2026-10-17):
https://developer.android.com/reference/android/provider/AlarmClock

`ACTION_SET_ALARM` can create or activate an existing matching alarm. `EXTRA_SKIP_UI=false`
requests UI but does NOT promise another provider confirmation. Banxuan's consent happens BEFORE
dispatch and warns that confirmation may immediately create/enable the alarm.
The clock owns the actual occurrence, repetition, timezone, sound, editing and deletion.
No implicit repeating days, message or ringtone extras are supplied.

This differs from the existing local notification reminders: it delegates to the user's Clock,
does not store the request and cannot prove creation or ringing. No success notification is invented.

## Result semantics

- Valid times: 00:00–23:59; invalid input does not reach the platform adapter.
- HANDOFF means `startActivity` returned, not alarm creation/firing verified.
- Missing handler → UNAVAILABLE; security rejection → DENIED.
- Ambiguous dispatch exception → UNKNOWN; instruct the user to inspect the target first, no retry.
  This shared handoff rule also protects the existing timer/app-launch paths.
- Viewing the alarm list is read-only; no mutation confirmation is needed for that navigation.
- Audit remains at most 20 records for this page instance, memory-only, not uploaded.
- Cancel either the picker or the Banxuan confirmation → no alarm dispatch.

## Executed software checks

Local environment: Windows, JDK 21.0.8 (Java target/toolchain configuration unchanged), Gradle 8.9;
independent Android SDK emulator `emulator-5556`, API 28, x86_64, `-no-audio`.
Fixture: 410×502 px @320dpi = 205×251dp, fontScale 1.0.
No Lenovo emulator or physical-device evidence is claimed.

Build commands (existing local toolchain; no SDK/license installation):

```powershell
$env:JAVA_HOME='D:\JDK'
$env:GRADLE_USER_HOME='D:\AIwatch\.tools\gradle-home'
.tools/gradle-8.9/bin/gradle.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
./evidence/tests/run_product_suite.ps1 -Serial emulator-5556 -Log evidence/tests/SYSTEM_ALARM_PRODUCT.log
```

| Gate | Local result |
|---|---|
| App + Android test APK build | PASS |
| App JVM tests | 59/59; 0 failures/errors/skipped (10 operator policy tests) |
| Lint | 0 errors, 31 warnings |
| Whole-run product instrumentation | 60/60 in one invocation; no reset/skip (10 operator tests) |

New Android coverage: intent time bounds/extras, cancel picker and confirmation, consent before
single dispatch/HANDOFF feedback, SHOW_ALARMS dispatch without SET_ALARM.
Mutation tests BLOCK the Clock receiver with Android Instrumentation ActivityMonitor: they do not
create a real alarm, and their HANDOFF result is dispatch/consent evidence only.
The SHOW_ALARMS test is also intercepted; it is not evidence of third-party Clock UI rendering.

Actual package resolution separately returned:

```text
SET_ALARM:  com.google.android.deskclock/com.android.deskclock.HandleSetAlarmApiCalls
SHOW_ALARMS: com.google.android.deskclock/com.android.deskclock.HandleApiCalls
```

Raw final logs: [build](tests/SYSTEM_ALARM_BUILD.log), [whole-run product](tests/SYSTEM_ALARM_PRODUCT.log).
Existing tests retained; canonical runner still rejects zero tests, failures and skips.

### Repeat-run finding

Initial run passed 60/60 (76.041s), but the next run was **59/60**: the new confirmation
test could not locate its positive button within five seconds. Preserved raw failure:
[SYSTEM_ALARM_PRODUCT_RED.log](tests/SYSTEM_ALARM_PRODUCT_RED.log).
Adding diagnostics alone produced 60/60 (58.676s), so that green run was NOT treated as a fix.
The original selector only searched the active accessibility root after a TimePicker-to-confirmation
window transition. Constraining it to the confirmation title alone still failed (59/60): the new
window snapshot showed clickable `CANCEL`/`OK`, while the exact source-resource label match differed
in case. Preserved [window diagnostic failure](tests/SYSTEM_ALARM_WINDOW_RED.log).
The final selector locates Banxuan's confirmation title across interactive windows and uses Android's
standard positive/negative button IDs, independent of display capitalization/localization. It checks
clickable/enabled and restores the automation service flags in `finally`.
The first failure had no full window snapshot, so its exact cause is not separately claimed proven.
Product consent semantics and assertions remain unchanged; no deadline increase, test deletion,
isolated-method run or reset was used.

After the button-ID fix, two consecutive complete runs passed **60/60** (27.016s, 26.155s),
without reinstall/reset between these two runs. Raw [repeat run](tests/SYSTEM_ALARM_REPEAT.log).
The diagnostic-only intermediate green log is retained as [SYSTEM_ALARM_DIAGNOSTIC.log](tests/SYSTEM_ALARM_DIAGNOSTIC.log),
not misrepresented as proof of the fix. Hosted CI is recorded separately from these local runs.

## Still pending / not claimed

Actual provider alarm creation/activation, duplicate matching, occurrence/date/repeat behavior,
ringing at the selected time, OEM behavior, Android 11+ query runtime, physical phone and
CD12Max validation. No cloud integration, autonomous device control or G3 completion claim.
No release, stable signing, package rename, server deployment or proprietary assets changed.
