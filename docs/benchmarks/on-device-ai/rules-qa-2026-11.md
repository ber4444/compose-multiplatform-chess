# Rules Q&A on device — November 2026

The first model run of Rules Q&A, the one on-device surface that is live for users. 37 fixtures from
`tools/rules_qa_fixtures.json`: 30 one-per-passage questions phrased as a player would ask, 4
confusable paraphrases, and 3 out-of-corpus questions. Scored with
`./gradlew :evals:scoreDeviceRun -Psurface=rules-qa`; the faithfulness column is a hand read.

## Retrieval (BM25, deterministic, same on every platform)

| Correct passage ranked… | In-corpus hits |
|---|---|
| first | 22/34 |
| in the top 2 | 28/34 |
| in the top 4 | 32/34 |

The two questions that miss BM25's top 4 entirely are *"Do I actually take their king to win?"*
(`board-goal`) and *"Can a knight hop out from behind its own pawns?"* (`knight-movement`).

## Android — Gemini Nano via ML Kit/AICore (`mlkit-aicore-full`), Pixel 10 Pro XL, Android 17

| | Before the fix (3 passes) | After the fix (3 passes) |
|---|---|---|
| model answers shown | 35/37 | 35/37 |
| correct passage cited (in corpus) | 28/34 | 31/34 (+1 correct answer that dropped its tag) |
| citations of a passage the model was not given | 0 | 0 |
| **factually wrong answers** | **4, identical every pass** | **1, identical every pass** |
| out-of-corpus questions refused or hedged | 2/3 | 3/3 |
| median / p95 latency | 4.1 s / ~6 s | 4.2 s / 5.3–5.5 s |

The four wrong answers before the fix, all validator-approved:

- *"Can I get a second queen without first losing my original one?"* → "No… Each player starts
  with only one queen [initial-position]." **Still wrong after the fix**, now while citing
  `promotion`, whose text contradicts it. With retrieval and prompt ruled out, this one is the model.
- *"Do we both need to agree to a draw if there is no possible mate?"* → "Yes, players must agree…"
  Fixed: "If there is no possible legal sequence that can lead to checkmate, the game is drawn."
  (One of three passes appends a muddled second sentence.)
- *"Both players want to stop even though a mate is still possible…"* → answered with threefold
  repetition. Fixed: draw by agreement.
- *"How do I claim a win if my opponent disconnects online?"* (out of corpus) → invented "the game is
  typically declared a draw". Fixed: "The offline rules do not cover what happens if your opponent
  disconnects online."

### What was wrong, and the fix

1. **The model's refinement query never reached the prompt.** `OnDeviceRulesQaAnswerer` built the
   answer prompt from `(fromQuestion + refined).distinctBy { it.id }.take(2)`. `lookup()` returns up
   to four hits, so the question's own top two filled both slots every time: in 31/31 rows the
   passages the model saw were exactly BM25's top 2 for the raw question. For each of the three
   wrong in-corpus answers the correct passage was BM25 rank 3 or 4.
2. **`ANSWER_PASSAGES = 2` was sized for a model that no longer ships** — gemma3-270m via Cactus,
   which echoed four passages back instead of answering. Gemini Nano does not.
3. **The prompt's example answer was draw-specific**: *"Yes — with no way left to force mate the game
   is drawn"*, which the model copied into the dead-position answer.

The fix interleaves question and refined hits (`selectAnswerPassages`), raises the cap to 4, makes
the example content-free, and tells the model to say when the retrieved rules do not cover the
question. Measured one variable at a time, one pass each:

| Variant | Correct cites | Wrong answers |
|---|---|---|
| cap 4, question hits first | 32/34 | 3 |
| cap 4, interleaved | 32/34 | 3 (different wording) |
| interleaved + prompt change (shipped) | 31/34 + 1 uncited | 1 |

The interleaving scores the same as question-first; it is kept because it is the only variant in
which the refinement turn — a full model call — can influence the prompt.

## iOS — Foundation Models, iOS 27.0 simulator (iPhone 18 Pro), one pass, unchanged code

The iOS tool ranks the corpus with `NLEmbedding` and returns four passages for a model-written
query.

| | |
|---|---|
| correct passage cited (in corpus) | 28/34 |
| factually wrong answers | 0 (one opens with a misleading "Yes") |
| out-of-corpus refused or hedged | 3/3 — the Sicilian Defence answer, written from general knowledge, was rejected by the validator |
| median latency (simulator on a Mac — says nothing about a phone) | 2.5 s |

Model, retriever, query author and passage count all differ between the platforms, so this is not a
model comparison.

## Reproducing, and four ways the run lied first

```bash
adb shell svc power stayon usb   # then unlock the phone by hand before starting
adb shell am start -S -W -n io.github.ber4444.chess/com.example.myapplication.MainActivity --ei bench_rules_qa_iterations 1
adb exec-out run-as io.github.ber4444.chess cat files/bench/rules_qa.jsonl > build/bench/rules-android.jsonl
./gradlew :evals:scoreDeviceRun -Psurface=rules-qa -Pfile=../build/bench/rules-android.jsonl
```

- **A locked phone looks like a flaky model.** `keepBenchInForeground()` does not stop the screen
  timeout from locking the device, and AICore then refuses with `ErrorCode 30`. Two runs lost 4 and 9
  contiguous rows that way.
- **The iOS bundle id is `io.github.ber4444.chess.ios`.** A simulator that still has the old
  `io.github.ber4444.chess` install will happily launch it; that build predates this suite, silently
  runs the Move Coach default and reports "Benchmark Complete".
- **Foundation Models fails every call on the iOS 26.5 simulator on a macOS 27 host**
  (`promptTemplateNotFound`, `GenerationError -1`). Use an iOS 27 runtime. The retrieval floor turns
  each failure into a cited "Grounded" answer, so check the `kind` column rather than the answers.
- **`xcodebuild -destination "name=iPhone 17 Pro"` fails** when that name exists on two runtimes; use
  `id=<UDID>`.
