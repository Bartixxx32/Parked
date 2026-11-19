# Publishing Parked on F-Droid

This document describes how Parked is prepared for [F-Droid](https://f-droid.org/),
how reproducible builds are verified and how to submit a new version.

## Key facts

| Item | Value |
|---|---|
| Application ID | `com.bartixxx.parked` |
| F-Droid metadata file | `metadata/com.bartixxx.parked.yml` |
| Source code | https://github.com/Bartixxx32/Parked |
| Issue tracker | https://github.com/Bartixxx32/Parked/issues |
| License | GPL-3.0-only |
| Category | Navigation |
| Author | Bartixxx |
| Release APK | `Parked-<version>.apk` |
| Upstream certificate SHA-256 | `684d25dd4c9b136f304dd2ecb6b1fd6acb776a6cf9b7fb030f8cdfaf5f9f1866` |

> **Package rename:** versions up to 1.0.3 used `io.github.bartixxx32.parked`.
> Android treats the new ID as a different app, so F-Droid users of the old package
> cannot update in place and must install the new app separately.

## Why reproducible builds

Parked is published on GitHub with the upstream release key. F-Droid rebuilds the
tagged source itself and checks that its unsigned build is byte-identical to the
upstream APK apart from the signature. When the check passes, F-Droid publishes the
upstream-signed APK and pins the certificate via `AllowedAPKSigningKeys` — users get
the same signature from GitHub and F-Droid.

Reproducibility requirements that the project satisfies:

- the Gradle wrapper version is committed and identical on both sides,
- all dependencies come from Maven Central / Google Maven with a committed version
  catalog; no `+` or snapshot versions,
- no Google Play Services, Crashlytics or other proprietary dependencies,
- the release workflow builds an **unsigned** APK and signs it afterwards with
  `apksigner` from **build-tools 34.0.0**, v2 signature only. Newer apksigner
  versions write a signing block that `apksigcopier` cannot compare reliably,
- the APK is not modified after signing (no zip re-alignment, no extra files).

The CI workflow verifies the result with the same tool F-Droid uses:

```bash
apksigcopier compare --unsigned Parked-<version>.apk app-release-unsigned.apk
```

## Fastlane metadata

Store metadata lives in the repository so F-Droid can read it from the tagged commit:

```
fastlane/metadata/android/
├── en-US/
│   ├── title.txt
│   ├── short_description.txt
│   ├── full_description.txt
│   ├── changelogs/<versionCode>.txt
│   └── images/
│       ├── icon.png
│       └── phoneScreenshots/
└── pl-PL/
    └── (same structure)
```

Rules used by F-Droid:

- `title.txt` — max. 30 characters,
- `short_description.txt` — max. 80 characters,
- `full_description.txt` — max. 4000 characters,
- `changelogs/<versionCode>.txt` — max. 500 characters, one file per released
  `versionCode`,
- icon 512×512 PNG, screenshots 1080 px wide or similar.

Screenshots are generated in GitHub Actions (`.github/workflows/screenshots.yml`)
on a clean emulator, so they always match the current UI. Download the artifact and
commit the PNGs under `phoneScreenshots/` for both locales before tagging a release.

## fdroiddata recipe

The complete recipe is kept in
[`docs/fdroiddata/com.bartixxx.parked.yml`](fdroiddata/com.bartixxx.parked.yml).
The newest `Builds` entry points at the `v1.5.2` tag commit, which already
contains `backend/worker/package-lock.json` (F-Droid rejects a `package.json`
without a lockfile). For later releases, add a new entry with the full SHA of
the new tag before submitting. F-Droid builds from that commit, never from a
moving branch.

Checklist before opening the merge request:

1. `gradle/wrapper/gradle-wrapper.properties` points to a released Gradle version.
2. `AndroidManifest.xml` contains no proprietary components or tracking SDKs.
3. No `INTERNET` traffic other than OpenStreetMap tiles and the optional,
   self-hostable family backend. The backend source is in `backend/`, uses
   HTTPS-only app URLs, has no browser CORS, applies rate limits, caps groups at
   20 cars and deletes stale location data; family mode is off by default and
   uploads only the latest spot. New groups are end-to-end encrypted: the
   Worker stores only ciphertext for the car label and coordinates.
4. `fastlane` metadata exists in English and Polish; changelog for the new
   `versionCode` is present.
5. The GitHub release contains `Parked-<version>.apk` and its `.sha256` file.
6. `AllowedAPKSigningKeys` matches the certificate printed by
   `apksigner verify --print-certs Parked-<version>.apk`.
7. The tag is pushed and the release workflow finished successfully.
8. Every `package.json` has a committed lockfile; F-Droid's scanner rejects the
   build otherwise. The Worker lockfile is `backend/worker/package-lock.json`.

## Release procedure

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/{en-US,pl-PL}/changelogs/<versionCode>.txt`.
3. Regenerate screenshots if the UI changed, commit them.
4. Commit, push `main` and wait for the Android CI job.
5. Tag the commit: `git tag v<versionName> && git push origin v<versionName>`.
6. The release workflow builds, signs, verifies (including `apksigcopier`) and
   publishes `Parked-<versionName>.apk` on GitHub Releases.
7. Verify locally:
   ```bash
   sha256sum -c Parked-<version>.apk.sha256
   apksigner verify --print-certs Parked-<version>.apk
   ```
8. Add a new `Builds` entry to the fdroiddata metadata with the full commit SHA,
   `versionName`, `versionCode` and bump `CurrentVersion` / `CurrentVersionCode`.
9. Open a merge request against
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata) with the updated
   `metadata/com.bartixxx.parked.yml`.

## After the first inclusion

New versions are detected through `UpdateCheckMode: Tags` and
`AutoUpdateMode: Version`. F-Droid picks up the new tag, rebuilds it, verifies the
signature and publishes the update. If a build fails only on F-Droid, the build log
in the merge request / pipeline shows the Gradle error; common causes are a missing
SDK component or a dependency that is not in a public repository.

## Useful links

- F-Droid inclusion policy: https://f-droid.org/docs/Inclusion_Policy/
- Reproducible builds: https://f-droid.org/docs/Reproducible_Builds/
- Metadata reference: https://f-droid.org/docs/Build_Metadata_Reference/
- Fastlane metadata: https://f-droid.org/docs/All_About_Descriptions_Translations_and_Screenshots/
