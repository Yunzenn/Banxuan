# Contributing

This project is a companion agent for Android 9+ phones and Full Android watches. CD12Max is a reference
watch, not the sole product target or a development prerequisite. Please read
this before opening a PR — most rejected changes are rejected because they cross one of the boundaries
below, not because the code is bad.

## Read these first, in this order

| File | Why |
|---|---|
| [`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md) | **The project memory.** What we are building, the frozen boundaries, the gates. |
| [`ROADMAP.md`](ROADMAP.md) | Current version line (v0.1 → v1.0), what each version must demonstrate. |
| [`README.md`](README.md) | Current state, architecture, build, known risks. |
| [`REUSE_AUDIT.md`](REUSE_AUDIT.md) | Licence status per reference project. Check it before proposing a dependency. |

## Boundaries that are not up for debate

```text
No Compose, no Wear Compose, no Wear OS runtime, no new UI stack.
Native Android Views only; minSdk remains 28. Android 8 and earlier are out of scope.

No on-device ASR/LLM/TTS. Phones and watches are thin clients; the server does the heavy work.

No shell for the model, ever. Tools are typed. `exec_shell("anything")` is not a design option.

Never commit third_party/live2d. It is a private local dependency.
  git ls-files third_party/live2d   must always be empty.

Never commit customer or licensed character assets, and never a named voice actor's voiceprint.
```

## Target hardware

The platform target is Android 9+ / API 28+ phones and Full Android watches, not Wear OS.
This is a product scope, not certification of every Android device. CD12Max specifications remain
customer-supplied reference information until measured on that device.

Keep **410×502@320dpi = 205×251dp** as a permanent compact regression fixture. It is explicitly
configured emulator geometry, **not measured CD12Max logical density**. Physical panel dpi does not
determine Android logical density; insets and the keyboard further reduce available app space.
Before changing an emulator, record its existing overrides and restore those values afterwards.

```bash
adb -s emulator-5554 shell wm size 410x502
adb -s emulator-5554 shell wm density 320
adb -s emulator-5554 shell dumpsys window displays
```

The canonical adaptive validation matrix lives in [`DEVICE_COMPATIBILITY.md`](DEVICE_COMPATIBILITY.md).
PR A changes contracts only; PR B will implement one bounded adaptive UI round using existing Views,
size tokens and a window-derived `CompanionLayoutSpec`, not model-name checks or three layouts.
Preserve `ProductUi.page()`'s 560dp maxWidth policy. Do not alter voice, memory, backend, Live2D or
Operator semantics in that round.

## Build

The Gradle wrapper is committed, so use `./gradlew`. The offline flags below are what this project's dev
machine uses; with network access the normal Gradle resolution works too.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

### A fresh clone builds everything except the Live2D module

The official Cubism SDK may not be redistributed, so `settings.gradle.kts` includes `:core-live2d` only
when the SDK root is actually present. A clean checkout therefore configures and builds normally:

```bash
./gradlew :core-protocol:test :core-audio:test :app:assembleDebug :app:assembleDebugAndroidTest
```

`:core-live2d` is absent from the project graph unless you drop `CubismSdkForJava-5-r.5` into
`third_party/live2d/sdk-r5/`. This is an architectural boundary, not skipped coverage, and CI relies on
it: the GitHub runner has no Cubism SDK and is not expected to.

### Offline instrumentation note

In this repository's current offline Gradle cache, a module with no other AndroidX dependencies failed
to resolve the transitive `androidx.annotation:1.7.0-beta01` requested through the test runner
dependencies. `core-live2d` explicitly pins `androidx.annotation:1.7.0` because that version is present
in the verified offline cache.

This is a fact about this repository's current offline cache and dependency graph, not a general Android
rule. Do not generalise it, and do not remove the pin without re-running the offline build.

## Tests

**The UTP runner does not start in this environment.** Do not report a change as verified because Gradle's
connected test task "passed" — it does not run. Use `adb` directly:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
    -e class 'com.aiwatch.probe.CompanionHomeTest#pushToTalkDrivesTheFourStatesAndAppendsBothSides' \
    com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

**Run one test method per `am instrument` invocation.** Running a whole class in one process lets static
state from one test contaminate the next, which produces failures that are not real.

## Evidence rules

This is the part that matters most, and the easiest to erode.

**放宽的是设备限制，不是验证标准。** An unavailable optional capability disables that feature; it does
not certify the device or excuse a broken core interaction. Record phone and watch results separately.

```text
A claim you have not verified must be written as PENDING or UNVERIFIED. Never as PASS.

Static evidence is not runtime evidence.
  "the patch applies cleanly / tests pass in isolation"  !=  "it works on the device"

Simulator results are not device results.
  An emulator reports its own ABI and GL limits, not those of a physical phone or watch.
  It cannot certify their microphone, speaker, background survival, battery or vendor-ROM behaviour.

Distinguish a positive control from a real result.
  A harness passing because some other component produced pixels is not evidence
  that the component under test produced pixels.
```

If your PR says something works, the PR must say how you know, with the command and the output.

## Branches and commits

Trunk-based. There is exactly one long-lived branch, `main`, and it must stay explainable and verifiable.
There is no `develop`.

```text
feat/<issue>-<name>       fix/<issue>-<name>        test/<issue>-<name>
refactor/<issue>-<name>   docs/<issue>-<name>       chore/<issue>-<name>
```

Examples: `test/31-voice-contract-harness`, `feat/42-memory-gateway`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/). `main` is squash-merged, so
your branch history does not need to be tidy — the commit that lands does.

```text
refactor(voice): add injectable boundaries for P0-2B contract testing
test(voice): add P0-2B XiaozhiVoiceSession contract harness
fix(voice): reset per-turn playback latency state
```

Keep commits single-purpose. A testability refactor and the tests that use it are two commits, so a
bisect can tell them apart.

## Pull requests

`main` requires a pull request; direct pushes are not accepted. Fill in the PR template — in particular
the **Verification** and **Evidence status** sections. A PR that changes behaviour without saying how it
was verified will be asked for evidence before review.

## Licence

Project code is [Apache-2.0](LICENSE). That covers **this repository's code only**. It does not cover:

```text
the Live2D Cubism SDK                    (proprietary, not in this repository)
character / model assets                 (customer-supplied, not redistributable)
bundled third-party models               (each under its own terms)
concentus / Opus                         (see app/src/main/assets/licenses/)
```

Before adding a dependency or copying code, check its licence and record it in
[`REUSE_AUDIT.md`](REUSE_AUDIT.md) with a status of `DIRECT`, `ADAPT` or `REFERENCE ONLY`. Unverified
licences must be written as unverified.

## Commit identity

Author identity is deliberately pseudonymous and pinned. See [`GIT_PRIVACY.md`](GIT_PRIVACY.md). Do not
add personal information to commits.
