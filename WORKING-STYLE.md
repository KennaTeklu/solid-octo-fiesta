# WORKING-STYLE.md

## Consulting experts

When a task touches design, data integrity, or user flow, consult relevant experts before deciding. The point is not to involve every skill available — it is to make sure a design problem is looked at by a design mind, and a data problem by an engineering mind, before the first line of code is written. Use your judgment on which experts are relevant — the ones closest to the problem at hand. Add more only if a first pass leaves something unanswered.

## Communicating back

When a task is unclear, or when a suggestion in a batch file seems to miss something you see in the code, say so before building. The client would rather have one question answered up front than a rebuild later. State what you found, what you would propose instead, and why. Short is fine.

## Reference — @Voice

The app this project rebuilds is @Voice Aloud Reader by Hyperionics. It is a real, working, commercially-shipped app on Google Play. Reading its behavior — screen flows, gesture handling, how it handles a PDF, how the voice picker feels — is more useful than any description. If a batch file says "match @Voice", install @Voice on a test device and look at it. Referencing behavior we can see beats guessing at intent.

## Delivering

Every batch ships as one zip. Structure is fixed and documented in ACCEPTANCE.md. Before shipping, verify the five commands; if any fails, fix and rerun. Every claimed feature has a screenshot. Every deferred item is marked with a reason.

## What matters

The client's phone has real bookmarks and reading progress on it. Never ship anything that risks that data without asking first — no uninstall steps, no signature changes, no versionCode decreases. When in doubt about anything that could touch existing data, ask.