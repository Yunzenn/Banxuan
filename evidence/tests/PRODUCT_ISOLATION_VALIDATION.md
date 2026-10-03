# Product instrumentation ownership — 2026-10-03

Status: **PASS / SOFTWARE EMULATOR**. Physical phone / CD12Max: **PENDING**.
No emulator CI job is added by this PR; that is the next independently scoped task.

## What the baseline actually showed

The previously installed Debug/test APKs passed a whole MemoryTrust class (28/28), and
IdentityProcessTest followed by MemoryTrustTest in one invocation also passed (29/29).
See `PRODUCT_ISOLATION_BEFORE.log` and `PRODUCT_ISOLATION_WARM_BEFORE.log`.
The historical assertion that process-per-method was mandatory was **not reproduced** on this run.
These baseline APKs were installed before the fixture changes; their exact binary hashes were not
archived, so these observations are not claimed as attestation of an exact published commit.
No fabricated RED-to-GREEN claim is made for this PR.

There was nonetheless an ownership defect in the test design: the corruption test rewrote
`noBackupFilesDir/device-identity.bin`, while the Application owns a persistent DataStore over that file.
The tests also depended on ambient process state and the runner described process resets as mandatory.

## Ownership change

`ProductTestRunner` installs `ProductTestApplication` only from androidTest. Each MemoryTrust method
starts an identity fixture: unique file, fresh production DeviceIdentityStore, and explicit coroutine owner.
The corruption test damages that file before the Store's first read. It still asserts the real screen
shows identity unavailable and the gateway is never read. It does not use an exception-only fake identity.

Teardown clears the registry, waits for the Activity work to finish, clears only the fixture subject's
synthetic cache rows when present, cancels/joins the Store owner, and deletes the owned temporary directory.
Existing Activity closure and test-local ProductStore/import files remain under their own try/finally.

The only production source change opens the Application class and its existing identity property to
the test subclass. Production still uses one `by lazy` Store and the same no-backup file; the Store
implementation/serializer/reset semantics, Room implementation and product UI are unchanged.
The test runner has no production reload flag or alternate authentication/subject path.

## Actual selected product gate

Environment: SDK emulator `emulator-5554`, API 28, x86_64, `-no-audio`,
410x502px / 320dpi (205x251dp), fontScale 1.0. No Lenovo or physical device used.
Existing window/density/font overrides were recorded and not changed.

```powershell
# Local build environment: JAVA_HOME=D:\JDK; GRADLE_USER_HOME=D:\AIwatch\.tools\gradle-home
.tools/gradle-8.9/bin/gradle.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:testDebugUnitTest :app:lintDebug -Pkotlin.compiler.execution.strategy=in-process --console=plain
.android-sdk/platform-tools/adb.exe -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
.android-sdk/platform-tools/adb.exe -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
pwsh -NoProfile -File evidence/tests/run_product_suite.ps1 -Serial emulator-5554 `
  -Log evidence/tests/PRODUCT_ISOLATION_SUITE_FINAL.log
```

The script makes **one** `am instrument -w -r` call, selecting these existing classes:

| Class | Executed |
|---|---:|
| IdentityProcessTest (warms persistent identity first) | 1 |
| MemoryTrustTest | 28 |
| CompanionHomeTest | 4 |
| ProductShellTest | 4 |
| DaylightUiTest | 2 |
| AdaptiveWindowTest (current compact fixture) | 1 |
| Total | **40/40** |

Final raw output: `PRODUCT_ISOLATION_SUITE_FINAL.log`: OK (40 tests), 25.04s,
40 successful status completions; failure/error/norun/ignore/assumption-skip = 0.
The earlier fixture run also passed 40/40, then fixture cache-row cleanup was added and the final
build and suite were rerun. Standalone Memory class: 28/28 (`PRODUCT_ISOLATION_MEMORY_AFTER.log`).

The runner checks the execution count against selected source annotations, and refuses skipped or
zero-match runs. No per-method process reset, uninstall, clear-data or weakened layout assertion.
This is a selected product suite, not all app instrumentation; audio soak/hardware, Cubism smoke,
live server and the eight-cell matrix are outside this run. The existing matrix evidence is unchanged.

Build: **PASS**. App JVM: **34/34**, failure/error/skipped 0. Debug lint: 0 errors / 31 warnings.
Final build raw output: `PRODUCT_ISOLATION_FINAL_BUILD.log`.

Final installed local APK SHA256:

```text
app-debug.apk             11eb16190845ce9fe6a6b76e732bffe5aca97a153b760b7e499c0922d53f3354
app-debug-androidTest.apk  1acb3c31a4783e96fbdb50c208160c08c9a9e8f11c772cc16040ca377ffda28d
```

These are local test artifacts, not a new public Release or an upgrade-compatibility claim.
The diagnostic per-method runner is retained with a configurable runner name; Cubism may still use it.
CONTRIBUTING identifies the whole-run product path as canonical.

Separate identity continuity check:

```powershell
pwsh -NoProfile -File scripts/verify-identity-process.ps1 -Serial emulator-5554
```

**PASS**: two distinct Android processes returned the same persistent device/client identity,
without reset/clear-data. Raw output: `PRODUCT_ISOLATION_IDENTITY_CONTINUITY.log` (identifiers redacted).
Its explicit force-stop proves this separate persistence check; it is not used between product methods.
