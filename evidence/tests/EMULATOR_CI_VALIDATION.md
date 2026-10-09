# Product emulator CI — 2026-10-09

Status: PENDING first GitHub-hosted runtime execution.

Local checks: YAML parse PASS, PowerShell parse PASS, `git diff --check` PASS.
Synthetic runner-output checks: 4/4 PASS (accept 40/40, reject 0/40, 39/40 and ignored status).
These validate parsing only, not emulator behaviour.

First hosted run 37906399812: fresh-clone PASS; emulator FAIL before tests.
Artifact emulator.log: `Unknown AVD name [banxuan-ci]`. Creation and lookup used different
default directories. Fix: explicit shared ANDROID_AVD_HOME and fail-fast process-liveness check.
No test failure was hidden and no assertion was removed. Rerun PENDING.

Independent PR after #38/#39. No product behaviour, cloud config, credential, signing or
proprietary asset changes. Existing whole-run product suite is reused on Ubuntu/API 28/x86_64.
Compact fixture: 410x502px, 320dpi, fontScale 1.0. Hardware audio disabled.

The runner derives the expected count from selected source annotations (currently 40) and
refuses zero-match, ignored, assumption-skipped or failing runs. One instrumentation invocation.
PowerShell ADB resolution now supports Linux as well as Windows.

The job uses official SDK tools directly, bounded boot timeout and read-only GitHub permissions.
No release/provider secrets or third-party emulator action. Test output, emulator diagnostics,
API/ABI/window facts and logcat are uploaded even on failure; a failed test still fails the job.

Runtime PASS must come from an actual GitHub Actions run, not YAML inspection.
Any PASS is SOFTWARE EMULATOR only. Phone/CD12Max, Cubism, live server, audible audio and
full eight-cell adaptive validation remain PENDING/outside this gate.
