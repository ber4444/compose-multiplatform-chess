#!/usr/bin/env bash
# Collects full Opening Explainer and Position Chat responses for the R-1 hand-review
# (docs/plans/cloud-eval-honesty-followups.md).
#
# This script COLLECTS. It does not judge, and nothing automated can: R-1 asks whether the output
# tells a player something they could not see, and every check in this repo verifies faithfulness
# instead — which text that copies its source, or says nothing, satisfies trivially.
#
# Deliberately prints responses in FULL. tools/verify_opening_retrieval.sh truncates to 70 chars,
# which is enough to spot a broken pipeline and useless for judging prose.
#
# Usage: tools/collect_cloud_samples.sh [base-url] > samples.txt
set -euo pipefail

BASE="${1:-https://compose-chess-opening-coach.fly.dev}"

# Show what the *user* sees, not what the wire carries. `:app` runs every cloud response through
# CitationSanitizer before display, so reviewing raw `[lichess-c-955-c55]` ids means reviewing text
# no player will ever read — and they are noise in a judgement about prose.
#
# Mirrors CitationSanitizer.sanitize: strip `[a-zA-Z0-9_-]` tags, keep `[move-N]` (RAG-2's
# evaluative citations, which the UI linkifies), then close up the whitespace and dangling
# punctuation the removal leaves behind. Keep the two in step if that file changes.
sanitize() {
  python3 -c '
import re, sys
text = sys.stdin.read()
text = re.sub(r"\[([a-zA-Z0-9_-]+)\]", lambda m: m.group(0) if re.fullmatch(r"move-\d+", m.group(1)) else "", text)
text = re.sub(r"[ \t]+([.,!?])", r"\1", text)
text = re.sub(r"[ \t]{2,}", " ", text)
sys.stdout.write(text)
'
}

# fen | movesSan JSON | opening name | chat question
POSITIONS=(
  'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","c5"]|Sicilian Defence|Why is this popular for Black?'
  'rnbqkbnr/pppp1ppp/8/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3|["e4","e5","Nf3","Nc6","Bc4"]|Italian Game|What is White trying to do with the bishop on c4?'
  'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3|["e4","e5","Nf3","Nc6","Bb5"]|Ruy Lopez|Should Black be worried about losing the e5 pawn?'
  'rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2|["d4","d5","c4"]|Queens Gambit|Is the gambit pawn really free?'
  'rnbqkb1r/pppppp1p/5np1/8/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3|["d4","Nf6","c4","g6"]|Kings Indian|Why does Black give up the centre?'
  'rnbqkbnr/pp1ppppp/8/2p5/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 2|["Nf3","c5"]|Zukertort|What structure is this heading for?'
  'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","e5"]|Open Game|What are the main plans here?'
  'rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2|["e4","d5"]|Scandinavian|Is recapturing with the queen bad?'
  'rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","c6"]|Caro-Kann|How does this differ from the French?'
  'rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","e6"]|French Defence|What is the problem piece for Black?'
)

for entry in "${POSITIONS[@]}"; do
  IFS='|' read -r fen moves name question <<<"$entry"

  echo "════════════════════════════════════════════════════════════════"
  echo "## $name — $moves"
  echo

  echo "--- OPENING EXPLAINER ---"
  # eco is null on purpose: that is what the shipping clients send, so the server has to identify
  # the opening from the moves alone (see CLAUDE.md § Cloud retrieval).
  curl -s -X POST "$BASE/v1/openings/explain" \
    -H 'Content-Type: application/json' \
    -d "{\"fen\":\"$fen\",\"movesSan\":$moves,\"eco\":null,\"locale\":\"en-US\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["text"])' | sanitize
  echo
  echo

  echo "--- POSITION CHAT — \"$question\" ---"
  # Expect ~11s of silence and then the whole answer at once. That is the provider batching
  # (P1-2), not a fault in this call, and it is not what R-1 is judging.
  curl -sN -m 60 -X POST "$BASE/v1/positions/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "{\"fen\":\"$fen\",\"movesSan\":$moves,\"eco\":null,\"history\":[],\"userMessage\":\"$question\",\"locale\":\"en-US\"}" \
    | python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if not line.startswith("data:"):
        continue
    event = json.loads(line[5:].strip())
    if event.get("text"):
        sys.stdout.write(event["text"])
print()
' | sanitize
  echo
done

echo "════════════════════════════════════════════════════════════════"
echo "Now read them. The question is usefulness, not correctness:"
echo "  does each one tell a player something they could not see on the board?"
echo "Record the verdict in docs/plans/cloud-eval-honesty-followups.md § R-1."
