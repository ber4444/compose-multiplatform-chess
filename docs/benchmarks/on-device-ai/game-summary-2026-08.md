# Game Summary on device — 2026-08-15

Measured because the Move Coach verdict does not transfer: a summary is a deliberate button press
with a spinner at game end, not an automatic panel, so a wait that disqualifies the coach may be
fine here. What does not change is the bar for truth — **this surface has no response validator at
all**, so whatever the model writes reaches the user.

12 real games, played engine-vs-engine with the player's side deliberately weakened and assessed
ply by ply (`tools/generate_summary_fixtures.py`). Both platforms read the same fixtures through
`SummaryFixtures`, so both models were handed the same games and the same turning points.

| | Android `mlkit-aicore-full` | iOS `FoundationModels` |
|---|---|---|
| Succeeded | **7/12** | 4/12 |
| Why the rest failed | 2 × latency budget (+3 screen-off, harness) | **8 × "An unsupported language or locale was used"** |
| Latency, successes | 11.9 s median | **2.3 s median** |
| Cites exactly the code-chosen turning points | **6/7** | 1/4 |
| Truncated mid-sentence | 1/7 | **4/4** |
| Length, median | 539 chars | 269 chars |

**This reverses the Move Coach result.** On the coach, Foundation Models was the faster, more fluent
and less truthful writer. On summaries it is *also* the less reliable one: it refuses two games in
three with a locale error — chess notation is evidently not natural language enough for it — and
every summary it does produce stops mid-sentence.

AICore's summaries are the best on-device output measured on this project so far. They name the
right moves with the right classes and read like a coach:

> *"[move-27] with `f3` was a significant blunder, leading to a loss of material. Later, [move-33]
> with `Qd1` was a slightly inaccurate move… Finally, [move-49] with `Qc5` was a mistake."*

Two real errors remain in the successes: one game dropped a turning point, and one conflated the
move played with the engine's preferred move (*"opting for Qc4 and cxd4 respectively"* where cxd4
was the engine's choice, not a move the player made). On iOS the same class of error puts a wrong
`[move-N]` on the engine's move — and B16 turns those tags into board jumps, so a wrong tag
navigates the user to the wrong ply.

## Does it beat the deterministic composer?

Not yet, and the gap is no longer about writing quality.

- `GameSummaryGrounding` cites 3 of 3 turning points, every time, instantly, and cannot fail.
- AICore is 58% success at ~12 s. The other 42% of the time the user waits 12 s and is shown the
  deterministic text anyway.
- Nothing guards truncation, because there is no validator on this surface.

**Recommendation: leave Android's Game Summary deterministic**, and treat this as the first surface
where an on-device model has come close enough to be worth re-measuring — a fix to the truncation
and the latency budget would change the answer, and neither is a model problem.

## A polish bug in the deterministic text itself

`GameSummaryGrounding` emits `"This was a inaccuracy."` and `"This was a good."` — wrong article,
and "a good" is not a move class a reader recognises. It ships today on both platforms, and it is
the text the model falls back to.
