# W3 — cache-first trust UI validation

Date: 2026-09-30. Base: `8bec9e1`. Local changes, not pushed.

## Changes

- Collect the existing repository `snapshots()` on IO and render on Main.
- CACHED means refresh in flight, not offline. STALE explicitly labels old content.
- FRESH displays the last full sync time in the device time zone.
- NEVER_SYNCED does not present an empty memory list; filtering remains disabled.
- Freshness has its own view, separate from mutation feedback and editor errors.
- Cached-first refresh remains busy; edit/delete entry guards prevent acting during refresh.
- Failed mutation reconciliation updates freshness without promoting cached records into a
  confirmed mutation result. Existing no-blind-retry/no-success-re-list logic is unchanged.
- Correct the obsolete text claiming the device never stores memory: it now explains that
  disconnected state does not display cache or fabricated data.

## Validation

Using the workspace Gradle 8.9, JDK at `D:/JDK`, and existing workspace Gradle cache:

```powershell
gradle --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

Result: BUILD SUCCESSFUL. MemoryTrustRepositoryTest: 28 tests, zero failures/errors/skipped.
Initial Kotlin daemon startup failed and compilation fell back successfully; the final build
explicitly used in-process compilation. One retry used an unquoted PowerShell property and failed
argument parsing; the quoted command above passed.

Added three instrumentation methods to MemoryTrustTest:

- cachedFirstFrameStaysLabelledWhenRefreshFails
- neverSyncedIsNotAnEmptyMemoryList
- successfulRefreshReplacesCachedFrameAndItsBanner

Device: `emulator-5554`. Installing the debug APK returned
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the existing package's signing certificate differs.
Initially no uninstall or app-data clearing was performed. The user subsequently authorized
replacing the SDK emulator app, explicitly excluding the Lenovo emulator. The target was verified
as `emulator-5554`, model `Android SDK built for x86_64`, qemu=1, API 28,
410x502 override at 320dpi. Only `com.aiwatch.probe` and its test package on this device were
uninstalled; this cleared the old app identity/data. Both new APKs installed successfully.

First instrumentation run: 23 PASS, 2 FAIL, 0 NORUN. The new multi-line sync metadata above the
list pushed the first card and editor field below the fold (y=519 and y=537 on a 502px screen).
Following targeted root-cause analysis, FRESH metadata became a footer; CACHED/STALE warnings
remain above the content. No layout assertions were weakened or removed.

Rebuilt both APKs and reran the 28 JVM tests successfully. Full per-method instrumentation rerun:
**25 PASS, 0 FAIL, 0 NORUN** including the three new W3 tests and both original layout guards.
The runner requires `OK (1 test)` per method. Raw final output is `W3_INSTRUMENTATION.log`.

```powershell
./evidence/tests/run_instrumentation.ps1 -Src app/src/androidTest/kotlin/com/aiwatch/probe/memory/MemoryTrustTest.kt -Class com.aiwatch.probe.memory.MemoryTrustTest -TestPkg com.aiwatch.probe.test -Serial emulator-5554 -Log .tools/w3_memory_fixed.log
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Next

W3 automated software validation PASS. Full visual review of cached/stale banners and reference
hardware validation are not claimed. W4 dedicated runtime/structural enforcement remains pending;
passing existing mutation tests alone does not close that broader gate.

No cloud configuration, remote service, real-phone or CD12Max validation performed.
