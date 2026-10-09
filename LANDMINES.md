# LANDMINES.md

Traps that have already burned us. Read before touching anything.

## Signing and install

- **Never `adb uninstall` unless the client has approved it.** Uninstall wipes the app's data directory — bookmarks, reading progress, everything. The first build of this repo will require one uninstall (cert change from the old custom key to the shared uber-apk-signer key). After that, always `adb install -r`.
- **The signing cert SHA-256 must be `1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953` for every build.** That is uber-apk-signer's embedded debug key. If a build ships with a different cert, the client's next `install -r` fails and they must uninstall.
- **versionCode must monotonically increase.** A build with a lower versionCode than what is installed is rejected by Android.

## App package and identity

- **Package name is `app.reader`.** Never change it. Changing it makes Android treat the app as a new install and abandons all existing data.
- **Application ID for debug builds is `app.reader.debug`.** It coexists with release. Do not install debug over release — they are different apps.

## Personal data

- **Never commit the client's real name, email, phone, address, or any personal identifier** in code, comments, docs, commits, CHANGES.md, or screenshots.
- **Never commit real client files** — text they opened, PDFs they read, bookmarks they made. Test fixtures are synthetic: "Test User", fake titles, fake paragraphs.
- **The keystore lives outside the repo.** Only the public debug key from uber-apk-signer is used in this project.

## Build and repack

- **Do not use .NET ZipFile or PowerShell `[IO.Compression.ZipFile]` to edit APKs.** It corrupts `classes.dex` on some builds; the app then crashes on launch with `Failed to extract classes.dex: Inconsistent information`.
- **Do not edit `source/local.properties` or commit it.** It contains machine-specific SDK paths; it is in .gitignore.
- **Do not commit build artifacts.** `source/build/`, `source/.gradle/`, `source/**/build/`, `source/.kotlin/` are all ignored. If they appear in a delivery zip, remove before committing.

## Delivery format

- Every batch ships as one zip. Structure is defined in ACCEPTANCE.md.
- Every claimed feature has a screenshot in `docs/verify/` inside the zip.
- Every deferred item is marked in CHANGES.md with a reason.
- Before shipping, run the five verification commands and paste the raw output in README.md.