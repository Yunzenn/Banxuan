# Release Infrastructure — wiring only, 2026-10-02

## Current status

- Public APK remains **0.4.1-preview, Debug signed**. Its Release/tag/assets are unchanged.
- Single version source already exists in `gradle.properties`; Gradle and packaging both read it.
- Tag-triggered workflow and signed-APK provenance checks are implemented; production execution **PENDING**.
- Owner confirmed no long-term keystore yet. `release/signing-certificate.sha256` is **UNCONFIGURED**.
- Protected GitHub Environment, real secrets, offline backup, production signing and hosted publication
  have **not** been configured or verified by this change. Upgrade compatibility: **NOT YET VERIFIED**.
- No UI, updater, migration/export feature, server or cloud configuration changes.

## Pipeline

```text
PR / main -> CI (no release secrets) -> Debug artifact
                   + release unit tests / lint / unsigned APK / disposable test signing

Preview tag -> validate tag/version/code/main ancestry/certificate pin
            -> same complete CI from that tag (no secrets passed or inherited)
            -> unsigned APK artifact in the same run
            -> sign job, protected release Environment, read-only repository permission
            -> pinned-certificate / APK / version / hash inspection
            -> publish job (no keystore), independent APK reinspection
            -> new draft + assets -> verify remote asset digests -> publish prerelease
```

No main-triggered user Release, manual dispatch bypass, clobber, Debug signing fallback or APK rebuild
inside the signing job. Existing Release creation fails rather than overwriting. All referenced Actions
are pinned to audited official commit IDs. There is no third-party publishing action; GitHub CLI is reused.
The reusable CI builds/tests the release variant without credentials; no duplicate parallel test definition.

The unsigned APK is produced by normal Android Gradle `assembleRelease`, with **no signingConfig**.
Official `apksigner` is intentionally used in the separate credential-bearing job instead of running
Gradle/build plugins with release secrets. The temporary key lives under RUNNER_TEMP, is permission-restricted,
removed on shell exit and never uploaded. The runner is ephemeral; there is no signing job cache.

## Owner setup — required before any new release tag

1. Create a dedicated long-lived Banxuan keystore on a trusted machine; do not use a CI Debug key.
   Use non-personal certificate metadata (Banxuan / yunzenn), keep passwords out of commands/history and chat.
   Maintain at least one offline encrypted backup, including alias and recovery instructions. Test recovery.
2. Record only the public certificate SHA-256 in `release/signing-certificate.sha256` through review.
   This independent pin rejects an accidentally replaced keystore. Do not silently change the pin to pass a job.
3. In repository Settings → Environments, explicitly create **release**. Restrict deployment refs to
   intended version tags; disallow branches. Configure required reviewer approval/prevent self-review where
   supported, and restrict who may create/delete/update release tags via repository rulesets.
   **YAML naming an Environment does not prove its protections exist.** Audit settings before use.
4. Put ONLY in that Environment (not repository/organization secrets):
   `BANXUAN_KEYSTORE_BASE64`, `BANXUAN_KEYSTORE_PASSWORD`, `BANXUAN_KEY_ALIAS`, `BANXUAN_KEY_PASSWORD`.
   Base64 is transport encoding, not encryption. Do not send values in chat or commit them.
5. Increment VERSION_CODE beyond all historical Preview tags and set VERSION_NAME, e.g. a future
   `0.5.0-preview.1`. No new version/tag is created by this wiring PR.
6. Review/merge the exact version commit, then create `v${VERSION_NAME}` at that reviewed main commit.
   The workflow rejects tag/version/checkout disagreement, unmerged source, non-increasing versionCode,
   missing certificate configuration, wrong signer, debuggable APK or inconsistent bundle metadata.
7. Approve the release deployment only after reviewing CI, unsigned artifact source and environment policy.

Do not modify `v0.4.0-preview` or `v0.4.1-preview`. On upload/network failure, inspect the existing draft
before doing anything else. Re-running fails closed on an existing draft/release: no blind upload retries
or automatic asset replacement. Correct/recover manually with reviewed provenance, or choose a new version.

## Provenance

`tag -> peeled commit == event commit == checkout -> VERSION_NAME/CODE -> actual APK badging`
and `APK bytes -> SHA-256 -> BUILD_INFO/SHA256SUMS -> uploaded GitHub asset digest`.
The publisher re-runs aapt/apksigner/zipalign on the downloaded signed APK and compares all sidecars.
Only one signer is accepted, matching the pinned certificate; a debuggable APK is forbidden in this channel.
Draft assets must have exact filenames, size, uploaded state and SHA-256 before visibility is enabled.
`BUILD_INFO` carries the certificate, tag, source commit, versionName/code, hash and honest upgrade status.
Neither timestamps nor Release IDs determine Android version order.

## Upgrade/migration Gate — NOT RUN

Use a disposable dedicated validation emulator/reference device, not the user's only populated install.
Install the archived actual Debug Preview, populate and record identity, basic settings and Room cache,
then try the production-key signed next APK with ordinary `adb install -r`.

- **PASS** only if install succeeds and identity/settings/cache are actually unchanged.
- **EXPECTED BLOCKED** only after Android actually rejects the install for incompatible signing.
- Anything else (no keystore, no test, unrelated installation error, data loss) stays pending/failure.

Do not uninstall, clear data or bypass signature checks and call that an upgrade. Identity/settings continuity
takes priority over cache. If the signing generation changes, design explicit safe migration before asking
users to uninstall; this PR does not implement export/import or silently regenerate identity.
Also prove two distinct future versions signed with the same real key preserve data on normal update.

## Verification and limitations

See [local evidence](evidence/RELEASE_INFRASTRUCTURE_VALIDATION.md).
Disposable signing tests generate and delete a test-only keystore inside a temporary directory. They verify
real unsigned APK -> apksigner -> inspector -> metadata and reject wrong pins/Debug APKs. They do not install
anything and are **not** production signing, hosted Environment or old-Preview migration evidence.

References used: [GitHub reusable workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows),
[Environment protections](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments),
[official apksigner](https://developer.android.com/tools/apksigner).
