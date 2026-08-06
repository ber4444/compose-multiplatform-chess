import re

with open("/Users/presence/Downloads/chess plan.md", "r") as f:
    content = f.read()

# 1. Patch Section 4 (Answer Shape)
old_sec4 = """- **4. Route chat by answer shape**: Classify the question before composing and require the corresponding structure. Add deterministic minimum-shape checks before display: polarity in the first clause for yes/no questions; both sides/structures for comparisons; a future action for plan questions; and benefit plus trade-off for why questions. These checks reject non-answers; they do not claim that an answer is strategically correct."""

new_sec4 = """- **4. Route chat by answer shape**: Classify the question before composing and require the corresponding structure:

  | Shape | Minimum response |
  |---|---|
  | Why is X popular? | choice → trade-off → characteristic plan |
  | What is piece X doing? | immediate target → role → follow-up plan |
  | Should I worry about X? | direct yes/no → condition → response |
  | Is pawn X free? | direct answer → cost → likely continuation |
  | How does X differ from Y? | shared objective → structural difference → trade-off |
  | What is the problem piece? | piece → restriction → standard remedy |
  | What is the plan? | future action, target, or condition |

  Add deterministic minimum-shape checks before display: polarity in the first clause for yes/no questions; both sides/structures for comparisons; a future action for plan questions; and benefit plus trade-off for why questions. These checks reject non-answers; they do not claim that an answer is strategically correct."""

content = content.replace(old_sec4, new_sec4)

# 2. Patch Section 6 (Presentation and observability)
old_sec5 = """- **5. Keep chat query-aware**: Position Chat must answer the user's question, not quote the Opening Explainer's first sentence. Provider prompts must not reward copying source wording merely to satisfy overlap checks. Preserve the grounding contract while making the retrieved insight and the question explicit inputs to the composer."""

new_sec5 = """- **5. Keep chat query-aware**: Position Chat must answer the user's question, not quote the Opening Explainer's first sentence. Provider prompts must not reward copying source wording merely to satisfy overlap checks. Preserve the grounding contract while making the retrieved insight and the question explicit inputs to the composer.

- **6. Keep presentation and observability correct**:
  - Make label/title ownership explicit so only one layer renders an opening label.
  - Preserve spaces and sentence boundaries through SSE chunking and final assembly.
  - Keep word-boundary truncation and deliberation cleanup covered by tests.
  - Make the collector print `composerId`, fallback/validator reason, finish reason, and `done`.
  - Ensure terminal composer/fallback output is distinguishable from a stream that simply ended.
  - Capture raw provider output for successful and rejected responses."""

content = content.replace(old_sec5, new_sec5)

# 3. Patch Monitoring
old_monitoring = """- Add sampled human usefulness review using the acceptance bar above. A high validator rate cannot close R-1 by itself.
- Treat the server's request cap as per-request estimation, not cumulative spend; keep policy and enforcement values visible and tested."""

new_monitoring = """- Add sampled human usefulness review using the acceptance bar above. A high validator rate cannot close R-1 by itself.
- Track provider, model, prompt version, retrieval ids, token usage, latency, finish reason, and fallback category.
- Treat the server's request cap as per-request estimation, not cumulative spend; keep policy and enforcement values visible and tested."""

content = content.replace(old_monitoring, new_monitoring)

# 4. Patch Article Updates
old_article = """- Article publication or external corrections (e.g. in **Routing Modes Are Not a Routing Policy**); update those after the fresh sample supplies exact measurements. Do not claim the cloud prose is user-ready until R-1 closes."""

new_article = """- Article updates to make after the next sample (e.g. in **Routing Modes Are Not a Routing Policy**): State the enforced server cap and distinguish it from the client policy declaration; describe provider-batched streaming and the remaining usefulness limitation. Do not claim the cloud prose is user-ready until R-1 closes."""

content = content.replace(old_article, new_article)

with open("/Users/presence/Downloads/chess plan.md", "w") as f:
    f.write(content)

print("Patch successful!")
