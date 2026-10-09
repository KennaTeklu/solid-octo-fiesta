# DESIGN-PARITY.md — what to borrow from Pro, what to leave behind

Pro and Reader are both personal-use Android apps. Pro is a data-dense fitness tracker; Reader is a text-first reading app. They share a codebase philosophy but not a screen shape.

This file lists what from Pro transfers, what does not, and why. Read before Batches 3 (reader core), 13 (highlight + auto-scroll), and 15 (themes + accessibility).

## Transfers cleanly

### Visual tokens
- Dark canvas and surface colours (Pro dark theme is quiet, no glare)
- Hairline borders instead of heavy shadows
- Muted-gold accent for the header name (used sparingly — one accent per screen)
- Inter for UI body text, Space Grotesk for display headings
- JetBrains Mono for numeric displays
- Card radius: 14 dp (Pro standard) applied to Reader cards
- Soft glass blur on the playback bar (Pro uses this for the bottom dock)

### Interaction patterns
- Bottom dock with 4-5 primary destinations, floating centre action (Pro Train FAB pattern)
- Sheet overlays with a rounded top edge and drag handle
- Confirm dialogs with red destructive action, grey cancel
- Toast notifications brief, top-anchored, non-blocking
- Haptics on every tap that commits a state change

### Navigation and state
- Nav state is a single source observed by every screen
- Force-stop + relaunch is the only way to see new code — never trust a stale screen
- Settings opens in under 1 second; cards expand without recomposing siblings
- Every list uses LazyColumn with key and contentType

### Notification style
- Media-style notification with play / pause / skip
- Lock-screen controls identical to in-app controls
- Progress bar reflecting playback position

## Transfers with adaptation

### Header treatment
Pro shows a streak fire + avatar + name + experience + a Resume pill. Reader's header should show: current document title (or "Library" when on home), a sync dot, and a Resume-reading pill when there is a document open in the background. Same visual weight, different content.

### Card-based content list
Pro's library renders 20 cards at a time with a "Load 20 more" button. Reader's library should do the same — the list may grow to hundreds of items. Same chunking discipline.

### Settings accordion
Pro groups 25 settings cards into a LazyColumn with single-open-at-a-time accordion. Reader should do the same for its SET-* groups. Same card body shape: title, subtitle, chevron, expand.

### First-run wizard
Pro fires a 5-step onboarding on first launch and marks completion with a flag. Reader should do the same on first install per profile. Same "resumable draft" pattern.

## Does not transfer

- Dashboards with charts (Reader is not analytical)
- Multiple navigation levels (Reader stays shallow)
- Muscle, workout, and fatigue icons (Pro-specific)
- A "Today" concept (Reader has no daily schedule)
- Progress rings and streak fire (Reader has no streaks)
- Reference screens (muscle wiki, exercise library)
- The Pro brand palette accents on the reader surface — keep the reader surface quiet, one accent for the current sentence and nothing else

## Personalization principles

Reader is *your* app for *your* reading. It can afford to be more personal than Pro:

- Greet with the last-read document on launch, not a library grid
- Remember the voice used per document (already in Batch 8)
- Suggest continuing the item most recently left off
- Let the reader surface be nearly empty until a document is open
- No dashboard, no KPIs, no notifications unless the user asked for one

Where Pro is loud by necessity (many metrics), Reader should be quiet by default.

## Colour parity

Shared tokens (recommend identical hex):
- Dark canvas `#07090D`
- Dark surface `#10141C`
- Dark surface2 `#151B25`
- Hairline `#2494A3B8`
- Text `#F2F4F8`
- Text2 `#B7BDC9`
- Text3 `#8A919E`
- Gold accent (used only in header) `#FBBF24`
- OK green `#34D399`
- Warn amber `#FBBF24`
- Bad red `#F87171`

Reader-specific tokens (not shared with Pro):
- Reader paper (light theme background) `#F5F1E8`
- Reader ink (light theme text) `#1E293B`
- Current-sentence highlight (dark) `#FBBF2420`
- Current-sentence highlight (light) `#B4530920`

## Rule

Borrowing a pattern means borrowing its *reason*. Pro chunked lists to fix an ANR — Reader chunks lists for the same reason. Pro accordioned settings to reduce recomposition — Reader does the same. Do not copy a shape without the reason; do copy the reason and pick the shape that fits.