# W4 — trust boundary regression guards

Base: `6264fd1` (W3 merged). Scope: tests/build configuration only; no new product features.

## Structural guard

`MemoryArchitectureTest` uses pinned test-only ASM 9.6 (BSD-3-Clause) to inspect production
Kotlin class files, including coroutine/lambda classes. It fails if class files are absent.
It enforces one external repository constructor call, at Activity.repository(), and rejects
gateway calls outside the repository or direct HTTP/protocol transport calls from the trust UI.
DeviceIdentity/DeviceIdentityStore reads are explicitly allowed. Kotlin's generated constructor
delegation inside the repository is not counted as another composition owner.

Three synthetic bytecode negative fixtures demonstrate detection of gateway bypass, an extra
constructor call, and direct HTTP access, including an unused nested path. They are scanner tests,
not instrumented production mutations. The guard is not a proof against reflection, native code,
or arbitrary future Java implementation; changes to architecture/language require guard review.
No self-written bytecode parser and no new APK runtime dependency were introduced.

Initial guard run failed on legitimate identity reads and constructor delegation; its overly broad
classification was corrected explicitly. Final result: 4/4 structural tests pass, plus existing
28/28 repository JVM tests. Existing CI already runs this app JVM suite.

## Runtime guard

The real Activity is launched with the existing test gateway seam, not a new product container.

- One Activity loads, confirms, rejects, edits and forgets; reflection in the test asserts the same
  repository object after each operation. Exact journal: list, confirm, reject, edit, forget.
- For each of four mutations, the authority applies the operation before the response is lost.
  Exact journal is list, mutation, list: no retry and only a read-back after uncertainty.
- Repeat all four with failed read-back: no retry, uncertainty remains, stale warning remains;
  edit draft is preserved. This does not promote cached data into an authoritative answer.

Final instrumentation: **28 PASS / 0 FAIL / 0 NORUN**, per-method fresh process runner.
Original output: `W4_INSTRUMENTATION.log`.
Device: SDK `emulator-5554`, API28, x86_64, 410x502@320dpi. Lenovo was not touched.
This run updated the W3 installation without uninstalling. W3's earlier identity/data reset still
means these results are NOT evidence of identity persistence across the old signing transition.

## Build and regression

Workspace Gradle 8.9/JDK at D:/JDK, Kotlin compiler in-process:

```text
:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
:core-protocol:test :core-audio:test :core-memory:test :core-memory-remote:test
```

Missing kotlin-test-junit5 in offline cache initially blocked the full suite; direct online access
also failed. The existing local proxy restored dependency access; no dependency versions or tests
were removed to get green. Core regression command completed BUILD SUCCESSFUL.

XML result counts, zero failures/errors/skips:

| Suite | Tests |
|---|---:|
| app (repository + structure) | 32 |
| core-protocol | 44 |
| core-audio debug / release | 20 / 20 |
| core-memory | 83 |
| core-memory-remote | 14 |

Gradle reused the unchanged core-protocol test result (UP-TO-DATE); not claimed as a new device run.
Room cache instrumentation was not rerun for this test-only change. Its prior 22-test evidence
remains separate. The APK built here is not approved for public redistribution until artifact
contents and signing/provenance checks are completed for the Preview.

## Status

W4: SOFTWARE AUTOMATED PASS. No cloud/backend, physical phone, CD12Max or full visual acceptance.
MemoryGatewayRegistry's product default remains null: ordinary installs display service unavailable,
not the test fixture memories. W0–W4 closure does not mean connected memory/voice is available.
