# On-Device AI Architecture

This document describes the architecture of the on-device AI move coach introduced in the `:onDeviceAi` module.

## Architecture Overview

The `:onDeviceAi` module sits between the game engine and the UI. The chess game logic does not know about language models; it simply exposes an interface (`OnDeviceTextGenerator`, `AiCoachOrchestrator`) that platform code implements. 

The module owns:
- **Semantic request/response models**: `MoveCoachRequest` (FEN, best move, tactical tags, evaluation) and `MoveCoachExplanation` (headline, body, confidence, metrics).
- **Routing policy**: Pure Kotlin logic that decides between on-device inference, cloud inference, or a rule-based fallback based on privacy class, latency budget, thermal state, and user settings.
- **Prompt builder**: Assembles a grounded prompt from the request's whitelisted chess fields, intentionally preventing user input from entering the system instruction.
- **Response validator**: Rejects responses that are too long, ungrounded, or contain forbidden phrases, with a retry-then-fallback chain.
- **Deterministic fallback**: Rule-based explanation built from the same tactical tags, ensuring the panel always shows useful information even when the model is unavailable.
- **Orchestrator**: Drives the flow (route → generate → validate → retry-or-fallback) as a Kotlin `Flow`.

The chess app (outside of `:onDeviceAi`) owns:
- **Context extraction**: Derives tactical tags (capture, check, castle, promotion, develops, center-control, king-safety, opening) from before/after game states.
- **UI state**: A sealed `MoveCoachUiState` that drives a Compose panel.
- **Timing**: Fires the coach after Black's move; cancels stale jobs on reset or new White move.

## Prompting Strategy

The prompt is intentionally narrow and grounded. The model isn't asked to reason about chess — Stockfish already did that. It is asked to *rephrase app-supplied facts* in natural language.

**System instruction:**
```text
You are a chess coach explaining a single move to a casual player.
Say WHY the move is good in 1-2 short sentences.
Be specific: mention the piece, the square, and what it does.
Do not mention openings by name, engine depth, or ratings.

Good: "Nf3 develops the knight and controls the central e5/d4 squares."
Bad: "This is a good move that improves the position."
```

**User prompt:**
```text
Move: Knight g1→f3
Key points: develops a piece, controls the center, opening-phase move

Explain this move in 1-2 sentences:
```

The "Key points" come from deterministic tags derived from the game state: `develops`, `center-control`, `king-safety`, `capture`, `check`, `castle`, `promotion`, `material-swing`, `pawn-push`, `opening`.

## Fallback Logic

When the model is unavailable (slow device, Apple Intelligence off, validation failed twice), the orchestrator produces a deterministic explanation from the tags:

- "Nf3 develops a piece to an active square. It fights for the center."
- "Qh5 delivers checkmate."
- "O-O castles kingside, tucking the king to safety."

This fallback ensures that devices without a local model (like Desktop and Web) still display a functional coach panel.

## Routing Policy

The routing policy models three routes: `OnDevice`, `Cloud`, and `Fallback`. The move coach is currently classified `LOCAL_ONLY` — it never uses the cloud.

Key routing decisions:
- **App backgrounded**: Fallback (background inference is blocked on mobile).
- **Thermal state CRITICAL**: Fallback.
- **LOCAL_ONLY + model available**: OnDevice.
- **LOCAL_ONLY + model unavailable**: Fallback.

Future request classes can opt into cloud routing by passing `allowCloud = true` and providing a non-zero cost budget.
