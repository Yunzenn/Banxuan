# Release infrastructure — local verification (2026-10-02)

Base: published main `6d978751756932ad41d37eb35036e402f6559084`.
Scope: wiring only, per owner confirmation that no production keystore exists.

| Check | Result |
|---|---|
| `test_preview_payload.py` | 5/5 PASS |
| `test_release_contract.py` | 7/7 PASS, including negative subcases |
| actionlint 1.7.12 on both workflows | PASS; shellcheck/pyflakes disabled, not a shell security proof |
| App Debug JVM | 34/34 PASS, 0 failures/errors/skips |
| App Release JVM | 34/34 PASS, 0 failures/errors/skips |
| assembleRelease | PASS, unsigned APK |
| lintRelease | 0 errors / 31 warnings |
| Disposable signing fixture on actual unsigned APK | PASS; test key deleted, no APK installed/published |
| Production certificate / key / protected Environment | PENDING, not configured |
| Actual tag-triggered hosted release | NOT RUN |
| Old Debug Preview -> production-signed upgrade | NOT RUN / NOT YET VERIFIED |

The W4 bytecode directory now follows the tested variant, so release structural tests examine release
classes rather than silently using Debug output. No assertions removed or tests skipped to get this result.

Commands:

```powershell
python evidence/tests/test_preview_payload.py
python evidence/tests/test_release_contract.py
.tools/actionlint-1.7.12/bin/actionlint.exe -shellcheck= -pyflakes= .github/workflows/ci.yml .github/workflows/release.yml
$env:JAVA_HOME='D:\JDK'
$env:GRADLE_USER_HOME='D:\AIwatch\.tools\gradle-home'
.tools/gradle-8.9/bin/gradle.bat --offline :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease '-Pkotlin.compiler.execution.strategy=in-process'
python evidence/tests/check_signing_fixture.py --apk app/build/outputs/apk/release/app-release-unsigned.apk --build-tools D:/AIwatch/.android-sdk/build-tools/35.0.0
```

Actionlint downloaded from the official rhysd/actionlint v1.7.12 release; Windows archive SHA-256:
`6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9`.
No tool binary, keystore or signed fixture was added to Git. No new tag, Release, server setting, real key
or GitHub Environment has been created by this wiring change. The public 0.4.1 Debug APK stays unchanged.
