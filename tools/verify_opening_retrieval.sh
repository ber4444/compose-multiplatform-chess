#!/usr/bin/env bash
# Smoke-tests the deployed opening explainer against eight real openings.
#
# Checks the two things unit tests cannot: that the *live* corpus resolves each position to the
# right ECO (retrieval correctness end to end), and which composer answered. Embedding-only
# retrieval used to return the wrong opening on all eight while every answer still looked fluent
# and correctly cited -- see the Cloud retrieval section in CLAUDE.md.
#
# Sends eco = null, exactly as the shipping clients do, so the server must identify the opening
# from the moves alone.
#
# Usage:  tools/verify_opening_retrieval.sh
#         COACH_BASE_URL=http://localhost:8080 tools/verify_opening_retrieval.sh
#
# The corpus ships inside the deployed image (BuildCorpusIndexMain bakes it at build time), so this
# needs no seeding step -- a successful deploy is enough. `GET /health` reports the index's row count
# and version if you want to confirm which corpus answered.
#
# NOTE: n=8, one call each. Provider hiccups (503/timeout) move the llm-v1 count around by several
# cases between runs, so treat that number as a wiring check, not a measurement -- use
# `./gradlew :evals:run` for anything you intend to quote.
set -uo pipefail

BASE="${COACH_BASE_URL:-https://compose-chess-opening-coach.fly.dev}"

# fen | movesSan (JSON array) | label | expected ECO
CASES=(
  'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2|["e4","c5"]|Sicilian|B20'
  'rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","e6"]|French|C00'
  'rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2|["e4","c6"]|Caro-Kann|B10'
  'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3|["e4","e5","Nf3","Nc6","Bb5"]|Ruy Lopez|C60'
  'r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3|["e4","e5","Nf3","Nc6","Bc4"]|Italian|C50'
  'rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2|["d4","d5","c4"]|Queens Gambit|D06'
  'rnbqkb1r/pppppp1p/5np1/8/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3|["d4","Nf6","c4","g6"]|King'"'"'s Indian|E60'
  'rnbqkbnr/pppppppp/8/8/2P5/8/PP1PPPPP/RNBQKBNR b KQkq c3 0 1|["c4"]|English|A10'
)

printf '%-14s %-6s %-14s %s\n' CASE ECO COMPOSER TEXT
printf '%s\n' "----------------------------------------------------------------------------------"

llm=0; template=0; wrong_eco=0

for row in "${CASES[@]}"; do
  IFS='|' read -r fen moves label expected <<< "$row"
  body=$(printf '{"fen":"%s","movesSan":%s,"eco":null}' "$fen" "$moves")
  resp=$(curl -s -m 45 -X POST "$BASE/v1/openings/explain" \
    -H 'Content-Type: application/json' -d "$body")

  read -r composer eco text <<< "$(printf '%s' "$resp" | python3 -c '
import sys, json, re
d = json.load(sys.stdin)
ps = d.get("passages", [])
eco = ps[0]["title"].split()[0] if ps else "-"
print(d.get("composerId", "-"), eco, re.sub(r"\s+", " ", d.get("text", ""))[:70])
' 2>/dev/null || echo "- - <parse-error>")"

  [ "$composer" = "llm-v1" ] && llm=$((llm+1)) || template=$((template+1))
  [ "$eco" = "$expected" ] || { wrong_eco=$((wrong_eco+1)); eco="$eco!=$expected"; }

  printf '%-14s %-6s %-14s %s\n' "$label" "$eco" "$composer" "$text"
done

total=$((llm + template))
printf '\n%s\n' "----------------------------------------------------------------------------------"
printf 'llm-v1 (passed validation): %d/%d\n' "$llm" "$total"
printf 'template-v1 (fell back):    %d/%d\n' "$template" "$total"
printf 'wrong ECO retrieved:        %d/%d\n' "$wrong_eco" "$total"

if [ "$llm" -eq 0 ]; then
  printf '\n%s\n' "NOTE: every row is template-v1. That means EITHER the model failed validation on"
  printf '%s\n'   "      all 8 cases OR no COACH_LLM_API_KEY is set on the server, in which case the"
  printf '%s\n'   "      LLM composer was never constructed and this run measures nothing about it."
  printf '%s\n'   "      Check: fly ssh console -C 'printenv COACH_LLM_API_KEY' | head -c 8"
fi
