# BATCHES.md — Reader build plan

Twenty batches across seven phases. Each is a complete delivery. versionCode increments by exactly 1 per batch. Cert always 1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953.

Before writing code for any batch: read FEATURE-MAP.md for the IDs in scope, and EXPERT-MAP.md to pick the experts closest to the problem. Consult two or three experts before deciding the approach. Then build.

One-time note: the current phone install was signed with a different key. The very first batch of this repo required one adb uninstall. Every batch since uses adb install -r.

---

## Phase 1 — Foundation (Batches 1–3)

---

## Batch 1 — Rebuild baseline

Delivered.

---

## Batch 2 — Large-PDF streaming + progress UI

IDs: PDF-08, RD-10, X-13
Experts: 22 (PDF Streaming), 79 (Large-File Memory), 2 (Compose Perf)

The problem: opening a large PDF looks stuck. No feedback, no progress, and the UI blocks.

Scope:
- Parse a PDF page-by-page on a background thread. Never block the main thread.
- While parsing, show a progress overlay: Page X of Y, characters counted so far, a percentage bar filling 0 to 100.
- Progress survives if the user backgrounds the app mid-parse — resume from the last completed page.
- Parse result feeds the reader as it becomes available — first page readable before the whole file finishes.
- Reader position bar at bottom reflects parse progress while parsing, and read progress after.

Verify: open a 500-page PDF. Progress appears within 1 second. Bar fills smoothly. First page readable before parse completes. No ANR. Kill app at 40% — reopen — resumes near 40%.

---

## Batch 3 — TTS & voice configuration (shipped)

IDs: TTS-01, TTS-04, TTS-05, TTS-06, VOICE-01, VOICE-02, VOICE-03, VOICE-04, PB-05, PB-06, PB-07, PB-08, SET-03, INT-10
Experts: 20 (TTS Engine Integration), 21 (Custom TTS Author), 22 (Audio Focus/Ducking)

versionCode 3, versionName 1.0.2, hash B5AFB119. Shipped.

Scope (shipped):
- Voice picker — searchable list, grouped by language and engine
- TTS engine picker — Google, Samsung, any installed engine
- Missing-voice prompt — Play Store link + alternative voice
- Voice sliders — rate 0.5x–3.0x, pitch, volume independent of media
- Expanded playback bar — speed/pitch/volume with live effect
- Settings Voice group, Advanced subsection, Cloud voices subsection
- Encrypted cloud API key storage (INT-10, wired in Batch 8)

Note: the reader-core-polish IDs originally planned for this slot
(RD-01, RD-02, RD-05, RD-08..RD-10, LIB-01..LIB-03, LIB-05, LIB-07,
ADD-01..ADD-04, PB-01..PB-03, PB-11, PB-13, PB-14, BM-01..BM-04,
SET-01, SET-07, SET-17, THEME-01, THEME-04, THEME-07, DEV-02,
DEV-06, INT-05, INT-06) are re-slotted into later batches. See the
Phase 2 and Phase 5 sections where those IDs now appear.

---

## Phase 2 — Formats Deep (Batches 4–6)

---

## Batch 4 — PDF companion + TOC + OCR hooks

IDs: PDF-01, PDF-02, PDF-03, PDF-06, PDF-07, RD-11, RD-23
Experts: 21 (PDF Text), 22 (PDF Streaming), 23 (OCR), 24 (CV Preprocessing)

Scope:
- Chapter / TOC detection from PDF bookmarks and outline
- Companion viewer: split-screen or toggle between extracted text and original PDF page
- Tap a spoken sentence → jump to the corresponding PDF page and area
- OCR button on the reader toolbar — triggers on-device ML Kit OCR for scanned pages
- Preprocessing before OCR: deskew, denoise, threshold

Verify: open a text PDF with chapters. Tap a sentence — companion opens at the right page. Open a scanned PDF — OCR button appears, tap → text extracted and readable.

---

## Batch 5 — EPUB, DOCX, Markdown, encoding

IDs: FMT-02, FMT-05, FMT-07, FMT-08, RD-18, RD-20..RD-22, SET-09, SET-11, SET-13, TTS-10
Experts: 25 (EPUB), 27 (DOCX), 29 (HTML/MD), 30 (Encoding)

Scope:
- EPUB parser with chapters, TOC, inline images
- DOCX parser with paragraph structure
- Markdown renderer (headers, lists, bold/italic/code)
- File picker accepts .epub, .docx, .md alongside .txt, .html, .pdf
- Encoding detection with manual override in reader menu
- Show / hide images toggle
- Show / hide links toggle
- Preserve original styles toggle for HTML

Verify: open one EPUB, one DOCX, one MD. All read aloud. Images render (if enabled). Chapters navigable. Encoding override works on a legacy file.

---

## Batch 6 — MOBI, AZW3, PRC, RTF, ODT, WhatsApp chat

IDs: FMT-03, FMT-04, FMT-06, FMT-09, FMT-10
Experts: 26 (MOBI), 28 (RTF/ODT), 31 (WhatsApp), 32 (DRM/LCP)

Scope:
- MOBI / AZW3 / PRC parsing (DRM-free only)
- FB2 parsing
- RTF and ODT parsing
- WhatsApp chat export parsing (structured, per-sender voices possible)
- LCP DRM support for purchased ebooks (Readium library)

Verify: open one of each format. All read aloud. DRM-free Kindle books work. DRM-protected book shows a clear message.

---

## Phase 3 — Voices (Batches 7–9)

---

## Batch 7 — Full voice picker + engine selection

IDs: TTS-01, TTS-04..TTS-06, VOICE-01..VOICE-04, PB-05..PB-08, SET-03, INT-10
Experts: 13 (TTS Integration), 14 (Custom Engine), 16 (SSML Prosody), 6 (DataStore)

Scope:
- Voice picker: searchable list, grouped by language and engine
- TTS engine picker: Google, Samsung, any installed engine
- Missing-voice prompt with link to install
- Default rate, pitch, volume in Settings
- Rate range (min/max) and step size
- Playback bar expand: speed slider, pitch slider, volume slider
- API key fields for cloud providers (saved but unused until Batch 8)

Verify: pick a non-default system voice. Playback uses it. Adjust rate — takes effect immediately. Change engine — persists across restart.

---

## Batch 8 — Cloud TTS providers

IDs: TTS-02, VOICE-07, VOICE-08, INT-10, PB-05..PB-07
Experts: 15 (Cloud TTS), 54 (Encryption), 4 (Coroutines), 70 (Audio Latency)

Scope:
- Cloud TTS providers, each behind explicit opt-in and an API key field:
  - OpenAI TTS
  - Microsoft Azure Speech
  - Amazon Polly
  - Google Cloud TTS / WaveNet
  - ElevenLabs
  - Custom OpenAI-compatible endpoint (URL + key + model)
- Cache cloud audio locally so re-reading does not re-hit the API
- Per-document voice override
- Named voice sets (save / load configurations)
- Clear indication in playback bar which provider is active

Verify: pick a provider, paste key, hear voice. Switch back to system voice. Both work. Cache hit avoids network on second play.

---

## Batch 9 — Pronunciation editor + dialog voices

IDs: TTS-07, TTS-08, TTS-11, TTS-12, VOICE-05, VOICE-06, DLG-01..DLG-08, SET-04
Experts: 18 (Pronunciation), 19 (Dialog Detection), 16 (SSML), 51 (Dropbox sync)

Scope:
- Pronunciation rule editor with regex patterns and test preview
- Roman numeral handling (convert or leave)
- Spoken-text preview (debug view showing post-replacement text)
- Voice per language mapping
- Dialog detection wizard: scans quote styles, assigns per-speaker voices
- Named voice groups, import from book, export as shareable file
- Apply dialog setup to a whole reading list
- Sync rules via Dropbox (optional)

Verify: add a rule like Dr to Doctor, hear it applied. Open a book with dialogue — wizard detects quotes, assigns two voices, both audible when played back.

---

## Phase 4 — Language & Study (Batches 10–12)

---

## Batch 10 — On-device translation + bilingual view

IDs: LANG-01..LANG-06, LANG-12, LANG-13, SET-08
Experts: 39 (On-Device ML Kit), 41 (Bilingual UX), 44 (Tokenizer), 43 (i18n)

Scope:
- Translate current article with on-device ML Kit
- Translate a selection from the reader long-press menu
- Source and target language picker
- Auto-detect source language
- Bilingual view: original and translation side by side, interleaved
- Bilingual order toggle (original-first or translated-first)
- Save translation as HTML export
- Remove translation and return to original

Verify: open a Spanish article. Translate to English. Bilingual view shows both. Read aloud works in either language. Save HTML export works.

---

## Batch 11 — Separate voices + reading both languages

IDs: LANG-07..LANG-11, LANG-14..LANG-16, VOICE-06
Experts: 42 (Multilingual TTS), 44 (Tokenizer), 64 (Screen Reader)

Scope:
- Separate voice per language (source and target)
- Read both languages sequentially: full sentence in each
- One-language mode: both visible, only the active language spoken
- Tune voices separately: rate, pitch, volume per language
- Study language switch with back/forward gestures
- Favourite languages quick switch
- Embedded Google Translate widget option (network)
- TalkBack-aware translation widget

Verify: bilingual Spanish-English. Playback reads Spanish sentence then English. Switch to one-language mode — only current language spoken. Back gesture flips primary.

---

## Batch 12 — Reference lookups + AI handoff

IDs: REF-01..REF-05, AI-01..AI-04, RD-14
Experts: 45 (Offline Dictionary), 46 (Wikipedia), 47 (Web Search), 48 (Selection Popup), 49 (LLM APIs), 50 (Prompt UX)

Scope:
- Long-press selection popup with sources: Dictionary, Wikipedia, Google Translate, Web Search
- Offline dictionary (bundled or downloadable)
- Compare results: all sources in one sheet with tabs
- Send to ChatGPT with a saved prompt
- Send to Claude with a saved prompt
- Send to Gemini with a saved prompt
- Saved prompts manager with named prompts

Verify: long-press a word — popup appears with 4 sources. Tap Dictionary — offline definition. Tap Wikipedia — excerpt. Send a paragraph to ChatGPT — opens with the saved prompt and the text.

---

## Phase 5 — Polish (Batches 13–17)

---

## Batch 13 — Highlight modes + auto-scroll + gestures

IDs: RD-03, RD-04, RD-06, RD-07, RD-12, RD-15..RD-17, RD-19, SET-02, SET-05, SET-06, SET-12
Experts: 58 (Highlighting), 59 (Auto-Scroll), 61 (Gesture Design), 2 (Compose Perf)

Scope:
- Word-level highlight mode (in addition to sentence-level)
- Auto-scroll to keep the spoken word visible
- Page / scroll / two-page reading modes
- Brightness swipe on left edge
- Tap-a-sentence to jump (already in Batch 3, verify still works)
- Sync-scroll toggle (per-document)
- Alt-text reading toggle
- Highlight mode radio in Settings
- Auto-scroll toggle in Settings
- Reading mode radio in Settings

Verify: switch to word-level highlight — the current word is highlighted as it is spoken. Switch to scroll mode — view follows. Tap the left edge and swipe — brightness changes.

---

## Batch 14 — Queue, reading lists, search

IDs: QUEUE-01..QUEUE-07, SRCH-01..SRCH-03, LIB-04, LIB-06, PB-04
Experts: 87 (Queue Modeler), 86 (SQLite FTS5), 61 (Gesture Design)

Scope:
- Multiple reading lists (News / Books / Study / Work)
- Per-item progress bar
- Auto-advance to next item
- Reorder queue by drag
- Clear finished items
- Multi-URL import (bulk add)
- Search inside current document with highlight-all and jump-to-hit
- Library filter chips (All / Books / Articles / Audio)
- Library multi-select for bulk actions
- Prev / next paragraph on playback bar long-press

Verify: create a queue with 5 items. Auto-advance works. Search inside a document — hits highlighted, tap to jump. Multi-select 3 items — delete all at once.

---

## Batch 15 — Themes, fonts, accessibility

IDs: THEME-02, THEME-03, THEME-05, THEME-06, THEME-08..THEME-10, ACC-01..ACC-07, SET-14, X-01..X-12
Experts: 62 (WCAG Auditor), 63 (Dyslexia), 64 (Screen Reader), 65 (Low-Vision), 56 (Typography), 3 (State)

Scope:
- Font choice: serif / sans / mono / system / OpenDyslexic
- Boldify (bionic emphasis on word heads)
- Line height slider
- Text alignment radio
- Paper / Night / Sepia presets
- One-page / two-page mode toggle
- Page-turn effect options
- TalkBack support on every screen
- 48 dp minimum tap targets everywhere
- Contrast ratio WCAG AA
- Explore-by-touch works
- RTL layout mode
- Vertical text (CJK)
- Reduced motion respected

Verify: enable OpenDyslexic — reader text switches. Enable TalkBack — every interactive element announced. Switch to sepia preset — whole reader recolours.

---

## Batch 16 — Playback behaviour + device integration

IDs: PLAY-01..PLAY-11, DEV-01, DEV-03..DEV-05, DEV-07..DEV-09
Experts: 60 (Sleep Timer), 68 (AVRCP), 67 (Ducking), 11 (Widget), 12 (Bluetooth)

Scope:
- Auto-start on open / on share-in
- Pause on notification (duck or pause)
- Sleep timer with shake-to-restart
- Auto-advance between queue items
- Repeat each sentence 1–5 times
- Extra pauses between sentences / paragraphs / articles
- Article transition announcement (gong / spoken / none)
- Headset auto-play on connect, auto-stop on disconnect
- Hardware button bindings (play / pause / skip / bookmark)
- Bluetooth quirks (reverse buttons, ignore first play, fade workaround)
- Home-screen widget (current reading, tap to resume)
- E-ink mode toggle
- Hide cutout in full-screen
- Background music ducking

Verify: set sleep timer to 5 minutes — playback stops. Shake to restart. Connect Bluetooth headset — playback starts. Disconnect — stops.

---

## Batch 17 — Recording + export

IDs: REC-01..REC-06, PB-10, PB-12, LANG-12
Experts: 20 (Audio Recording), 8 (Media3), 5 (Room)

Scope:
- Record-on-demand (red dot on playback bar)
- MP3 export
- OGG export
- WAV export (uncompressed)
- Continue recording across reading list items
- Continue recording across book chapters
- Share current text as file or text

Verify: press record — file grows. Stop — MP3 saved. Open in a player — audio matches speech. Enable continue-across-list — one file covers multiple articles.

---

## Phase 6 — Sync & Integration (Batches 18–19)

---

## Batch 18 — Dropbox + Drive sync

IDs: INT-01, INT-02, INT-07, INT-08, BM-05, LIB-08, TTS-12, SET-16
Experts: 51 (Dropbox), 52 (Drive), 53 (Conflict Resolution), 54 (Encryption)

Scope:
- Dropbox sync: OAuth connect, queue upload/download, rules upload/download
- Google Drive sync: same shape as Dropbox
- Sync status indicator in Library header
- Auto-add new Dropbox files
- Bookmark sync across devices
- Conflict resolution (last-write, dedupe by content hash)
- Sync pronunciation rules

Verify: connect Dropbox. Add item on device A — appears on device B within 30 seconds. Add bookmark on A — appears on B. Disconnect — sync stops, no crash.

---

## Batch 19 — Clipboard handoff + browser extensions + cloud picker

IDs: ADD-05, ADD-06, INT-03, INT-04, INT-09, WEB-09
Experts: 34 (Web Scraping), 51 (Dropbox), 4 (Coroutines)

Scope:
- Clipboard watch: chip when new text copied
- Multi-URL import from a text block
- Browser extension bridge (receive from Chrome / Edge / Brave / Firefox)
- Monitor Downloads folder for new files
- Cloud-backed document picker (Dropbox, Drive)

Verify: copy a URL on the phone — chip appears. Tap it — Reader opens URL. Send a page from the desktop browser extension — appears in Reader within a minute.

---

## Phase 7 — Advanced Web (Batch 20)

---

## Batch 20 — Full web reader + serial downloader

IDs: WEB-01, WEB-03..WEB-07, WEB-10, RD-21, RD-22
Experts: 33 (Readability), 34 (Ksoup), 35 (WebView), 36 (Ad Blocking), 37 (Serial Downloader), 73 (Network Security)

Scope:
- URL fetch with smart article extraction (already in Batch 3, extend here)
- Ad stripping on fetched pages
- Full-page mode (load entire page, not just article)
- Browser mode (WebView with JS, login, interaction)
- Multi-part detection: article has next-link — banner shows Part N of M
- Serial downloader: follow next-links, merge into one document
- Stop-and-keep on serial download interruption
- Vertical text rendering (CJK)
- Article extraction toggle in reader
- Original styles toggle in reader

Verify: fetch a 10-part web serial — all chapters merged into one document. Fetch an ad-heavy news article — ads stripped, clean text reads aloud. Open a JS-required page — browser mode loads it.

---

## Cross-project design note

See DESIGN-PARITY.md — Pro's UI/UX decisions that we may borrow for Reader. Reader is a text-first app; Pro is a data-dense app. Some of Pro's patterns translate (card layout, quiet nav, dark theme tokens, haptics on tap, notification style). Others do not (heavy dashboards, chart-heavy screens). Read DESIGN-PARITY.md before Batches 3, 13, and 15 to decide what carries over.

---

## Rules for every batch

- versionCode increments by exactly 1.
- Cert is 1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953.
- One zip per batch. Structure in ACCEPTANCE.md.
- Every claimed feature has a screenshot in docs/verify/.
- Every deferred item has a reason in CHANGES.md.
- Five verification commands, raw output in README.md.
- adb install -r after the very first delivery. Never uninstall without asking.
- Consult 2–3 experts from EXPERT-MAP.md before deciding approach.

## Current batch

**Batch 2 — Large-PDF streaming + progress UI.** Awaiting delivery.
