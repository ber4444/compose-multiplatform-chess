#!/usr/bin/env bash
# Fetch the Filament iOS release (Metal static libs + headers) and stage the IBL assets for the
# default iOS target (issue #54). Reproducible for local dev and CI.
#
#   tools/fetch_filament_ios.sh [version]
#
# After running, build/run the Metal-native renderer with:
#   cd iosApp && xcodegen generate
#   xcodebuild -project iosApp.xcodeproj -scheme iosApp \
#     -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' build
#
# Filament's iOS release ships one .xcframework per library, each with an `ios-arm64` (device) and an
# `ios-arm64_x86_64-simulator` slice — so both the Apple-Silicon simulator and real devices build from
# the same download. filament.xcconfig selects the slice per SDK.
set -euo pipefail

VERSION="${1:-1.72.0}"        # pin deliberately; bump after verifying API compatibility
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/iosApp/iosApp/Filament/filament"
TARBALL="filament-v${VERSION}-ios.tgz"
URL="https://github.com/google/filament/releases/download/v${VERSION}/${TARBALL}"

echo "==> Filament iOS v${VERSION}"
echo "    from: $URL"
echo "    into: $DEST"

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Downloading…"
curl -fL --retry 3 -o "$TMP/$TARBALL" "$URL"

echo "==> Extracting…"
# The tgz expands to a top-level 'filament/' dir (include/, lib/arm64, lib/x86_64, …).
tar xzf "$TMP/$TARBALL" -C "$TMP"
rsync -a --delete "$TMP/filament/" "$DEST/"

echo "==> Staging IBL assets into the app bundle (papermill KTX, matching the Android backend)…"
ENV_SRC="$REPO_ROOT/app/src/commonMain/composeResources/files/env"
RES_DST="$REPO_ROOT/iosApp/iosApp/Resources"
cp "$ENV_SRC/papermill_ibl.ktx" "$RES_DST/papermill_ibl.ktx"
cp "$ENV_SRC/papermill_skybox_blurred.ktx" "$RES_DST/papermill_skybox_blurred.ktx"

echo "==> Available xcframeworks (reconcile with LIBRARY_SEARCH_PATHS/OTHER_LDFLAGS in filament.xcconfig):"
ls -1 "$DEST/lib" 2>/dev/null || echo "    (lib/ not found — check the release layout)"

echo "==> Done. Next: cd iosApp && xcodegen generate, then build the iosApp scheme."
