# Releasing skiko-winui

`skiko-winui-release.properties` is the committed source of the next release's
version and upstream identity. This branch prepares the CMP WinUI 1.12.1 release:

```properties
release.version=0.150.1-alpha1
upstream.version=0.150.1
upstream.tag=v0.150.1
upstream.commit=3956e988e6e93eaf1ee985d725049443e7845807
kotlinWinRT.release.version=0.1.0
```

The branch is `release-0.150.1`. The integration merge starts from the
official `v0.150.1` tag and imports WinUI changes through `winui_dev`
commit `863c2e96d54edc61b901a2821a9f40b9bd1d6dd2`, while keeping the upstream
Skiko API and Skia `m150-1f14f1166a` from `v0.150.1`. Newer upstream renderer,
Skottie extraction, Graphite, Vulkan, and m151–m154 changes are excluded from
the source tree. Skottie remains part of the core API and native library, as
in Skiko 0.150.1. Both `winui-jvm` and `winui-mingw` retain the latest WinUI
backend contracts, including D3D12 interop and compositor frame scheduling.

Kotlin 2.4 and JDK 25 remain required by kotlin-winrt. Kotlin source/API language
levels remain 2.3/2.2 for the WinUI build. These tooling requirements are
separate from the Skiko/Skia baseline used by CMP 1.12.1.
The JVM main compilation explicitly retains module name `skiko`: Kotlin 2.4's
default module name would change the JVM names of internal accessors used by
inline API. Public JVM signatures for all 411 Skia classes and companions in
the official `skiko-awt:0.150.1` jar were compared against this build and match.

The pinned upstream commit is integrated on this branch. The release uses
stable kotlin-winrt `0.1.0` and the source/runtime checks below.
The release checks reject an unmerged baseline; resolve and integrate the official
upstream tag when selecting a different release baseline.

## Version and source identity

- Development builds default to `<release.version>-SNAPSHOT`.
- `-Pdeploy.release=true` selects exactly `release.version` for WinUI builds.
- `-Pskiko.version=...` remains available for local/snapshot consumers. In release
  mode it must match the committed release version.
- The shared Skiko build version, WinUI jars, KLIBs, POMs, and runtime dependency
  versions use the same resolver. WinUI builds no longer derive their default
  version from upstream's placeholder `deploy.version=0.0.0`.
- The release tag is derived as `skiko-winui/v<release.version>`. The namespace
  separates fork releases from imported upstream tags.
- Prerelease numbers may use one or more digits, including the selected
  `0.150.1-alpha1` version and older two-digit versions.
- Every WinUI POM records `skiko.upstream.version`, `skiko.upstream.tag`, and
  `skiko.upstream.commit`. Release POMs also record the fork release tag in SCM.
- Windows native libraries and ICU data use the directory produced by
  `unzipSkiaReleaseWindowsX64`, or the explicit `skiko.winui.windowsSkiaDir`
  override. Cached Skia versions are never selected by directory enumeration;
  changing the selected Skia package also invalidates the native build inputs.

Both `winui-jvm` and `winui-mingw` share this release identity. The Maven group is
`io.github.compose-fluent`; the complete release contains:

| Artifact | Purpose |
| --- | --- |
| `skiko-winui` | Multiplatform entry point |
| `skiko-winui-jvm` | `winui-jvm` API and WinUI integration |
| `skiko-winui-windows` | JVM Windows native runtime |
| `skiko-winui-mingw` | `winui-mingw` / `mingwX64` KLIB |
| `skiko-winui-mingw-runtime` | MinGW runtime assets |

The formal release uses **kotlin-winrt `0.1.0`**, now available from Maven Central.
Both `kotlinWinRT.release.version` and root `kotlinWinRT.version` select `0.1.0`.
Both backend paths must be rebuilt with this stable dependency; the earlier
`0.150.1-alpha01` rehearsal used `0.1.0-SNAPSHOT`. Publication checks inspect
both configuration and packaged POM/Gradle dependency versions; signing an old
SNAPSHOT-based bundle does not make it eligible for publication.

The upstream tag/SHA fixes the source being integrated. The final fork commit
and artifact hashes in `release-manifest.json` identify exactly what was built.
The ancestry check confirms inclusion of the baseline; it does not classify
every later commit as an upstream change or a fork change. Review the changes
relative to the selected baseline when preparing a release, especially if a
development branch has already integrated a newer upstream version.

## Integrate the selected upstream version

Perform normal integration on `winui_dev` (or an explicitly selected development
branch), preserving the fork's changes. `master` and `origin/master` are not
updated by these commands:

```powershell
git switch release-0.150.1
git fetch --no-tags https://github.com/JetBrains/skiko.git refs/tags/v0.150.1:refs/skiko-winui/upstream/v0.150.1
git rev-parse 'refs/skiko-winui/upstream/v0.150.1^{commit}'
python scripts/skiko_winui_release.py verify --fetch-upstream
```

Resolve conflicts, review the diff, and validate both backend paths before
tagging. Fetching in the verifier only updates the private
`refs/skiko-winui/upstream/...` namespace. It never merges or changes a branch.

The `verify --fetch-upstream` command checks that the official tag resolves to
the pinned SHA, that this SHA is an ancestor of the current source, and that
`dependencies.skia` matches that upstream tag. A newer Skia package is rejected
even when the pinned commit is present in the branch's merge history. Adding
`--require-tag` also requires the derived fork tag to point to HEAD and the
checkout to be clean. In GitHub Actions, publication must be running from that
exact tag, including when the workflow is dispatched manually.
`--for-publish` additionally requires the committed kotlin-winrt build version
to match the pinned stable release dependency; it includes `--require-tag`.

## CMP WinUI 1.12.1 baseline rehearsal validation

Validated on Windows x64 with JDK 25.0.4.7, Visual Studio 2026 MSVC x64 tools,
Windows SDK 10.0.26100.0, Kotlin 2.4.0, and kotlin-winrt 0.1.0-SNAPSHOT.

- Passed: upstream tag/SHA and Skia package verification; 25 release-tool tests.
- Passed: `winui-jvm` main/test compilation and `winui-mingw` main compilation,
  including the m150 Skia C ABI bridge.
- Passed: 9 focused JVM tests, 5 MinGW native bridge smoke tests, and the
  synthetic C++ indirect pointer parser/state tests.
- Passed: JVM signature comparison of all 411 Skia classes and companions
  against the official `org.jetbrains.skiko:skiko-awt:0.150.1` artifact.
- Passed: release-mode compilation/linking and local BuildRepo publication of
  all five `0.150.1-alpha01` components via `publishSkikoWinuiToBuildRepo`.
- Window-hosted JVM/MinGW sample smokes have not been run on this branch.
  The native bridge smoke covers adapter selection, diagnostics, and callback ABI.

Commands from the repository root (JDK 25 and NuGet CLI on PATH):

```powershell
python -m unittest discover -s scripts/tests
python scripts/skiko_winui_release.py verify --fetch-upstream
.\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' '-Pskiko.winui.skipSamples=true' :skiko:checkWinuiAwtFreeBoundary :skiko:checkWinuiJvmApiClasspathBoundary :skiko:compileKotlinWinuiJvm :skiko:compileTestKotlinWinuiJvm :skiko:compileKotlinWinuiMingw --no-configuration-cache --no-daemon --max-workers=1
.\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' '-Pskiko.winui.skipSamples=true' :skiko:runWinuiIndirectPointerNativeTests :skiko:winuiJvmTest --tests 'org.jetbrains.skiko.winui.*' :skiko:winuiMingwTest --tests 'org.jetbrains.skiko.winui.WinUIMingwNativeBridgeSmokeTest' --no-configuration-cache --no-daemon --max-workers=1
```

Maven Central now serves the kotlin-winrt `0.1.0` toolkit, runtime, authoring,
and JVM/MinGW POMs (verified on 2026-10-11). The earlier unsigned SNAPSHOT-based
artifacts remain suitable only for rehearsal and downstream integration checks.

## Prepare artifacts locally

Host requirements: Windows x64, JDK 25, Visual Studio/MSVC x64 build tools,
Windows SDK 10.0.26100.0, NuGet CLI on PATH, Git, and Python 3.10 or newer. Kotlin/Native, Skia, and
WinUI dependencies are provisioned using the existing Gradle tasks.

From the repository root, after integrating the upstream baseline:

```powershell
.\gradlew.bat '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' '-Pskiko.winui.skipSamples=true' '-Pdeploy.release=true' :skiko:checkWinuiAwtFreeBoundary :skiko:checkWinuiJvmApiClasspathBoundary :skiko:publishSkikoWinuiToBuildRepo --no-configuration-cache --no-daemon --max-workers=1
python scripts/skiko_winui_release.py bundle --dry-run
```

This rehearsal writes `skiko/build/rehearsal/<release.version>/`, containing an
unsigned `skiko-winui-<release.version>-rehearsal.zip`, a manifest with
`dryRun=true` / `publishable=false` and the actual kotlin-winrt dependency
versions, and draft release notes. It performs no upload, creates no release
tag, and needs no publishing credentials. The `upload` command rejects these
artifacts before contacting Central.

For a formal bundle, first use published kotlin-winrt `0.1.0`, commit the final
configuration, and rebuild. Supply the signing key using the existing Gradle
environment properties:

- `ORG_GRADLE_PROJECT_signingInMemoryKeyId`
- `ORG_GRADLE_PROJECT_signingInMemoryKey`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`

Run `python scripts/skiko_winui_release.py bundle` without `--dry-run` to
assemble the signed Central bundle. It rejects unsigned artifacts, a dirty
checkout, or dependencies on the unpublished toolkit SNAPSHOT. The Gradle release
publication tasks also require the pinned upstream commit to be present in HEAD.

The bundle command selects only the configured version of the five publications
from `skiko/build/repo`, checks their POM identity and signatures' presence, and
calculates the required MD5/SHA-1 checksums. It excludes other versions and Maven
repository metadata. Central Portal performs the signature and component
validation. The output under `skiko/build/release/<release.version>/` contains:

- `skiko-winui-<release.version>-central.zip`
- `release-manifest.json`, with upstream/fork commits and artifact/bundle SHA-256
- `release-notes.md`, including `Based on Skiko <upstream.version>`

Building these artifacts is not a runtime smoke test. Complete the appropriate
JVM and MinGW runtime checks before publishing and describe any remaining
limitations in the release notes. The rehearsal's manifest records the current
SNAPSHOT dependency; its source and artifact checks do not establish that the
formal kotlin-winrt release is available.

## CI and publication

`.github/workflows/publish-skiko-winui.yml` has two channels:

| Trigger | Channel | Upload |
| --- | --- | --- |
| Push to `winui_dev` | Snapshot | Existing Central SNAPSHOT repository |
| Push `skiko-winui/v<release.version>` | Release | Central Portal, then GitHub Release |
| Manual dispatch | Selected channel | Only when `publish=true` |

A manual `release` dispatch with `publish=false` prepares and saves the
nonpublishable rehearsal bundle and manifest without signing or uploading to
Central. It can run before kotlin-winrt `0.1.0` is available. A manual publication must
select the matching release tag as its workflow ref. No workflow merges upstream
source during publication; it builds the checked-out source after verifying it.

Keep these existing repository secrets configured:

- `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`: Central Portal user-token
  username and password.
- `MAVEN_GPG_KEY_ID` / `MAVEN_GPG_KEY_ARMOR` / `MAVEN_GPG_KEY_PASSWORD`: PGP signing.

After validation and review, commit the release source and configuration and tag
that final commit:

```powershell
git tag -a skiko-winui/v0.150.1-alpha1 -m 'skiko-winui 0.150.1-alpha1; based on Skiko 0.150.1'
python scripts/skiko_winui_release.py verify --fetch-upstream --for-publish
git branch --show-current
git push origin refs/tags/skiko-winui/v0.150.1-alpha1:refs/tags/skiko-winui/v0.150.1-alpha1
```

Verify the current branch and explicit destination before pushing. The workflow
uploads the bundle using Central Portal's `AUTOMATIC` publishing mode, records
the deployment ID, and waits for `PUBLISHED`. Only then does the separate job
create the GitHub Release and attach the bundle and manifest. Alpha/beta/RC
versions are marked as GitHub prereleases. Published versions and tags should
not be reused for changed source.

An interrupted upload must not blindly retry with a new deployment. The output
`central-deployment.json` records the accepted deployment ID. Resume checking the
same deployment with:

```powershell
python scripts/skiko_winui_release.py upload --deployment-id <existing-deployment-id>
```

This still checks the matching tag, clean checkout, manifest, and bundle hash
before using the Portal token. A timeout or `FAILED` state does not create a
GitHub Release.

## Focused validation

Release tooling tests use temporary Git repositories and mocked Portal calls:

```powershell
python -B -m unittest discover -s scripts/tests -v
```

POM generation checks require JDK 25 and the normal Gradle/plugin dependencies,
but do not compile/link the native runtime or upload anything:

```powershell
.\gradlew.bat -p skiko '-Pskiko.winui.enabled=true' '-Pskiko.winui.mingw.enabled=true' generatePomFileForKotlinMultiplatformPublication generatePomFileForWinuiJvmPublication generatePomFileForWinuiMingwPublication generatePomFileForSkikoWinuiWindowsRuntimePublication generatePomFileForSkikoWinuiMingwRuntimePublication --no-configuration-cache --no-daemon --max-workers=1
```

Add `-Pdeploy.release=true` to check final release versions and SCM tags. This
metadata-only check does not establish that the upstream source is merged or
that signed artifacts are ready; use the verifier and bundle checks for those.

Publisher API and bundle format references:
[Central Portal API](https://central.sonatype.org/publish/publish-portal-api/),
[Central bundle upload](https://central.sonatype.org/publish/publish-portal-upload/),
[Central requirements](https://central.sonatype.org/publish/requirements/).
