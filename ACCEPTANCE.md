# ACCEPTANCE.md

The exact test the client runs on your delivery. Build to pass it.

## Before shipping

Run all five commands. If any fails, fix and rerun from the top.

    ./gradlew clean testReleaseUnitTest assembleRelease
    aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
    apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
    sha256sum app/build/outputs/apk/release/app-release.apk
    python3 verify_apk.py app/build/outputs/apk/release/app-release.apk

Paste the raw output of all five into README.md inside the zip. Not a summary.

## Target values

- `aapt2` line must show the versionCode and versionName stated in the batch file.
- `apksigner` line must show cert SHA-256 `1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953`.
- `sha256sum` must differ from the previous batch's APK hash (the batch file states the previous hash).

## What the client runs

    adb install -r reader.apk
    adb shell am force-stop app.reader
    adb shell monkey -p app.reader -c android.intent.category.LAUNCHER 1
    adb shell dumpsys package app.reader | Select-String "versionCode|versionName"

Install must print Success. Version must match the batch file. App must launch.

## What the client checks

1. Every feature listed in CHANGES.md with a green check is present and works on the phone.
2. Every green check has a matching screenshot in `docs/verify/`.
3. No crash during a 5-minute session of normal use.
4. Existing bookmarks, reading progress, and settings are preserved after the `-r` install.

## Delivery zip structure

    reader-batch-N.zip
    ├── reader.apk
    ├── SHA256SUMS.txt
    ├── README.md          five verification outputs, raw
    ├── CHANGES.md         three-column table: Item | Status | Evidence
    ├── docs/
    │   ├── BUILD.md
    │   └── verify/        one screenshot per shipped feature
    └── source/            Kotlin source tree, no build artifacts, no keystore, no local.properties

## Red flags that will get the delivery rejected

- `INSTALL_FAILED_VERSION_DOWNGRADE` — versionCode too low
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — wrong signing cert
- App crashes on launch
- Missing screenshots for claimed features
- APK hash matches a previous delivery
- CHANGES.md claims features not in the APK
- Real client files or personal data inside the zip

## Installing on the client's phone

The client has an older build signed with a different key. The very first delivery of this repo will need one `adb uninstall app.reader` before installing. After that, always `-r`.