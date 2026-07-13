# Rules Q&A retrieval decision

The rules corpus contains only a few dozen short passages. Shipping a second neural model solely
for query embeddings would add binary size, memory pressure, initialization work, and another
platform delivery path on top of Cactus on Android and Foundation Models on iOS.

For v1, the app uses the plan's permitted fallback: a deterministic BM25 scan over the same
bundled corpus on every target. The build validates the TSV and generates common Kotlin data, so
lookup is offline, has no database, and cannot diverge across Android, iOS, desktop, and Wasm.
The fixed-query common test covers twelve representative rules questions. A neural embedding model
should only replace BM25 after device measurements show a retrieval-quality gain worth its size
and latency cost.
