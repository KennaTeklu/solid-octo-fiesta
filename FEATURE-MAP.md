# FEATURE-MAP.md

The full feature inventory for Reader, organized by section. Every feature has an ID. Every batch references IDs from this file.

## Sections

| Section | IDs | Count | Phase |
|---|---|---|---|
| 1. Library / Home | LIB-01..LIB-08 | 8 | 1, 5 |
| 2. Add Content | ADD-01..ADD-06 | 6 | 1, 5 |
| 3. Reader Screen | RD-01..RD-23 | 23 | 1, 2, 4, 5 |
| 4. Playback Bar | PB-01..PB-14 | 14 | 1, 3, 5 |
| 5. TTS & Voices | TTS-01..TTS-12 | 12 | 2, 3, 5 |
| 6. Voice Configuration | VOICE-01..VOICE-08 | 8 | 3, 4, 5 |
| 7. Dialog Voices | DLG-01..DLG-08 | 8 | 5 |
| 8. Formats & Parsers | FMT-01..FMT-10 | 10 | 1, 2, 5 |
| 9. PDF Specific | PDF-01..PDF-08 | 8 | 2, 4, 5 |
| 10. Web & URL Reading | WEB-01..WEB-10 | 10 | 1, 2, 5 |
| 11. Queue & Reading Lists | QUEUE-01..QUEUE-07 | 7 | 1, 5 |
| 12. Bookmarks | BM-01..BM-05 | 5 | 1, 5 |
| 13. Search | SRCH-01..SRCH-03 | 3 | 5 |
| 14. Language & Translation | LANG-01..LANG-16 | 16 | 4 |
| 15. Reference Lookups | REF-01..REF-05 | 5 | 4 |
| 16. AI Handoff | AI-01..AI-04 | 4 | 5 |
| 17. Recording & Export | REC-01..REC-06 | 6 | 5 |
| 18. Accessibility | ACC-01..ACC-07 | 7 | 5 |
| 19. Themes & Fonts | THEME-01..THEME-10 | 10 | 1, 5 |
| 20. Playback Behaviour | PLAY-01..PLAY-11 | 11 | 5 |
| 21. Device Integration | DEV-01..DEV-09 | 9 | 1, 5 |
| 22. Integration & Sync | INT-01..INT-10 | 10 | 3, 5 |
| 23. Settings Shell | SET-01..SET-17 | 17 | 1, 3, 4, 5 |
| Cross-cutting | X-01..X-17 | 17 | All |

**Total: 232 features across 23 sections + 17 cross-cutting concerns.**

## How to use this file

- Each batch file names the IDs it implements.
- Each ID has a matching row in the long form of this map (kept in `docs/FEATURE-MAP-FULL.md` when we need to inspect UI/UX/dependency detail).
- IDs are stable. Do not renumber.

## Phase → Batch mapping

Phase 1 (Foundation) — Batches 1–3
Phase 2 (Formats Deep) — Batches 4–6
Phase 3 (Voices) — Batches 7–9
Phase 4 (Language & Study) — Batches 10–12
Phase 5 (Polish) — Batches 13–17
Phase 6 (Sync & Integration) — Batches 18–19
Phase 7 (Advanced Web) — Batch 20

See BATCHES.md for the per-batch scope.