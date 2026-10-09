# BATCHES.md — Reader build plan

Five batches, in order. Each is a complete delivery. Do not skip ahead.

versionCode starts at 1 (v1 was unsigned by us — this is our first build). Every batch bumps by 1. All builds share the cert 1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953.

**One-time note:** the current phone install is signed with a different key. First batch of this repo requires `adb uninstall app.reader` before install. Everything after is `adb install -r`.

---

## Batch 1 — Rebuild baseline

**Goal:** prove the source builds, signs with the shared key, and installs on the phone.

**Scope:**
- Build the v1 source as-is. No feature changes.
- versionCode 1, versionName 1.0.0
- Sign with the shared cert
- Install on the client's phone (one uninstall first, then install)

**Verify:** app opens, all v1 features still work — open a TXT, play, pause, skip, bookmark, background playback.

**Why first:** establishes the build+sign+install pipeline before we add anything. If this fails, nothing else can ship.

---

## Batch 2 — PDF, EPUB, DOCX

**Goal:** the big format gap. This is the client's #1 priority.

**Scope:**
- PDF text extraction for text-based PDFs (born-digital)
- Graceful message when a PDF is scanned images only — tell the user OCR comes next
- EPUB reading (chapters, table of contents, images inline)
- DOCX reading (plain text extraction, paragraph structure)
- Markdown rendering (headers, lists, bold/italic/code)
- File picker accepts `.pdf`, `.epub`, `.docx`, `.md` in addition to `.txt`/`.html`

**Small PDF reliability test:** open a 50 MB PDF, a 200-page PDF, a 500-page PDF. All three must load, scroll, and read without crashing. The current v1 chokes on large PDFs — this batch fixes that.

**Verify:** open one of each format from the phone. Read aloud. Scroll. Resume position after kill.

---

## Batch 3 — Voices and cloud TTS

**Goal:** the client's #2 priority — more voices, better voice quality.

**Scope:**
- Full voice picker: list every system TTS voice, language, engine
- Per-voice settings: default speed, pitch, language
- Cloud TTS providers, each behind explicit opt-in in settings with an API key field:
  - OpenAI TTS
  - Microsoft Azure Speech
  - Amazon Polly
  - Google Cloud TTS
  - ElevenLabs
  - Custom OpenAI-compatible endpoint (URL + key + model name)
- Cache cloud audio locally so re-reading doesn't re-hit the API
- Clear indication in the UI of which provider is active

**Network permission:** this batch adds INTERNET. That is expected here and only here. Every other part of the app remains offline.

**Verify:** pick a cloud provider, paste a key, hear the new voice. Switch back to system voice. Both work.

---

## Batch 4 — OCR, dictionary, translate

**Goal:** the language and accessibility features.

**Scope:**
- OCR for image-only PDFs (ML Kit Text Recognition, on-device)
- Dictionary lookup on long-press (offline dictionary bundle)
- Wikipedia lookup on long-press
- Web search on long-press
- Google Translate on selected text (on-device ML Kit or cloud, user's choice)
- Bilingual view: original and translated side by side
- Separate voice for translations

**Verify:** scan a paper page, read it aloud. Long-press a word, get a definition. Translate a paragraph, both versions visible.

---

## Batch 5 — Polish and extras

**Goal:** close every remaining gap.

**Scope:**
- Word-level highlight toggle (in addition to sentence-level)
- Auto-scroll to the spoken word
- Tap-a-sentence to jump
- Sleep timer
- Search inside current document
- Font size, line spacing, reading themes (paper / night / sepia)
- Selected-text bookmarks (in addition to position bookmarks)
- Full reading list queue with auto-advance
- Custom pronunciation rules editor
- Dialog enhancement (voice per speaker)
- Export translated text as HTML
- Export audio as MP3
- Home screen widget (current reading, tap to resume)
- MOBI / AZW3 / PRC / RTF / ODT

**Verify:** every item in SPEC.md is either shipped or marked deferred with a reason. Nothing left unclassified.

---

## Rules for every batch

- versionCode increments by exactly 1.
- Cert is 1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953.
- One zip per batch. Structure in ACCEPTANCE.md.
- Every ✅ claim has a screenshot in docs/verify/.
- Every deferred item has a reason in CHANGES.md.
- Five verification commands, raw output in README.md.
- `adb install -r` after the first build. Never uninstall without asking.

## Current batch

**Batch 1 — Rebuild baseline.** Awaiting delivery.