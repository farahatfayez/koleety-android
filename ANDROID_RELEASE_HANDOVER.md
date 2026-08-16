# KOLEETY AI Android — Safe Rebuild Handover

## Purpose

This branch prepares a **test-only rebuild** of the working TWA baseline under the new identity `com.koleety.ai`. It does not publish to Google Play and it does not replace `com.mycollegeai.app`.

## Locked scope

The TWA behavior and its dependency version remain the same as the baseline. The permitted changes are limited to:

| Area | Value |
| --- | --- |
| Application ID / namespace | `com.koleety.ai` |
| Display name | `KOLEETY AI` |
| Default and deep-link domain | `koleety.com` and `www.koleety.com` |
| Target / compile SDK | 36 |
| Candidate version | `1.0.20` (`versionCode 21`) |
| Launcher assets | Existing KOLEETY icon assets only |

## Verified local result

`./gradlew assembleDebug` completed successfully with JDK 17 and Android SDK 36. The resulting debug APK identifies itself as `com.koleety.ai.debug`, targets SDK 36, and has the app label KOLEETY AI. A debug APK is not a Google Play release artifact.

## Signing policy

The signing key is intentionally **not** stored in the repository or build fallback values. Keep the keystore and all credentials in an owner-controlled secure vault. For a local signed release, supply all four environment variables:

```text
KEYSTORE_PATH
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

The GitHub workflow expects the corresponding repository secrets, including the base64-encoded keystore. It may build artifacts only when manually dispatched. It does not upload to Google Play and does not create a public GitHub release.

> Never use an action that exports a keystore or its passwords as a downloadable artifact. Preserving the key means preserving it in a secure owner-controlled vault, not exposing it in code or artifacts.

## Required release gates

1. Verify that `https://koleety.com/.well-known/assetlinks.json` contains only `com.koleety.ai` with the current Play App Signing fingerprint.
2. Build a signed AAB with the preserved upload key.
3. Upload the AAB to **Internal testing only**.
4. Install from the tester link and confirm repeated cold starts, deep links, sign-in, image upload, and primary study flows on a physical Android device.
5. Keep `com.mycollegeai.app` available until the new app completes physical-device validation and a separate owner decision authorizes any transition.
6. Do not upload to production before the physical-device gate passes.
