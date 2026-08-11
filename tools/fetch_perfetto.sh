#!/usr/bin/env bash
# Fetch the Perfetto `trace_processor_shell` prebuilt used to run SQL against the macrobenchmark
# traces (macrobenchmark/build/outputs/connected_android_test_additional_output/*.perfetto-trace).
#
#   tools/fetch_perfetto.sh [version]
#   tools/perfetto/trace_processor_shell -q query.sql some.perfetto-trace
#
# This replaces the vendored `perfetto/trace_processor.py`, which was Google's auto-generated
# amalgamated Python downloader (Apache-2.0, marked DO NOT EDIT). That file was never source: it
# only picks a host slice out of a pinned manifest and curls the real binary out of Google's GCS
# bucket — exactly what this script does, minus 299 lines of vendored codegen in git.
#
# Upstream: https://github.com/google/perfetto (Apache-2.0), tools/trace_processor.
# The binaries come from Perfetto's own release bucket; the SHA-256s below are copied verbatim from
# the v49.0 manifest that the vendored file carried, so the fetch stays pinned and verifiable.
#
# The fetched binary is intentionally gitignored, matching tools/fetch_filament_desktop.sh.
set -euo pipefail

PINNED_VERSION="v49.0"
VERSION="${1:-$PINNED_VERSION}"   # pin deliberately; bump with the checksums below, together
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/tools/perfetto"

# Host slice + the pinned SHA-256 for it. Perfetto publishes one binary per arch; there is no
# tarball to unpack, so this is a plain download + chmod.
uname_s="$(uname -s)"
uname_m="$(uname -m)"
BIN="trace_processor_shell"
case "$uname_s/$uname_m" in
  Darwin/arm64)          ARCH="mac-arm64"     SHA256="9c325030078bc4de8693083c9e4e2b72c83ca694c3a4ef8cc1bd9c29fb421815" ;;
  Darwin/x86_64)         ARCH="mac-amd64"     SHA256="867c70800cfe81c2640f2aae8bb58eca68fa1389a3258a25c285ee5510edbbe3" ;;
  Linux/x86_64)          ARCH="linux-amd64"   SHA256="6af6f87e6521eec186e74c68c0c6eeeeb557556e368d0e4f563be5ce5d9d936b" ;;
  Linux/aarch64)         ARCH="linux-arm64"   SHA256="ecb6a1a073eb4bbfe36af56ab4406671e8febe02fb4c6dcef73fb1fe5d817fad" ;;
  Linux/armv6l|Linux/armv7l|Linux/armv8l)
                         ARCH="linux-arm"     SHA256="da0c361d4a2c8d8b2d1ffd45cd388d964cc58b09e8e41f48aa045ed357510755" ;;
  MINGW*/*|MSYS*/*|CYGWIN*/*)
                         ARCH="windows-amd64" SHA256="a881f3e2d4c6131493e85bfd1f36d1efe58e1478e2991825418d5d21614c1e48"
                         BIN="trace_processor_shell.exe" ;;
  *)
    echo "Unsupported Perfetto host: $uname_s/$uname_m" >&2
    exit 1
    ;;
esac

URL="https://commondatastorage.googleapis.com/perfetto-luci-artifacts/${VERSION}/${ARCH}/${BIN}"

echo "==> Perfetto trace_processor_shell ${VERSION} (${ARCH})"
echo "    from: $URL"
echo "    into: $DEST/$BIN"

# Already fetched and intact? Skip the download — this script is meant to be safe to re-run.
if [ -x "$DEST/$BIN" ] && [ "$VERSION" = "$PINNED_VERSION" ] &&
   [ "$(shasum -a 256 "$DEST/$BIN" | cut -d' ' -f1)" = "$SHA256" ]; then
  echo "==> Already present and checksum matches. Nothing to do."
  exit 0
fi

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Downloading…"
curl -fL --retry 3 -o "$TMP/$BIN" "$URL"

# The checksums above belong to $PINNED_VERSION only. An explicit version argument is allowed
# (handy for reproducing someone else's trace tooling) but cannot be verified against them, so say
# so rather than silently comparing against the wrong pin.
if [ "$VERSION" = "$PINNED_VERSION" ]; then
  echo "==> Verifying SHA-256…"
  actual="$(shasum -a 256 "$TMP/$BIN" | cut -d' ' -f1)"
  if [ "$actual" != "$SHA256" ]; then
    echo "Checksum mismatch for $URL" >&2
    echo "  expected: $SHA256" >&2
    echo "  actual:   $actual" >&2
    exit 1
  fi
else
  echo "==> Skipping checksum: $VERSION is not the pinned $PINNED_VERSION."
fi

chmod +x "$TMP/$BIN"
mv "$TMP/$BIN" "$DEST/$BIN"

"$DEST/$BIN" --version || true

echo "==> Done. Query a trace with:"
echo "    tools/perfetto/$BIN -q your_query.sql path/to/trace.perfetto-trace"
