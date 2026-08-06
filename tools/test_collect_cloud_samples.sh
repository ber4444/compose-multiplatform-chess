#!/usr/bin/env bash
set -euo pipefail

tmpdir=$(mktemp -d)
trap 'pkill -P $$ || true; rm -rf "$tmpdir"' EXIT

start_fixture_server() {
  local dir="$1"
  cat > "$dir/fixture_server.py" << 'EOF'
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/v1/openings/explain":
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({
                "text": "This is a test opening explanation.",
                "diagnostics": {
                    "releaseVersion": "test-release",
                    "corpus": {"ready": True},
                    "retrievedPassageIds": ["test-id"],
                    "composerId": "test-composer",
                    "finishReason": "completed",
                    "latencyMs": 10,
                    "rawProviderOutput": "raw test text"
                }
            }).encode('utf-8'))
        elif self.path == "/v1/positions/chat/stream":
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            
            token_event = {"type": "token", "text": "This is a chat stream response."}
            self.wfile.write(f"data: {json.dumps(token_event)}\n\n".encode('utf-8'))
            
            done_event = {"type": "done", "composerId": "test-composer"}
            self.wfile.write(f"data: {json.dumps(done_event)}\n\n".encode('utf-8'))
            
            diag_event = {
                "type": "diagnostics",
                "diagnostics": {
                    "releaseVersion": "test-release",
                    "corpus": {"ready": True},
                    "retrievedPassageIds": ["test-id"],
                    "composerId": "test-composer",
                    "finishReason": "completed",
                    "latencyMs": 15,
                    "rawProviderOutput": "raw chat output"
                }
            }
            self.wfile.write(f"data: {json.dumps(diag_event)}\n\n".encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

httpd = HTTPServer(('127.0.0.1', 0), Handler)
with open("port.txt", "w") as f:
    f.write(str(httpd.server_port))
httpd.serve_forever()
EOF
  python3 "$dir/fixture_server.py" &
  while [ ! -f port.txt ]; do sleep 0.1; done
  local port=$(cat port.txt)
  rm port.txt
  export FIXTURE_URL="http://127.0.0.1:$port"
}

start_fixture_server "$tmpdir"
bash tools/collect_cloud_samples.sh "$FIXTURE_URL" "$tmpdir/output"
test -f "$tmpdir/output/summary.json"
jq -e '.samples[0].opening.diagnostics.releaseVersion == "test-release"' "$tmpdir/output/summary.json"
jq -e '.samples[0].chat.terminalEvent == "done"' "$tmpdir/output/summary.json"
