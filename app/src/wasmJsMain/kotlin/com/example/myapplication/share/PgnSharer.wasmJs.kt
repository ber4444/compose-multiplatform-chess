package com.example.myapplication.share

/**
 * Wasm/JS [PgnSharer]. Creates a `Blob` from the PGN, an object URL, and a synthetic
 * `<a download="suggestedFileName">` click to trigger a browser download. Fire-and-forget — no
 * `Promise.await` (avoids the known wasm `Promise.await` GC pitfall noted in the plan). The object
 * URL is revoked after the click.
 */
class WasmPgnSharer : PgnSharer {
    override fun share(pgn: String, suggestedFileName: String) {
        downloadText(pgn, suggestedFileName)
    }
}

@JsFun("(text, filename) => { const blob = new Blob([text], {type: 'application/x-chess-pgn'}); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url); }")
private external fun downloadText(text: String, filename: String)

/** Factory mirroring `wasmBoard3DSupport(...)` — constructed at the wasm entry point. */
fun wasmPgnSharer(): PgnSharer = WasmPgnSharer()
