# SPEC.md — feature parity with @Voice Aloud Reader

The reference is @Voice Aloud Reader by Hyperionics. Every feature in it that is not in Reader is a gap. The batch list in BATCHES.md closes gaps in order of client priority.

## What @Voice has today

### Content formats
- Plain text (TXT)
- HTML and web pages (built-in reader strips ads and clutter)
- PDF (text extraction; OCR fallback for scanned pages)
- EPUB, AZW3, MOBI, PRC
- DOC, DOCX, RTF, ODT
- Markdown
- WhatsApp chat exports (structured reading)

### Text-to-speech
- System TTS engines — any installed on the device
- Cloud TTS: Google WaveNet, Amazon Polly, Microsoft Azure (bring your own key)
- Voice per speaker (change voice for dialogue in novels)
- Custom pronunciation rules (name pronunciation, abbreviations)
- Dialog enhancement (audiobook-style dialogue voice)

### Reading
- Sentence-level highlight synced to speech
- Word-level highlight option
- Auto-scroll following the spoken word
- Tap a sentence to jump there
- Reading lists and queues (auto-advance through a queue)
- Bookmarks: manual + selected-text bookmarks
- Search inside current document
- Sleep timer
- Background reading with lock-screen and notification controls
- Resume where you left off, per document
- Adjustable font size, line spacing, background (paper / night / sepia)

### Language tools
- Google Translate integration (translate on the fly)
- Bilingual view (original and translation side by side)
- Separate voices for source and translated text
- Dictionary lookup on long-press
- Wikipedia lookup on long-press
- Web search on long-press

### Extras
- Export translated results as HTML
- Export audio as MP3 (using TTS engine)
- Handle shared text from any app
- Widget for quick access to current reading

## What Reader v1 has

- TXT and HTML file picker
- Paste text
- Share-received text
- System TTS only
- Sentence highlighting synced to speech
- Play / pause / skip sentence / skip paragraph
- Speed and pitch sliders
- Background playback with lock-screen and notification controls
- Resume per document
- Bookmarks and a basic reading list
- Dark mode

## The gap (all of these are what we build)

Grouped by category, not by priority. Priority order is in BATCHES.md.

### Formats
- PDF text extraction
- PDF OCR fallback for scanned pages
- EPUB
- MOBI / AZW3 / PRC
- DOC / DOCX / RTF / ODT
- Markdown
- WhatsApp chat export parsing

### TTS
- Cloud providers: OpenAI, Azure, Polly, Google WaveNet, ElevenLabs, custom OpenAI-compatible endpoint
- Per-voice settings (default voice, default speed per voice)
- Custom pronunciation rules editor
- Dialog enhancement (voice per speaker)

### Reading
- Word-level highlight toggle
- Auto-scroll to the spoken word
- Tap-a-sentence to jump
- Sleep timer
- Search inside current document
- Adjustable font size, line spacing, background themes (paper / night / sepia)
- Full reading list with auto-advance through queue
- Selected-text bookmarks

### Language
- Google Translate integration
- Bilingual view
- Separate voice for translations
- Dictionary lookup on long-press
- Wikipedia lookup on long-press
- Web search on long-press

### Extras
- Export translated text as HTML
- Export audio as MP3
- Home screen widget

## What we deliberately do not build

@Voice ships with:
- Ad SDKs (AppLovin, AdMob, Unity, BidMachine, Vungle) — skip
- Firebase analytics and crash reporting — skip
- LIAPP anti-tamper (native library, encrypted asset, signature check) — skip
- License verification — skip
- Paywall / subscription / premium unlock — skip
- Any form of telemetry — skip

Reader is offline, ad-free, telemetry-free by design. Never add any of the above.

## Priority order

Defined in BATCHES.md. Client priorities, high to low:

1. PDF reliability (the current blocker)
2. More voices (system + cloud)
3. Cloud TTS providers
4. OCR
5. Dictionary + translate
6. Everything else