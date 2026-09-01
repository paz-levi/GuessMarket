# Lecturer JavaFX Materials — Key Points

Source: NotebookLM-generated brief from the lecturer's JavaFX lesson materials (separate
from the general Ex2 overview — see `lecture-transcript-notes.md` for that one). Cross-
checked against this repo's actual `gui` module, not accepted at face value.

## Confirmed already correct in this codebase

- **Engine/JavaFX decoupling** — "Engine classes must remain 100% agnostic to JavaFX
  imports." Already a hard rule (`CLAUDE.md` Section 2), enforced throughout.
- **Resource path pitfall** — the lecturer warns that `FXMLLoader`/CSS resource lookups must
  resolve correctly once packaged inside a JAR (a classic relative-vs-absolute classpath
  gotcha). **Already verified safe in practice, not just in theory**: `gui.jar` has been run
  directly (`run.bat`) multiple times today, and `jar tf` confirmed `MainView.fxml`/
  `styles.css` land at the same classpath location the code looks them up from. Not a live
  risk — no action needed, but worth knowing this is exactly the failure mode being guarded
  against.
- **Single JAR, JavaFX-only submission; roadmap order** — both already confirmed via the
  other lecture transcript; this material repeats them, nothing new.

## Real, acted-on finding

- **`<fx:include>` decomposition** — the lecturer teaches splitting complex screens into
  reusable FXML sub-components, with a specific naming convention (`fx:id="fooBar"` → parent
  field `FooBarController fooBarController`). `MainViewController` has grown into a
  single-class handler for file loading + both tabs' full list/detail/form UI — a real
  god-class by the project's own established standard (`CLAUDE.md` Section 5).
  **Decision, recorded in `CLAUDE.md` Section 2**: not restructured now (a real split needs
  an inter-controller communication design — opening an event affects the Users tab and vice
  versa — that doesn't get cheaper by waiting). Compromise: new large UI blocks (starting
  with Order Book's UI) go into plain static-method helper classes, not FXML/Controllers,
  to slow further growth without committing to the harder design yet.

## Noted, not acted on — stylistic gaps, no confirmed grading relevance

- **`UIAdapter` pattern** — a named pattern for wrapping `Platform.runLater` callbacks so
  background engine calls stay JavaFX-unaware. This codebase already achieves the same
  *outcome* (Task lives entirely in `gui`, engine is JavaFX-free) without a class carrying
  this specific name/shape. Not adopted explicitly — the goal is already met structurally.
- **`Task` with real `updateMessage()`/`updateProgress()`** — the lecturer's materials
  describe structured progress reporting; this app's `Task` currently just shows an
  indeterminate spinner plus an artificial delay for a single atomic operation (file
  parsing), which has no natural intermediate steps to report. Low value here.
- **JavaFX `Property`/binding usage** (`textProperty().bind(...)` etc.) — the lecturer
  teaches this over direct imperative `setText()`/manual rebuild, which is what this
  codebase uses throughout. A real stylistic gap from what was taught, but:
  - Grading is confirmed functionality-only (per the other lecture transcript) — no known
    grading cost.
  - Retrofitting bindings this late would be a large-surface, high-regression-risk change
    for a codebase that's already working and manually verified.
  - Worth being able to explain this choice if ever asked (e.g. in an oral component), not
    worth refactoring under deadline pressure.
