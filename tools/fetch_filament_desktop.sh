#!/usr/bin/env bash
# Fetch the Filament desktop release (native C++ headers/libs/tools) used by the
# Compose Desktop 3D board backend.
#
#   tools/fetch_filament_desktop.sh [version]
#
# The fetched release is intentionally gitignored, matching tools/fetch_filament_ios.sh.
set -euo pipefail

VERSION="${1:-1.72.0}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/desktopMain/filament/filament"

uname_s="$(uname -s)"
case "$uname_s" in
  Darwin) PLATFORM="mac" ;;
  Linux) PLATFORM="linux" ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
  *)
    echo "Unsupported desktop Filament host: $uname_s" >&2
    exit 1
    ;;
esac

TARBALL="filament-v${VERSION}-${PLATFORM}.tgz"
URL="https://github.com/google/filament/releases/download/v${VERSION}/${TARBALL}"

echo "==> Filament desktop v${VERSION} (${PLATFORM})"
echo "    from: $URL"
echo "    into: $DEST"

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Downloading..."
curl -fL --retry 3 -o "$TMP/$TARBALL" "$URL"

echo "==> Extracting..."
tar xzf "$TMP/$TARBALL" -C "$TMP"
rsync -a --delete "$TMP/filament/" "$DEST/"

test -d "$DEST/include"
test -d "$DEST/lib"

echo "==> Staged Filament desktop payload:"
find "$DEST" -maxdepth 2 \( -type f -o -type l \) | sed "s#^$REPO_ROOT/##" | sort | head -80
echo "==> Done."
