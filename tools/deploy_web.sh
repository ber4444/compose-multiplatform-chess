#!/usr/bin/env bash
# Build the production wasm bundle and deploy it to Fly.io as a static site (web/fly.toml).
#
#   tools/deploy_web.sh            # build + deploy
#   tools/deploy_web.sh --no-build # deploy the dist already in app/build
#
# Needs flyctl, logged in (`fly auth login`). The app must exist once:
#   fly apps create compose-chess-web
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
dist="$repo/app/build/dist/wasmJs/productionExecutable"

if [[ "${1:-}" != "--no-build" ]]; then
  "$repo/gradlew" -p "$repo" :app:wasmJsBrowserDistribution
fi

if [[ ! -f "$dist/index.html" || ! -f "$dist/app.js" ]]; then
  echo "No production dist at $dist; run without --no-build." >&2
  exit 1
fi

# A classic <script> cannot parse the bundle (it contains import.meta), and the page then renders
# blank with no build-time error. Refuse to ship that.
if ! grep -q '<script type="module" src="app.js">' "$dist/index.html"; then
  echo "index.html does not load app.js as a module script; the deployed page would be blank." >&2
  exit 1
fi

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp "$repo/web/Dockerfile" "$repo/web/Caddyfile" "$stage/"
mkdir "$stage/site"
# Source maps are not shipped: they only help with devtools open, and they embed the sources.
rsync -a --exclude '*.map' "$dist/" "$stage/site/"

fly deploy "$stage" --config "$repo/web/fly.toml" --dockerfile "$stage/Dockerfile" --remote-only
