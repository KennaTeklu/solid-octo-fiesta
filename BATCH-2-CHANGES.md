# CHANGES.md — Batch 2

Three-column table: Item | Status | Evidence.

| Item | Status | Evidence |
|---|---|---|
| **PDF-08** Page-by-page PDF streaming on a background thread | ✅ shipped | `parser/PdfStreamingParser.kt` — opens `PDDocument` once, iterates pages with `PDFTextStripper.startPage/endPage`, runs on `Dispatchers.IO`. Never touches the main thread. |
| **PDF-08** First page readable before the whole file finishes | ✅ shipped | `PdfStreamingParser.emit()` builds a partial `ParsedDocument` after every page; `ReaderViewModel.feedPartial()` swaps it into the live `_doc` flow, so the reader body updates as each page arrives. |
| **PDF-08** Progress throttled to ~12 Hz to avoid jank on 500-page files | ✅ shipped | `PdfStreamingParser.emit()` skips emissions that fall within 80 ms of the previous one (except the final one). |
| **RD-10** Progress overlay with three live numbers (Page X of Y, chars counted, % bar) | ✅ shipped | `ui/reader/ProgressOverlay.kt` — `LinearProgressIndicator(progress = { animatedPercent })`, animated bar fill, three live text fields. See `docs/verify/01-progress-overlay.png` and `docs/verify/02-mid-parse-partial.png`. |
| **RD-10** Overlay does not block the reader body once the first page is ready | ✅ shipped | `ReaderScreen` keeps the reader body interactive underneath the overlay (`Box` z-order). `docs/verify/02-mid-parse-partial.png` shows partial text + overlay together. |
| **X-13** Progress survives app being killed — resume from last completed page | ✅ shipped | `PdfStreamingParser.runParse()` appends one JSON line per page to `pdf-progress/<docId>.jsonl`. On reopen, `runParse(resumeFromSidecar=true)` reads the sidecar, replays the persisted pages, jumps the bar to the persisted percentage, and continues parsing the remaining pages. See `docs/verify/04-resumed-after-kill.png`. |
| **X-13** Sidecar cleaned up after a complete parse | ✅ shipped | `ReaderViewModel` calls `PdfStreamingParser.clearSidecar(docId)` when the state reaches `Done`. |
| **X-13** Sidecar removed when document is deleted | ✅ shipped | `DocumentRepository.deleteDocument()` also deletes the sidecar. |
| Bottom bar reflects parse progress while parsing | ✅ shipped | `ui/reader/PlaybackBar.kt` — when `pdfLoading != null`, counter shows `Page X/Y` and bar shows parse %. Otherwise shows sentence done/total. |
| Bottom bar reflects reading progress after parse completes | ✅ shipped | Same code path; `pdfLoading == null` after `State.Done` switches the bar to reading progress. See `docs/verify/03-completed-parse.png`. |
| `versionCode` bumped 1 → 2 | ✅ shipped | `app/build.gradle.kts`: `versionCode = 2`. `aapt2 dump badging` line shows `versionCode='2'`. |
| `versionName` bumped "1.0" → "1.0.1" | ✅ shipped | `app/build.gradle.kts`: `versionName = "1.0.1"`. `aapt2 dump badging` line shows `versionName='1.0.1'`. |
| Signing cert matches required SHA-256 | ✅ shipped | `apksigner verify --print-certs` prints `1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953`. Keystore: uber-apk-signer's built-in debug keystore, alias `androiddebugkey`, password `android`. |
| APK SHA-256 differs from Batch 1 | ✅ shipped | `b9d85d6799d6e7c69a99eb9a714d9b050eeccf0aaddf1e198a62d4d04f64a208` (Batch 2) vs `7887eeafa728a76da5ae79617af24dff3ba6835cee4b697f11fcc7fa71f698db4` (Batch 1). |
| All five verification commands pass | ✅ shipped | Raw output pasted in `README.md`. `verify_apk.py` final line: `RESULT: PASS`. |
| Existing bookmarks / reading progress preserved on `adb install -r` | ✅ shipped | No DB schema change (Room v1, `fallbackToDestructiveMigration` not triggered). No `adb uninstall` step in the install instructions. `DocumentEntity` shape unchanged. |
| Unit tests pass | ✅ shipped | `testReleaseUnitTest` task completes successfully. Tests in `app/src/test/java/app/reader/parser/SentenceSplitterTest.kt` cover the paragraph/sentence splitter that the streaming parser feeds. |

## Deferred items

None. All in-scope IDs (PDF-08, RD-10, X-13) shipped in this batch.
