# Calendar event handoff — 2026-10-21

## Scope

Adds a user-driven native device action under **Settings → Device Operations → Add schedule**.
This is source-only and is not included in the public `0.4.2-preview` APK. The change does not
implement speech parsing, an Agent Planner, background scheduling, calendar synchronization, or
direct event creation.

## Reuse and consent boundary

Banxuan reuses Android's calendar editor via `Intent.ACTION_INSERT` and
`CalendarContract.Events.CONTENT_URI`, pre-filling title, start/end timestamps, and `ALL_DAY=false`.
The user confirms inside Banxuan before the handoff, then reviews and saves in the calendar app.
`HANDOFF` means only that the intent was dispatched; it does not mean an event was saved.

Banxuan does not read or write the Calendar Provider and requests neither `READ_CALENDAR` nor
`WRITE_CALENDAR`. A narrow `<queries>` intent signature is present only because the UI checks whether
a compatible handler exists; there is no `QUERY_ALL_PACKAGES`. No third-party source or calendar
library was added. See [reuse audit](../REUSE_AUDIT.md).

Official references: [Add an event to the calendar](https://developer.android.com/guide/components/intents-common)
and [declare package visibility needs](https://developer.android.com/training/package-visibility/declaring).

## Behavior limits

- Title is trimmed, required, limited to 80 characters, and rejects control characters.
- Start must be in the future; duration is bounded to 1–1440 minutes and checked for overflow.
- The first form defaults to 30 minutes. The system calendar may let the user edit the details
  before saving.
- Canceling either Banxuan form/confirmation dispatches nothing. Ambiguous dispatch is reported as
  `UNKNOWN` and is never retried automatically.
- Missing calendar handler reports `UNAVAILABLE`; unsupported controls are disabled.

## Verification performed

Environment: API 28 x86_64 SDK emulator, `emulator-5554`, compact fixture 410×502 px at 320 dpi
(205×251 dp), no physical-device claim.

| Check | Result |
|---|---:|
| Calendar/Native Operator Android instrumentation | 13/13 PASS |
| Canonical product instrumentation, one invocation | 63/63 PASS; no failures/skips |
| Adaptive window/font matrix after system-bar change | 8/8 PASS |
| App module debug unit tests | 61/61 PASS |
| All module unit-test variants executed | 303 executions; 0 failures/errors/skips (debug/release variants repeat cases) |
| `assembleDebug` + `assembleDebugAndroidTest` | PASS |
| `lintDebug` | 0 errors, 31 warnings |
| `git diff --check` | PASS |

The adaptive matrix covers 205×251, 240×320, 360×640, and 411×891 dp at font scales 1.0 and 1.3.
The test also exercises PTT state transitions, Settings/Memory reachability, text overlap, insets,
and Activity recreation. PTT dispatch is tested through the view's touch-event path; this is not
evidence of physical touchscreen behavior.

The all-module count comes from running both the `testDebugUnitTest` task selector and root `test`
task across the multi-module build. Debug/release variants repeat app/audio test cases; the total
is executions, not unique test methods. The build also reports existing deprecation/vendor-source
compiler warnings; no test was skipped or weakened.

The locally generated debug APK is `app/build/outputs/apk/debug/app-debug.apk` (17,286,280 bytes,
SHA-256 `5BBDC3CC3858CAFE4669311728BC3C36E72481871CE1FF0D6BA136B9C824F2C0`). It was built from
local working-tree changes on `codex/system-alarm`, based at `868088f3d0b1713b718e93eee6e8f2976f1b8f8e`;
this is not a clean-commit release artifact. It has not been uploaded, released, or installed over a
user's physical device. Public release and update compatibility are unchanged. No server, firewall,
DNS, credential, or paid API was touched.

## Still pending

Actual event insertion/save in each target calendar app, reference-phone and CD12Max verification,
accessibility/visual operator work, voice-to-action planning, natural-language intent extraction,
and integration with future Agent Planner remain unverified or unimplemented.
