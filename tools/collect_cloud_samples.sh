#!/usr/bin/env bash
# Collects full Opening Explainer and Position Chat responses for the R-1 hand-review
# (for manual evaluation).
#
# This script COLLECTS. It does not judge, and nothing automated can: R-1 asks whether the output
# tells a player something they could not see, and every check in this repo verifies faithfulness
# instead — which text that copies its source, or says nothing, satisfies trivially.
#
# Deliberately prints responses in FULL. tools/verify_opening_retrieval.sh truncates to 70 chars,
# which is enough to spot a broken pipeline and useless for judging prose.
#
# Usage: tools/collect_cloud_samples.sh [base-url] [output-directory]
set -euo pipefail

BASE="${1:-https://compose-chess-opening-coach.fly.dev}"
if [ $# -ge 2 ]; then
  OUTDIR="$2"
else
  OUTDIR="samples-$(date +%Y%m%d-%H%M%S)"
fi

if [ -e "$OUTDIR" ]; then
  echo "Error: Output directory '$OUTDIR' already exists." >&2
  exit 1
fi

mkdir -p "$OUTDIR"

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


for i in "${!POSITIONS[@]}"; do
  entry="${POSITIONS[$i]}"
  IFS='|' read -r fen moves name question <<<"$entry"
  
  idx=$(printf "%02d" "$i")
  
  payload_explain=$(jq -n --arg fen "$fen" --argjson moves "$moves" '{fen: $fen, movesSan: $moves, eco: null, locale: "en-US"}')
  curl -f -s -m 60 -X POST "$BASE/v1/openings/explain" \
    -H 'Content-Type: application/json' \
    -d "$payload_explain" \
    > "$OUTDIR/${idx}_opening.json"

  payload_chat=$(jq -n --arg fen "$fen" --argjson moves "$moves" --arg q "$question" '{fen: $fen, movesSan: $moves, eco: null, history: [], userMessage: $q, locale: "en-US"}')
  curl -f -sN -m 60 -X POST "$BASE/v1/positions/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "$payload_chat" \
    > "$OUTDIR/${idx}_chat.txt"
done

python3 -c '
import json, sys, os, glob, re

outdir = sys.argv[1]

def sanitize(text):
    text = re.sub(r"\[([a-zA-Z0-9_-]+)\]", lambda m: m.group(0) if re.fullmatch(r"move-\d+", m.group(1)) else "", text)
    text = re.sub(r"[ \t]+([.,!?])", r"\1", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()

samples = []
positions_str = sys.argv[2]
positions = positions_str.strip().split("\n")

for i, entry in enumerate(positions):
    if not entry.strip(): continue
    parts = entry.split("|")
    fen, moves, name, question = parts[0], parts[1], parts[2], parts[3]
    idx = f"{i:02d}"
    
    with open(f"{outdir}/{idx}_opening.json") as f:
        opening_raw = json.load(f)
    
    opening_text = opening_raw.get("text", "")
    opening_diag = opening_raw.get("diagnostics")
    if opening_diag is None:
        print(f"Error: missing diagnostics in opening for {name}", file=sys.stderr)
        sys.exit(1)
        
    chat_text_parts = []
    chat_diag = None
    terminal_event = None
    
    with open(f"{outdir}/{idx}_chat.txt") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("data:"): continue
            event = json.loads(line[5:].strip())
            evt_type = event.get("type")
            if evt_type == "token" and event.get("text"):
                chat_text_parts.append(event["text"])
            elif evt_type in ("done", "fallback"):
                terminal_event = evt_type
            elif evt_type == "diagnostics":
                chat_diag = event.get("diagnostics")

    if not terminal_event:
        print(f"Error: missing terminal event in chat for {name}", file=sys.stderr)
        sys.exit(1)
    if not chat_diag:
        print(f"Error: missing diagnostics in chat for {name}", file=sys.stderr)
        sys.exit(1)

    chat_text = "".join(chat_text_parts)

    samples.append({
        "request": {
            "name": name,
            "movesSan": moves,
            "question": question
        },
        "opening": {
            "visibleText": sanitize(opening_text),
            "diagnostics": opening_diag
        },
        "chat": {
            "terminalEvent": terminal_event,
            "visibleText": sanitize(chat_text),
            "diagnostics": chat_diag
        }
    })
    
    print("════════════════════════════════════════════════════════════════")
    print(f"## {name} — {moves}")
    print()
    print("--- OPENING EXPLAINER ---")
    print(f"Diagnostics: {opening_diag.get("releaseVersion")} | {opening_diag.get("composerId")} | {opening_diag.get("finishReason")} | Latency {opening_diag.get("latencyMs")}ms")
    print()
    print(sanitize(opening_text))
    print("\n")
    print(f"--- POSITION CHAT — \"{question}\" ---")
    print(f"Diagnostics: {chat_diag.get("releaseVersion")} | {chat_diag.get("composerId")} | {chat_diag.get("finishReason")} | Latency {chat_diag.get("latencyMs")}ms")
    print()
    print(sanitize(chat_text))
    print()

with open(f"{outdir}/summary.json", "w") as f:
    json.dump({"samples": samples}, f, indent=2)

print("════════════════════════════════════════════════════════════════")
print("Now read them. The question is usefulness, not correctness:")
print("  does each one tell a player something they could not see on the board?")
print("Record the verdict.")
' "$OUTDIR" "$(printf "%s\n" "${POSITIONS[@]}")"
