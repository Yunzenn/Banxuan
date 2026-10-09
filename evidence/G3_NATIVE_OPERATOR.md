# G3 Native Device Operator — 2026-10-09

Status: PENDING runtime validation. Accessibility: PENDING separate spike.

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

Validation pending: app JVM/build/lint and API 28 SDK emulator selected product gate.
Physical phone/CD12Max, real timer provider execution, arbitrary app launch foreground verification,
voice Agent integration, Accessibility and background proactive notifications are not claimed PASS.
Follow-up: separate Accessibility observe→one safe action→observe spike, then local scheduler spike.

Initial local install onto emulator-5554 was rejected due to different Debug certificate. The shell
mistakenly continued into instrumentation on the stale installed APK; that run FAILED and is not
evidence for this change (`G3_NATIVE_STALE_APK.log`). No uninstall or clear-data was performed.
Fresh dedicated `banxuan-operator-api28` / emulator-5556 created instead; installs are now fail-fast.
