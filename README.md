# Reader — native Android TTS reader

Personal-use text-to-speech reader for Android. A clean rebuild of @Voice Aloud Reader without the ads, trackers, license checks, or anti-tamper.

**Package:** app.reader
**Min SDK:** 26 (Android 8.0)
**Target SDK:** 34 (Android 14)
**Language:** Kotlin + Jetpack Compose + Material 3

## What is in this repo

| Path | What it is |
|---|---|
| source/ | Current v1 source tree (working APK on the client's phone) |
| baseline/reader-v1-release.apk | The APK currently installed on the client's phone |
| .gitignore | Build artifacts, keystores, local config — excluded |

## The signing change — read this before installing

The current APK on the client's phone was signed with a custom keystore that is no longer in this repo. This repo will use the shared uber-apk-signer debug key from here forward. That means:

- **First install of the next build requires `adb uninstall app.reader`** — this wipes the app's saved bookmarks and reading progress (minor, acceptable for this app).
- **Every batch after that installs with `adb install -r`** — no data loss.

## Current state — v1 (installed)

What works:
- Open TXT / HTML via SAF file picker
- Paste text from clipboard
- Share text from another app
- System TTS with play / pause / skip sentence / skip paragraph
- Speed and pitch sliders
- Sentence highlighting synced with speech
- Background playback with notification + lock-screen controls
- Last-position resume per document
- Bookmarks and reading list
- Dark mode

Permissions requested: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK, POST_NOTIFICATIONS. Nothing else.

## What is missing (the parity gap with @Voice)

Content formats: PDF, EPUB, MOBI, DOCX, RTF, Markdown.
TTS: Cloud providers (OpenAI, Azure, Polly, Google WaveNet, ElevenLabs, custom OpenAI-compatible), per-voice settings, pronunciation rules.
Reading: Word-level highlight, auto-scroll, sleep timer, search in text, in-text dictionary, translate, bilingual view.
Other: OCR for image-only PDFs, per-document voice override, speech editor.

Full list in SPEC.md. Build order in BATCHES.md.

## Read order for anyone working on this

1. WORKING-STYLE.md — how we work together
2. LANDMINES.md — traps that have already burned us
3. SPEC.md — what full parity looks like
4. BATCHES.md — the plan, in order
5. ACCEPTANCE.md — how a delivery is judged
6. source/ — the current code

---

## License

Proprietary. Personal, non-commercial use only. See [`LICENSE`](./LICENSE)
for the full terms. Commercial use is prohibited without prior written
permission.