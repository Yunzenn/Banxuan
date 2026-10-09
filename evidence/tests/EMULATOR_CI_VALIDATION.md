# Product emulator CI — 2026-10-09

Status: **PASS / SOFTWARE EMULATOR**.

Hosted run [37909067182](https://github.com/Yunzenn/Banxuan/actions/runs/37909067182),
source commit `a151128`: fresh-clone PASS; product-emulator PASS.
Downloaded product artifact: **OK (40 tests)** in 37.927s; no ignored/assumption-skipped
statuses. Raw output: `EMULATOR_CI_PRODUCT_PASS.log`.
The following history records the failures before this successful run, not unresolved blockers.

Local checks: YAML parse PASS, PowerShell parse PASS, `git diff --check` PASS.
Synthetic runner-output checks: 4/4 PASS (accept 40/40, reject 0/40, 39/40 and ignored status).
These validate parsing only, not emulator behaviour.

First hosted run 37906399812: fresh-clone PASS; emulator FAIL before tests.
Artifact emulator.log: `Unknown AVD name [banxuan-ci]`. Creation and lookup used different
default directories. Fix: explicit shared ANDROID_AVD_HOME and fail-fast process-liveness check.
No test failure was hidden and no assertion was removed. Rerun PENDING.

Run 37907392358: workflow validation FAIL before jobs (runner context used at job env scope).
Corrected to export ANDROID_AVD_HOME from RUNNER_TEMP inside the provisioning step.
Run 37907676317 started successfully; runtime result still PENDING.

Run 37907676317 subsequently FAIL: emulator reported boot completed in 69468ms,
but could not connect to ADB daemon at startup and stayed offline to the poller.
Fix: use the installed platform-tools first on PATH and start ADB before emulator.
The 300s boot deadline remains unchanged. Rerun PENDING.

Run 37908577735: provisioning/boot PASS; install FAIL (`adb: command not found`).
The SDK tools path was step-local. Fixed by exporting it through GITHUB_PATH to subsequent steps.
No application/test change. Hosted product execution still PENDING.

Independent PR after #38/#39. No product behaviour, cloud config, credential, signing or
proprietary asset changes. Existing whole-run product suite is reused on Ubuntu/API 28/x86_64.
Compact fixture: 410x502px, 320dpi, fontScale 1.0. Hardware audio disabled.

The runner derives the expected count from selected source annotations (currently 40) and
refuses zero-match, ignored, assumption-skipped or failing runs. One instrumentation invocation.
PowerShell ADB resolution now supports Linux as well as Windows.

The job uses official SDK tools directly, bounded boot timeout and read-only GitHub permissions.
No release/provider secrets or third-party emulator action. Test output, emulator diagnostics,
API/ABI/window facts and logcat are uploaded even on failure; a failed test still fails the job.

Runtime PASS comes from the actual GitHub Actions run above, not YAML inspection.
Any PASS is SOFTWARE EMULATOR only. Phone/CD12Max, Cubism, live server, audible audio and
full eight-cell adaptive validation remain PENDING/outside this gate.
