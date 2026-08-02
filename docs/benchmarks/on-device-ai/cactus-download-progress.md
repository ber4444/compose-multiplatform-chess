# Cactus model-download progress (B18 prerequisite)

Status: **Answered — Cactus exposes no download progress. Progress must be synthesized.**

B18 ("first-run model download with visible progress") assumed the Cactus SDK could report download
progress. It cannot. This is the finding that determines B18's shape, recorded here because it is a
fact about a third-party binary that no code in this repo can assert.

## What was checked

`com.cactuscompute:cactus:1.4.1-beta`, the version pinned in
`onDeviceAi/build.gradle.kts`. Both the compiled classes and the published sources were inspected
from the Gradle cache:

```bash
unzip -o -q ~/.gradle/caches/modules-2/files-2.1/com.cactuscompute/cactus-android/1.4.1-beta/*/library-release.aar classes.jar
javap -cp classes.jar com.cactus.CactusLM
```

## Findings

| Question | Answer |
|---|---|
| Does `downloadModel` accept a progress callback? | **No.** The only overloads are `downloadModel(String, Continuation)` and its `$default` bridge. |
| Is there any other progress hook? | **No.** It delegates to `expect suspend fun downloadAndExtractModels(tasks: List<DownloadTask>): Boolean`, which also takes no callback. |
| Is there a progress type in the SDK? | Yes, and it is **dead code**. `commonMain/CactusStructs.kt` declares `typealias CactusProgressCallback = (Double?, String, Boolean) -> Unit`, referenced nowhere in the SDK. Do not plan around it — it looks like an API that exists and is not one. |
| Why can't the download be observed? | The Android actual (`androidMain/ModelDownloader.android.kt`) is a bare `input.copyTo(output)` over an `HttpURLConnection` into `filesDir/models/<filename>`. No counter, no emission. |

## Consequence for B18

Two options, and the second is viable:

1. **Indeterminate spinner + honest copy.** Costs nothing, tells the user nothing about how long a
   ~200 MB fetch will take. This is what ships today.
2. **Synthesize determinate progress by polling the partial file.** All the required pieces are
   public API:
   - `CactusLM.getModels(): List<CactusModel>` → the model row for `gemma3-270m`.
   - `CactusModel` carries `size_mb: Int`, `download_url`, `slug`, `isDownloaded`.
   - The destination filename is `download_url.split('?').first().split('/').last()`, and the file
     lands under `CactusModelManager.getModelsDirectory()`.
   - `modelExists(modelName): Boolean` reports completion.

   Poll `File.length()` against `size_mb * 1024 * 1024` on an interval and publish a fraction.

   **Cost:** this depends on the SDK's on-disk layout, which is not part of its documented contract
   and can change in a patch release. A Cactus upgrade must re-verify the path and filename
   derivation above. That is the trade being made, and it should be made deliberately.

## What shipped instead (interim)

The download no longer blocks anything, which was the larger half of B18:

- `CactusTextGenerator.warmup()` returns as soon as the fetch starts; `status()` reports
  `AiAvailability.Downloading` while it runs.
- The orchestrator is attached *before* warmup completes, so a coached move during the download
  routes normally, sees `Downloading`, and renders the deterministic line
  (`FallbackPresentation.Silent`) — the board and coach are fully usable throughout.
- One shared `initJob` guards initialization. This matters more than it looks: `downloadModel`
  internally hops to `Dispatchers.IO`, releasing the generator's single-threaded engine dispatcher
  mid-flight, so a naive re-entrant `ensureInitialized()` would start a **second** 200 MB download
  of the same model.
