# CI and releases

## Merge validation

[CI](../../.github/workflows/ci.yml) runs for every PR targeting `main`, every
push to `main`, and manual dispatch. There are no path filters. Superseded PR
runs are cancelled; main builds finish independently. Forks need no secrets.

| Required check name | What it proves |
| --- | --- |
| `Node validation` | Clean lockfile install, version/changelog agreement, TypeScript, ESLint, Jest including CLI tests, real Jest failure propagation, and three host native regressions |
| `Android release validation` | JVM tests, source-built ARM64 PRoot/loader/PTY, R8 release compilation, and unsigned APK validation |

Jest discovers `cli/__tests__/cli.test.js` through the root configuration. Do
not run it a second time as a separate CLI suite. The failure probe deliberately
returns 1 after a real failed assertion; CI checks both the exit code and the
named assertion. The previous probe referenced a deleted file. Gradle's normal
test task propagates failures; the legacy `horus.forceKotlinUnitFailure` property
has no remaining test consumer and is not used as evidence of failure handling.

The APK verifier checks ZIP integrity, application ID, version name/code,
ARM64-only packaging, non-debuggable status, DEX and JS bundle presence, unsigned
status, native ELF architecture, and PRoot/loader hashes against the embedded
manifest. These checks do not establish byte-for-byte F-Droid reproducibility
or acceptance on a device. Gradle's release variant bundles production JS and
retains onboarding/security behavior; debug APKs bypass those flows.

Jest JSON and Gradle HTML/XML reports are retained for seven days, including on
test failure. A validated unsigned APK and metadata (commit, run URL, version,
SHA-256, runtime manifest) are retained for seven days on successful PR/main
builds. Download them from the run's Artifacts section. They are build diagnostics,
not public releases or installable updates. Treat contributor artifacts as
untrusted. No signing credentials are available to CI.

## Local checks and toolchain

Use Node from [`.node-version`](../../.node-version), JDK 17, Android SDK platform
`platforms;android-37.0`, build-tools 37.0.0, NDK 27.1.12297006, and CMake 3.22.1.
Use the Gradle wrapper (9.4.1, with its distribution SHA-256). React Native
0.87.0 supplies AGP 9.2.1 through the npm lockfile; Kotlin is 2.2.0. CI uses
Ubuntu 24.04. Install native prerequisites `make`, a C compiler, `git`, `bash`,
and `unzip`. Set `JAVA_HOME` and `ANDROID_HOME`; accept SDK licenses locally.

```sh
git submodule update --init --recursive
npm ci --no-audit --no-fund
node scripts/ci/check-version.js
npm run typecheck
npm run lint
npm run test:unit -- --ci --watchAll=false
# Expected exit status 1 with HORUS_FORCE_TS_UNIT_FAILURE in the failed test:
npm run test:unit:failure-probe
bash scripts/native/test-unix-socket-translation.sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon --stacktrace --max-workers=2
cd ..
node scripts/ci/verify-apk.js android/app/build/outputs/apk/release/app-release-unsigned.apk
```

Do not supply `horus.releaseStoreFile` or other signing properties during
validation. A local signed release will intentionally fail the unsigned verifier.
`check-version.js` checks package/Android version agreement and a nonempty
Fastlane changelog named for `versionCode`. It does not require a version bump
on every PR; release review must check monotonic version codes against F-Droid.

For failures, open the first failed step and its reports. `npm ci` failure
usually means the lockfile is inconsistent or the registry is unavailable.
Fix the lockfile with the pinned Node version; do not replace `npm ci` with
`npm install` in CI. Type/lint/Jest errors should be reproduced with that exact
command. A missing native source requires recursive submodule initialization.
Socket bind failures on a restricted host require a host that permits Unix
sockets. SDK/license errors require the exact packages above. JVM/R8/CMake
failures need the first actual error in the Gradle log, not just its final
stacktrace. ABI, version, or digest mismatches are release-integrity failures;
fix their source instead of weakening the verifier. For download outages,
confirm upstream recovery and rerun only failed jobs. Do not loop full builds.

## Enable main protection after observing CI

The October 18, 2026 audit found `main` unprotected, no repository rulesets,
and no prior Actions runs. This PR does not change live repository settings.
After both jobs have actually run successfully, configure a branch rule/ruleset
for `main` with:

- Required PRs and at least one independent approval; dismiss stale approvals
  and require approval of the most recent push by someone other than its author.
- Required checks **`Node validation`** and **`Android release validation`**,
  sourced from GitHub Actions; require the branch to be up to date before merge.
  Confirm these exact names in the successful PR checks UI before saving.
- All review conversations resolved; block force pushes and branch deletion.
- Apply to administrators where supported. Permit bypass only for a named,
  minimal emergency maintainer group; record the reason and obtain a follow-up
  review for each bypass. Do not grant general contributor bypass.

Do not enable merge queue without first adding and testing a `merge_group`
trigger. Fork workflows may need a maintainer's normal GitHub approval before
their first run. Keep Actions workflow-token permissions read-only and do not
allow PR workflows to approve PRs. CI explicitly grants only `contents: read`,
does not persist checkout credentials, and never uses `pull_request_target`.
Gradle cache writes are restricted to `main`; PRs only read it. npm caches the
download cache, not `node_modules`; every job performs `npm ci`.

## Release acceptance on ARM64

PR validation and main validation use the same jobs. Main green proves a
production-mode build, not a release candidate's runtime acceptance. Before
tagging, review the exact candidate commit, changelog, version increment,
dependencies and native submodules. Run `npm run test:android:connected` on a
real ARM64 Android device and record device model/API, commit, commands, counts,
and sanitized results. Follow the isolation precautions in
[ARM64 validation](../testing/android-arm64-validation.md) so debug tests do not
overwrite a user's production app/data.

Also accept the **release-mode** candidate on real ARM64 hardware using an
isolated test device/profile: fresh onboarding/password/lock, upgrade from the
prior supported release when an F-Droid-signed candidate is available, PRoot
startup, shell/PTY input and exit, multiple sessions, file operations, runtime
hash rejection and optional USB remote access. Keep acceptance evidence tied to
the candidate SHA. Debug tests cannot prove production security behavior; an
x86 emulator cannot prove ARM64 PRoot behavior. Never use production signing
secrets for validation. Device acceptance is a manual release gate, not an
expensive per-commit emulator matrix.

## Versions, F-Droid and public APKs

Change `versionName` and monotonically increase `versionCode` in
`android/app/build.gradle`, keep `package.json` version aligned (regenerate the
lockfile's root version if needed), and add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
The optional host CLI has an independent version.

The authoritative build recipe lives in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.scariflabs.horus.yml).
The audited recipe tracks `v` version tags, initializes submodules, uses the
pinned NDK, builds Hermes from source and removes its prebuilt compiler. It
currently adapts the Gradle plugin's Java toolchain to JDK 21. GitHub validation
uses the repository's JDK 17 configuration and npm-provided Hermes compiler;
it is not a replacement for the F-Droid source-only build.

The audited public release is `v1.0.4` / code 5. GitHub's
[release record](https://github.com/scarif-labs/horus/releases/tag/v1.0.4)
identifies its APK as the same F-Droid file, with SHA-256
`bf67340c4dd8321636c158902d0bf7b5a4684a23eae982d0026d45e348cb3430`.
The F-Droid signing certificate SHA-256 is
`311e3d58a6f92b2704aff6fabb5ad2f1ceddfe8b4bda2ed4dafeb72819b7112c`.
Preserve this signer: public GitHub APKs must be the verified F-Droid-signed
bytes. Never sign with a new GitHub keystore or upload a debug/CI APK as a release.

To verify a public download with Android build-tools on PATH:

```sh
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs horus-1.0.4.apk
```

Require successful signature verification and the certificate digest above;
compare the checksum and bytes with F-Droid's independently obtained copy
(`https://f-droid.org/repo/com.scariflabs.horus_5.apk` for code 5). A checksum from
the same download alone does not authenticate the publisher. If verifying the
optional `.asc`, obtain and authenticate F-Droid's PGP key separately; see the
release record. A PGP signature does not replace APK signer verification.

## Prioritized follow-up: F-Droid-aware release automation

1. **P1: Design and review a manual-approval publication gate.** A future
   `vX.Y.Z` tag workflow should verify that the tag points to an accepted main
   commit, matches source version/code, has passing CI and recorded real-device
   acceptance. Tags and publication remain manual until this is reviewed.
2. **P1: Retrieve, never re-sign.** Wait for the F-Droid build/index, retrieve
   the expected package/version/code, verify APK signature/certificate and
   trusted F-Droid index checksums, ABI and source mapping. Use bounded retries
   or manual resumption because F-Droid delivery is asynchronous. Refuse a
   mismatched signer or version; never fall back to a locally signed APK.
3. **P2: Publish only verified bytes after approval.** Use a protected GitHub
   environment and a separate publication job with narrowly scoped
   `contents: write`. Create a draft release with the identical F-Droid APK,
   available upstream signature and checksums; verify downloads before approval
   to publish. Make retries idempotent and reject conflicting existing assets.
   No tag-triggered publication workflow is added by this foundation.

If a release fails before publication, leave it unpublished, fix the cause and
repeat only the affected checks/acceptance. Do not move published tags or
replace public APK bytes silently. If a public release is defective, document
the problem and ship a higher versionCode through F-Droid, then mirror its
verified signed APK. Android downgrade is not a general rollback strategy;
protect user data and provide migration/recovery instructions. For a bad
GitHub mirror, halt synchronization, retain evidence and consult maintainers
before replacing/removing assets. A signer mismatch is a hard stop.

## Dependency and security maintenance

Actions are pinned to full commit SHAs; monthly Dependabot PRs propose updates.
Review upstream ownership/release notes and the changed action code before
merging; preserve SHA pins and rerun both jobs. Use `setup-gradle`'s wrapper
validation and the wrapper distribution checksum. Change Node, JDK, React
Native/AGP, Gradle, SDK/NDK and CMake deliberately in focused PRs, keeping this
guide and CI aligned; validate device behavior when native dependencies change.
Toolchain versions are controlled, but hosted runner images, JDK patch selection
and external artifact availability still change. CI is not a fully hermetic
or independently reproducible F-Droid build.

Private vulnerability reporting was **enabled** in the October 18 audit; verify
the private form in [Security](https://github.com/scarif-labs/horus/security)
remains available. The audit found Dependabot security updates, secret scanning
and push protection disabled. Maintainers should review enabling these settings
and the Actions allowlist/token policy separately. This PR changes none of those
live settings. Actions currently allows all actions without a repository-wide
SHA-pin requirement; the default workflow token is read-only and PR approval by
workflows is disabled. Follow the [security policy](../../.github/SECURITY.md)
for reports.
