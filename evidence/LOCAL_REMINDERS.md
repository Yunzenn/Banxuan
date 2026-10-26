# Local reminders — 2026-10-16

Scope: user-facing Settings → 本地提醒. Input up to 160 characters, select date/time within the next year,
confirm creation, list status, cancel and withdraw a notification. No server, Voice/Memory integration,
automatic Agent decisions, Accessibility, foreground service or wake-lock loop.
Public 0.4.2-preview APK remains unchanged. This is a source feature pending review.

## Reuse / implementation

- Official [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms):
  AlarmManager.setAndAllowWhileIdle + explicit immutable PendingIntent. Inexact, not an exact alarm or safety timer.
- Official [notification permissions](https://developer.android.com/develop/ui/views/notifications/notification-permission):
  POST_NOTIFICATIONS on API33+, application and channel availability checked at creation and posting.
- Existing ProductUi / Native Views / AtomicFile pattern reused. No new dependency, module, queue, scheduler engine,
  database, permission workaround or copied third-party code. Standard Android API usage, not proprietary source reuse.
- Pending IDs recovered on app launch, reminder-page resume, BOOT_COMPLETED, MY_PACKAGE_REPLACED and TIME_SET.
  This is recovery logic, not evidence every OEM delivers those events.

## Contracts / privacy

One process-owned Reminders in ProbeApplication; synchronized durable mutations. Persist PENDING before scheduling;
persist CANCELLED before removing the alarm. Late/duplicate broadcasts cannot replay terminal records.
Persist POSTING before notification side effect; process death here is an uncertain result, never blind re-post.
POSTED only means NotificationManager accepted the call, not sound, user visibility/read or acoustic delivery.
FAILED / BLOCKED / uncertain POSTING are visible and do not auto-retry. Recreate manually if appropriate.
At most 32 pending and bounded terminal history (at most 64 total); no pending work evicted for history.
Corrupt/oversized storage fails closed, never silently resets. Bounded receiver IO, no reminder content in logs.

Text is currently plaintext in noBackupFilesDir, under application sandbox; **not application-layer encrypted**.
No server upload or backup. UI warns against sensitive content; lockscreen uses PRIVATE + generic public version.
Do not claim root/physical-extraction protection. Force-stop can prevent alarms until user reopens the app;
system/OEM power policies can delay inexact delivery. This is not an urgent/medical reminder facility.

## Validation

Initial implementation at 4a87066: local software validation **PASS** (before review fixes).
Hosted run 38885812940 also passed. Review subsequently found three uncovered branches below.

- Final assembleDebug / assembleDebugAndroidTest / app JVM tests / lintDebug: BUILD SUCCESSFUL (1m11s).
- App JVM **55/55**, failures/errors/skipped = 0; includes 14 new reminder policy tests.
- Lint 0 errors / 31 warnings (existing warnings retained, not suppressed).
- Independent SDK API28 x86_64 `emulator-5556`, 410x502px @320dpi / fontScale1.0 / no-audio:
  canonical whole-run **53/53**, 65.206s, no failures/skips. Existing 46 retained + 7 new cases.
- Real AlarmManager → manifest receiver → NotificationManager notification tested after finishing the page
  and opening the system Home launcher; actual notification content + private visibility + POSTED state checked.
  Instrumentation process stays alive; this is **not** a process-death or acoustic delivery test.
- UI test enters text, cancels first confirmation (no record created), confirms next, verifies persisted text.
  Storage reopen/corruption/size bound and cancellation with late delivery covered.
- Final logs: `tests/LOCAL_REMINDERS_BUILD_FINAL.log`, `tests/LOCAL_REMINDERS_FINAL.log`.
  Earlier local green iterations preserved separately; not substituted for final-source validation.

```powershell
# JAVA_HOME=D:\JDK; GRADLE_USER_HOME=D:\AIwatch\.tools\gradle-home
.tools/gradle-8.9/bin/gradle.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pkotlin.compiler.execution.strategy=in-process --console=plain
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
.android-sdk/platform-tools/adb.exe -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
./evidence/tests/run_product_suite.ps1 -Serial emulator-5556 -Log evidence/tests/LOCAL_REMINDERS_FINAL.log
```

No hardware, API33 permission runtime, real reboot, Doze timing,
process-kill or cross-signature upgrade claim. Existing product suite remains intact; new reminder cases
are added to the same whole-run runner, no per-method process resets or skipped-test PASS.

## PR #43 review fixes

Three regressions reproduced before changing production semantics:

1. Date confirmed then time dialog cancelled: selected date changed by one day while label remained old.
   New native-dialog test fails the previous implementation; cancelling the date dialog is a separate guard.
2. Existing cancellation dialog confirmed while repository refresh is busy: status stayed PENDING.
   New test holds the real repository monitor with latches, triggers Activity pause/resume refresh,
   confirms cancellation, releases the refresh, then requires CANCELLED (no process reset).
3. Android post accepted but POSTED persistence fails: previous catch block overwrote status with FAILED.
   Fault-injection unit test requires durable POSTING and exactly one dispatch even after reopen/restore.

RED: focused JVM 1/1 failed as expected; reminder instrumentation 10 executed / 2 failures (25.598s).
Logs: `tests/LOCAL_REMINDERS_REVIEW_RED_BUILD.log`, `tests/LOCAL_REMINDERS_REVIEW_RED_ANDROID.log`.
The RED fixture only exposes the existing native date dialog internally; business behaviour was unchanged.

Fixes: date/time uses an isolated Calendar draft and commits only on time confirmation; UI operations
serialize through the existing coroutine Mutex, disabling new mutations while retaining already-confirmed
actions; dispatch exceptions keep POSTING uncertain, and POSTED commit errors propagate without a FAILED rewrite.
The previous post-exception test still asserts one dispatch/no retry, now requiring honest uncertainty instead
of claiming definite failure. No test removed or skipped; no new framework/dependency introduced.

Review-fix local verification **PASS**: assembleDebug + assembleDebugAndroidTest + full app JVM + lintDebug,
BUILD SUCCESSFUL (52s); **56/56 JVM**, failure/error/skipped = 0; lint 0 errors / 31 existing warnings.
Canonical API28 emulator whole-run **56/56**, 32.182s, no failures/skips. The 3 new Android tests and
1 new JVM test are retained alongside the original cases; uncertain dispatch also remains one-call/no-retry.
GREEN logs: `tests/LOCAL_REMINDERS_REVIEW_GREEN_BUILD.log`, `tests/LOCAL_REMINDERS_REVIEW_GREEN_ANDROID.log`.
Hosted review-fix CI pending at submission. Hardware/API33/Doze/reboot boundaries remain unchanged.

## Notification snooze action — 2026-10-26

Each successfully posted reminder now offers **10 分钟后提醒**. The action is handled by the existing
explicit `ReminderReceiver` and AlarmManager path; it is accepted only from durable `POSTED`, writes the
new `PENDING` due time before re-arming the same reminder identity, then dismisses the current notification.
Repeated action delivery cannot move the due time again. Pending capacity remains capped at 32; an uncertain
or failed scheduling operation is not blindly retried. No exact-alarm permission, service, polling loop,
new dependency, backend, or autonomous Agent behavior was added.

Local validation: Debug APK + AndroidTest APK compile, `lintDebug`, and app JVM suite pass; **64/64 JVM**
tests with 0 failures/errors/skips. The new Android instrumentation case verifies the real notification
action, same-ID re-arm, dismissal, and snooze-action PendingIntent cleanup on API 28; JVM tests cover
duplicate delivery and schedule failure. Hosted CI first reported one timeout in the existing
`MemoryTrustTest.savingWithoutChangingAnythingPreservesTheOriginalProvenance`; the snooze instrumentation
case passed in that run. The complete API28 emulator job then passed on rerun (**64/64**, no skips), and
fresh-clone CI passed. No local emulator is available in this environment. This is emulator evidence, not
phone/OEM/Doze or acoustic-delivery certification.
