# Daylight UI — implementation and reuse

Scope: presentation only. No protocol, repository ownership, memory authority or audio pipeline changes.
Default direction: bright warm-white surfaces, slate-blue typography, quiet anime illustration.

## Reuse

- Native Android Material Light theme, ImageButton, RippleDrawable, TextView, ScrollView and
  existing app controls are reused; no new UI framework or animation engine.
- Google Material Design Icons, Apache-2.0, frozen commit
  `bd8cb85bd4bad964fe6918f79665bb40c3a8efef`.
  Source: `android/action/settings/materialiconsround/black/res/drawable/round_settings_24.xml`.
  Adapted to `app/src/main/res/drawable/ic_material_settings.xml`: only the tint attribute is
  qualified with `android:` for the native theme (no AppCompat dependency); caller sets the tint.
  Full license packaged at `assets/licenses/material-icons-APACHE-2.0.txt`.
- No source from the Android XiaoZhi candidate with unverified license was copied.
- No Cubism Core, third-party models, character artwork or additional audio player introduced.

## Generated illustration

`app/src/main/res/drawable-nodpi/companion_daylight.png` was generated with the built-in image tool.
It is a new preview illustration, not a licensed franchise character or a Live2D model.
Prompt: original adult female anime companion, waist-up transparent portrait, ash-brown
shoulder-length hair, pale blue ribbon, warm brown eyes, gentle smile, cream cardigan over
powder-blue blouse, refined slice-of-life illustration, restrained cel shading, warm daylight,
low saturation; no backdrop, particles, text, logos or existing character references.
No claim of exclusive copyright is made for generated artwork.

## Layout and boundaries

- Home reserves 48dp touch targets and a scrollable 48dp transcript tail; the illustration receives
  remaining height. History/state handling remains unchanged.
- Watch-sized windows (configuration height <= 300dp) use native immersive-sticky mode;
  edge swipe restores system controls. Phone-sized windows retain system bars.
- Settings and memory pages share semantic colors with Home.
- Primary/secondary text and status text contrast against the default surface are tested at >=4.5:1.
- Static illustration only: no claim of expression changes or playback-driven lip sync.
- No endpoint is provisioned; no live AI or real memory service is implied by this UI work.

## Validation (2026-10-01, local SDK emulator only)

- Build: `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`.
  Gradle 8.9 / JDK 21.0.8 runtime, Java/Kotlin target 17, minSdk 28 unchanged. BUILD SUCCESSFUL.
- App JVM: 32 tests, zero failures/errors/skips.
- DaylightUiTest (2), CompanionHomeTest (4), ProductShellTest (4): **10/10 PASS**.
  Raw output: [DAYLIGHT_UI_INSTRUMENTATION.log](tests/DAYLIGHT_UI_INSTRUMENTATION.log).
- MemoryTrustTest: **28/28 PASS, zero FAIL/NORUN**, existing per-method process runner.
  Raw output: [DAYLIGHT_MEMORY_INSTRUMENTATION.log](tests/DAYLIGHT_MEMORY_INSTRUMENTATION.log).
- Lint: **0 errors, 32 warnings**, not zero-warning certification. Warnings include unused legacy
  resources, a dependency update suggestion, existing draw allocation and the official icon's long path.
- First UI run: 9/10; continuous breathing prevented timely activity-idle detection in the new test.
  Replaced the infinite loop with one 1.8-second settling breath; unchanged assertions then passed.
- The official icon initially required an AppCompat attribute; fixed with the native `android:`
  namespace, rather than adding an entire runtime to satisfy one icon.
- Offline lint initially lacked play-sdk-proto; downloaded through the existing proxy without
  changing dependencies. Elevated and sandbox builds use different debug keys; rebuilt with the
  existing emulator-compatible key and installed with `-r`. **No uninstall or data clear** performed.
- Device: SDK `emulator-5554`, API28 x86_64, override 410x502@320dpi; Lenovo untouched.
  Screenshot [home.png](screenshots/daylight/home.png) retains emulator letterboxing, unedited.
- APK SHA-256: `f2f844a56a04923a2a4e6d2744e8326d153d9e42ef95ada36b08a8367b369a04`.
  This is a local debug build, **not** the published v0.4.0-preview asset; published artifacts unchanged.
- Art SHA-256: `52e538aad5f57302033194cd1e2de98725d8c4bd6c70da9f7f9e51a9fef48adc`.

Visual scope: first light-theme iteration with screenshot inspection, not user aesthetic sign-off.
Large font scales, phone-size visual review, real audio, hardware and server integration remain unverified.
