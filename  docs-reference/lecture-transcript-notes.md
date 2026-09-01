# Lecturer Video Transcript — Key Points (Exercise 2 Overview)

Source: transcribed via NotebookLM from the lecturer's Ex2 overview recording. This is a
distillation of what's actually confirmed there, not the full transcript — see chat history
if the exact wording is ever needed.

## Confirmed, changes something we'd assumed

- **Module structure:** stay in the same IntelliJ project as Ex1; add a **third module**
  dedicated to the JavaFX app, alongside `engine` and Ex1's console `ui` module — not
  inside `ui`. (Acted on — see `ARCHITECTURE.md` for the resulting module layout.)
- **Submission packaging:** submit **only the JavaFX module's JAR.** No JAR needed for the
  old console module; it doesn't need to run correctly against Ex2 files or stay
  backward-compatible in any way.
- **Grading emphasis:** functionality only — buttons work, components reachable, scrolling
  correct. No points lost for graphic design or CSS quality.
- **No Ex1 backward compatibility required at all** — Ex2 is tested only against
  new-schema files.

## Confirmed, matches what we already had

- Recommended build order: JavaFX skeleton (Ex1 data, LMSR, no users) → Users (schema +
  engine + UI) → Order Book (engine first) → Order Book UI → Mint last. Matches
  `BUILD_PLAN_2.md`'s stage order exactly.
- Main screen: single `BorderPane`, top = file loading, center = `TabPane` with
  Events/Users tabs, each with a list (left) + details (right) split.
- Git: recommended to tag the Ex1 submission commit and continue Ex2 on `master` in the
  same repo. (Done — see the `ex1-submission` tag.)

## Explicitly NOT covered by the recording (checked directly, not just asked)

These remain genuinely open — not resolved by re-listening, and (for the two Order Book
ones) not actually in the written spec either, despite an initial NotebookLM answer
suggesting otherwise; double-checked directly against the docx text:

1. **Naked sell** (selling Order Book shares the user doesn't hold) — no guidance found
   anywhere. Our resolution: reject via `IllegalTradeException` (protects the share-supply
   conservation invariant already documented in `order-book-appendix.md`).
2. **Resting/unmatched Order Book orders at event close** — no guidance found anywhere,
   including the docx itself (verified directly, not just via NotebookLM's claim). Worth a
   direct forum question before implementing Order Book's `closeEvent` path.
3. Negative-balance blocking timing, and leftover Order Book funds at close — recording adds
   nothing beyond what the written spec already says (or doesn't say); both remain our own
   documented interpretations/open items in `CLAUDE.md` Section 8.
4. Exact JavaFX version — recording says separate setup guides/videos are coming; not in
   this recording itself.
5. Third-party JavaFX styling libraries (e.g. AtlantaFX) — not mentioned by name; recording
   does say grading is functionality-only with "freedom of choice" on tools/components,
   which softens (but doesn't fully settle) the earlier caution. See `CLAUDE.md` Section 8
   for the current call on this.
