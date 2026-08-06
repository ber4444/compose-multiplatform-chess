import re

with open("/Users/presence/Downloads/chess plan.md", "r") as f:
    content = f.read()

# 1. Update 0.3 Eligibility gates header
content = content.replace(
    "## 0.3 Eligibility gates *(2 of 4 cleared)*",
    "## 0.3 Eligibility gates *(3 of 4 cleared)*"
)

# 2. Update 0.3 Eligibility gates table
old_table_row = """| "Uses the RevenueCat SDK to power at least one in-app or web purchase" | ⚠️ **SDK wired, not yet earning.** `purchases-kmp-core` backs `Entitlements` on Android + iOS behind `RevenueCatEntitlements`. **Still needed: dashboard products/offerings, a paywall, and something that actually reads the entitlement** | §0.4 — no longer a *code* blocker; the remainder is dashboard + paywall |"""
new_table_row = """| "Uses the RevenueCat SDK to power at least one in-app or web purchase" | ✅ **Code is Done (#117).** SDK is implemented and paywall reads the entitlement. **Still needed: dashboard products/offerings** | §0.4 — Code is complete; the remainder is dashboard configuration |"""
content = content.replace(old_table_row, new_table_row)

# 3. Update 0.4 Monetization design
old_sdk_bullet = """- [x] ~~**The RevenueCat SDK itself.**~~ Shipped. Three findings worth keeping: the coordinate is"""
new_sdk_bullet = """- [x] ~~**The RevenueCat SDK itself.**~~ Shipped in #117. Three findings worth keeping: the coordinate is"""
content = content.replace(old_sdk_bullet, new_sdk_bullet)

old_paywall_bullet = """- [ ] Paywall UI, and make something actually **read** `LocalEntitlements` — the seam gates nothing
      today."""
new_paywall_bullet = """- [x] ~~Paywall UI, and make something actually **read** `LocalEntitlements`~~ — Shipped in #117 (`PaywallScreen.kt` and `ProGate.kt`)."""
content = content.replace(old_paywall_bullet, new_paywall_bullet)

# 4. Update Week 1
old_week1_bullet = """- [ ] **C** RevenueCat KMP SDK behind the `Entitlements` seam (§0.4). The seam landed in #114; this
      is the SDK, the API key, and one product resolving. **Still the eligibility blocker.**"""
new_week1_bullet = """- [x] **C** ~~RevenueCat KMP SDK behind the `Entitlements` seam (§0.4).~~ Shipped in #117."""
content = content.replace(old_week1_bullet, new_week1_bullet)

# 5. Update Week 2
old_week2_bullet = """- [ ] **C** Paywall UI (RevenueCat's remote paywall or hand-rolled Compose) + entitlement gating on
      the five AI surfaces."""
new_week2_bullet = """- [x] **C** ~~Paywall UI + entitlement gating on the five AI surfaces.~~ Shipped in #117 (`PaywallScreen.kt`, `ProGate.kt`)."""
content = content.replace(old_week2_bullet, new_week2_bullet)

# 6. Add Shipped PRs 117-122 section
shipped_116_section = """- **Adding a column doesn't reach hand-rendered rows.** The three manual device rows silently
  shifted one column left when the Fluency column landed, misreporting the benchmark data the
  article cites.

## ~~B4b. Server-side output sanitization~~ — SHIPPED"""

new_shipped_section = """- **Adding a column doesn't reach hand-rendered rows.** The three manual device rows silently
  shifted one column left when the Fluency column landed, misreporting the benchmark data the
  article cites.

## Shipped — PRs #117–122

Monetization and rigorous cloud evaluations. Removed from the phases below; the reasoning lives in the commits, PR bodies, and `docs/plans/cloud-eval-honesty-followups.md`.

| Was | Outcome | PR |
|---|---|---|
| **§0.4 / Week 1 / Week 2** Monetization | Shipped the RevenueCat SDK, `PaywallScreen`, and entitlement gates for all AI surfaces. | #117 |
| Docs sync | Restructured AI features in README, synced monetization, synced CLAUDE.md. | #118, #120 |
| **B14 / B20** Cloud Eval / Shadow Eval | Shipped rigorous server-side evaluation tests (Corpus citability, LLM response shapes, chat streaming counts) to close the cloud eval honesty follow-ups. Includes `tools/collect_cloud_samples.sh` for R-1 usefulness review. | #121 |
| Deployable corpus reseeding | Shipped verifiable corpus reseeding (`PostgresPassageRepositoryTest` and `SeedMain`) to make the R-1 cloud eval follow-ups deployable. | #122 |

## ~~B4b. Server-side output sanitization~~ — SHIPPED"""

content = content.replace(shipped_116_section, new_shipped_section)

# 7. Update B5 chat re-scope to point to cloud-eval-honesty-followups.md
old_b5 = """## B5. RAG-4 — chat re-scope

- [ ] Retrieve **assessment records**, not corpus passages, for questions about the user's game.
- [ ] Counterfactuals ("why not Bxf7?") via Stockfish + narration.
- [x] ~~**Split out a Hint button**~~ — shipped in #116. Note two behaviours it settled that the
      bullet didn't anticipate: the button is hidden when no engine is attached (a
      `pickMoveCPU` "hint" is a capture-preferring *random* move, i.e. confidently wrong advice),
      and it queries at `EngineDifficulty.HARD` regardless of the opponent's setting, so a hint on
      Easy doesn't teach a deliberately weakened move.
- [ ] Build the retrieval query from deterministic features, using the user's message only to select
      *which* records are relevant (#97 Known-gap 1's second sub-cause)."""

new_b5 = """## B5. RAG-4 — chat re-scope

*Note: For the cloud surfaces (Opening Explainer and Position Chat), this phase is now superseded by the formal R-1 strategy defined in `docs/plans/cloud-eval-honesty-followups.md` (merged in #121/122).*

- [ ] Execute **R-1 implementation strategy**:
  - Retrieve insight cards (not generic ECO passages) and route chat by "answer shape".
  - Derive board facts deterministically by replay and separate this layer from prose.
- [x] ~~**Split out a Hint button**~~ — shipped in #116. Note two behaviours it settled that the
      bullet didn't anticipate: the button is hidden when no engine is attached (a
      `pickMoveCPU` "hint" is a capture-preferring *random* move, i.e. confidently wrong advice),
      and it queries at `EngineDifficulty.HARD` regardless of the opponent's setting, so a hint on
      Easy doesn't teach a deliberately weakened move."""
content = content.replace(old_b5, new_b5)

# 8. Update B14 to mark cloud-side shadow evals as shipped
old_b14 = """- [ ] **Make the fluency row gate.** `FluencyScorer` reports but enforces nothing —
      `EvalMain`'s regression check still filters on `groundingViolations` alone. Same gap as B13's
      first bullet, and the same fix. Note `local-template-chat` sits at 5.0%, so switching it on
      needs a decision: tighten the composer, or gate on *regression against the recorded rate*
      rather than on zero."""

new_b14 = """- [x] ~~**Make the fluency row gate.**~~ Cloud eval honesty follow-ups (R-1) and server-side quality tests were merged in PR #121, strictly enforcing response shapes and citability.
- [ ] For on-device: enforce fluency gating for `local-template-chat` (currently at 5.0%). Decide to tighten the composer or gate on *regression against the recorded rate* rather than zero."""
content = content.replace(old_b14, new_b14)

# 9. Update B20
old_b20 = """- [ ] Shadow/canary — run a prompt or model change against the golden set as a **release gate**,
      and diff scorecards. The harness exists; wire it."""
new_b20 = """- [x] ~~Shadow/canary — run a prompt or model change against the golden set as a **release gate**.~~ Cloud side shipped in #121 (via R-1 manual usefulness review and server-suite gates)."""
content = content.replace(old_b20, new_b20)


with open("/Users/presence/Downloads/chess plan.md", "w") as f:
    f.write(content)

print("Edits applied!")
