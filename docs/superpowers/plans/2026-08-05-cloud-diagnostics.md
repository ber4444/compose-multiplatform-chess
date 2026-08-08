# Cloud Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make every cloud answer and sample artifact identify the deployed release, corpus, retrieval, composition outcome, provider output, and terminal state.

**Architecture:** Add defaulted diagnostic DTOs to coachApi, attach them to opening responses and final chat SSE events without changing UI behavior. The server produces request-scoped metadata; a read-only verifier compares a corpus manifest with PostgreSQL state; collector and evals consume exact records.

**Tech Stack:** Kotlin/JVM 21, Ktor 3.4.3, kotlinx.serialization, PostgreSQL/pgvector, Bash/Python 3, Gradle/JUnit 5.

## Global Constraints

- Preserve player-visible text, fallback behavior, token flow, and platform glue.
- Add only defaulted or optional wire fields; the app ignores an unknown chat diagnostics event.
- Preserve grounding gates and mutation tests; a missing provider is unavailable, never passing.
- Retain raw provider output only in server diagnostics and collector artifacts, never in app state, history, or UI.
- verifyCorpus is read-only and does not load ONNX.

---

### Task 1: Add shared models and a JSON health report

**Files:**

- Modify: coachApi/src/commonMain/kotlin/com/example/coachapi/CoachApiModels.kt
- Modify: server/src/main/kotlin/com/example/coachserver/Application.kt
- Modify: server/openapi.yaml
- Test: server/src/test/kotlin/com/example/coachserver/ApplicationTest.kt
- Test: server/src/test/kotlin/com/example/coachserver/OpenApiContractTest.kt

**Interfaces:**

- CorpusDiagnostics(ready, seedVersion?, rowCount?, finalSourceId?)
- CloudDiagnostics(releaseVersion, corpus, retrievedPassageIds, composerId, finishReason, latencyMs, completionTokens?, rawProviderOutput?)
- HealthReport(status, releaseVersion, corpus)
- OpeningExplainResponse.diagnostics: CloudDiagnostics? = null
- ChatStreamEvent.TYPE_DIAGNOSTICS and diagnostics: CloudDiagnostics? = null

- [ ] **Step 1: Write failing health and compatibility tests**

~~~kotlin
@Test
fun healthReportsConfiguredReleaseAndUnavailableCorpus() = testApplication {
    application { openingCoachModule(testDependencies(listOf(passage)), releaseVersion = "git-abc123") }
    val body = jsonClient.get("/health").body<HealthReport>()
    assertEquals(HealthReport("ok", "git-abc123", CorpusDiagnostics(ready = false)), body)
}

@Test
fun legacyOpeningResponseWithoutDiagnosticsDeserializes() {
    assertEquals(null, REQUEST_JSON.decodeFromString<OpeningExplainResponse>(legacyJson).diagnostics)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :server:test --tests "com.example.coachserver.ApplicationTest.healthReportsConfiguredReleaseAndUnavailableCorpus"

Expected: FAIL because health is text and the DTOs do not exist.

- [ ] **Step 3: Implement the minimum wire contract**

~~~kotlin
@Serializable
data class HealthReport(val status: String, val releaseVersion: String, val corpus: CorpusDiagnostics)

get("/health") {
    call.respond(HealthReport("ok", releaseVersion, corpusStatusReader.readOrUnavailable()))
}
~~~

Provide releaseVersion = "unknown" and an exception-safe unavailable corpus reader default. Add response and event schemas to OpenAPI independently of routing.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :server:test --tests "com.example.coachserver.ApplicationTest" --tests "com.example.coachserver.OpenApiContractTest"

Expected: PASS, including contract validation.

- [ ] **Step 5: Commit**

Run: git add coachApi/src/commonMain/kotlin/com/example/coachapi/CoachApiModels.kt server/src/main/kotlin/com/example/coachserver/Application.kt server/openapi.yaml server/src/test/kotlin/com/example/coachserver/ApplicationTest.kt server/src/test/kotlin/com/example/coachserver/OpenApiContractTest.kt && git commit -m "feat: expose cloud diagnostic wire models"

### Task 2: Read and verify corpus state without writes

**Files:**

- Create: server/src/main/kotlin/com/example/coachserver/CorpusStatus.kt
- Create: server/src/main/kotlin/com/example/coachserver/VerifyCorpusMain.kt
- Modify: server/build.gradle.kts
- Test: server/src/test/kotlin/com/example/coachserver/CorpusStatusTest.kt
- Modify: README.md

**Interfaces:**

- fun interface CorpusStatusReader { fun read(): CorpusDiagnostics }
- PostgresCorpusStatusReader(DataSource) reads only corpus_seed_state WHERE singleton = TRUE.
- verifyCorpus(dataSource, manifest): CorpusDiagnostics throws for absent/mismatched version, count, or final source.
- :server:verifyCorpus calculates the local/current-image manifest and prints matching state.

- [ ] **Step 1: Write failing verifier tests**

~~~kotlin
@Test
fun matchingSeededManifestVerifies() {
    repository.replaceCorpus(rows, manifest)
    assertEquals(CorpusDiagnostics(true, manifest.version, manifest.expectedRowCount, manifest.finalSourceId), verifyCorpus(dataSource, manifest))
}

@Test
fun differentFinalSourceFailsVerification() {
    insertSeedState(manifest.version, manifest.expectedRowCount, "wrong-source")
    assertFailsWith<IllegalStateException> { verifyCorpus(dataSource, manifest) }
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :server:test --tests "com.example.coachserver.CorpusStatusTest"

Expected: FAIL because the reader/verifier does not exist.

- [ ] **Step 3: Implement reader, verifier, and JavaExec task**

~~~kotlin
fun verifyCorpus(dataSource: DataSource, manifest: CorpusSeedManifest): CorpusDiagnostics {
    val actual = PostgresCorpusStatusReader(dataSource).read()
    check(actual.ready) { "Corpus seed state is absent" }
    check(actual.seedVersion == manifest.version) { "Corpus seed version mismatch" }
    check(actual.rowCount == manifest.expectedRowCount) { "Corpus row count mismatch" }
    check(actual.finalSourceId == manifest.finalSourceId) { "Corpus final source mismatch" }
    return actual
}
~~~

~~~kotlin
tasks.register<JavaExec>("verifyCorpus") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.example.coachserver.VerifyCorpusMain")
}
~~~

VerifyCorpusMain requires DATABASE_URL and optional COACH_CORPUS_DIR; it must not apply schema or load an embedding model.

- [ ] **Step 4: Document and verify GREEN**

Document DATABASE_URL=… ./gradlew :server:verifyCorpus after serving-image seeding. Run: ./gradlew :server:test --tests "com.example.coachserver.CorpusStatusTest"

Expected: PASS.

- [ ] **Step 5: Commit**

Run: git add server/src/main/kotlin/com/example/coachserver/CorpusStatus.kt server/src/main/kotlin/com/example/coachserver/VerifyCorpusMain.kt server/build.gradle.kts server/src/test/kotlin/com/example/coachserver/CorpusStatusTest.kt README.md && git commit -m "feat: verify deployed corpus state"

### Task 3: Produce opening diagnostics and capture all provider outputs

**Files:**

- Modify: server/src/main/kotlin/com/example/coachserver/Composers.kt
- Modify: server/src/main/kotlin/com/example/coachserver/OpeningService.kt
- Modify: server/src/main/kotlin/com/example/coachserver/Application.kt
- Test: server/src/test/kotlin/com/example/coachserver/OpeningServiceTest.kt
- Test: server/src/test/kotlin/com/example/coachserver/LlmComposerHttpTest.kt

**Interfaces:**

- CompositionResult(text: ComposedText, finishReason: String, completionTokens: Int? = null, rawProviderOutput: String? = null)
- Terminal reasons: completed, budget_rejected, provider_error, validator_rejected.
- OpeningService attaches actual retrieved IDs and non-negative measured latency.

- [ ] **Step 1: Write failing composition tests**

~~~kotlin
@Test
fun openingDiagnosticsNameRetrievalAndDeterministicOutcome() = runBlocking {
    val body = OpeningService(testDependencies(listOf(passage))).explain(request)
    assertEquals(listOf("lichess-c20"), body.diagnostics!!.retrievedPassageIds)
    assertEquals("template-v1", body.diagnostics!!.composerId)
    assertEquals("completed", body.diagnostics!!.finishReason)
}

@Test
fun acceptedProviderTextIsRetained() {
    val result = LlmComposer(fixedClient(validText), TemplateComposer()).compose(request, listOf(passage))
    assertEquals(validText, result.rawProviderOutput)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :server:test --tests "com.example.coachserver.OpeningServiceTest" --tests "com.example.coachserver.LlmComposerHttpTest"

Expected: FAIL because composition results and response diagnostics do not exist.

- [ ] **Step 3: Implement explicit outcomes**

Every LLM exit constructs CompositionResult. A valid/rejected completion retains complete raw text and usage; an exception has no raw text. Preserve deterministic fallback text. Emit one structured log per attempt containing release, retrieved IDs, composer, terminal reason, usage, and complete raw output.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :server:test --tests "com.example.coachserver.OpeningServiceTest" --tests "com.example.coachserver.LlmComposerHttpTest"

Expected: PASS for successful, budget, provider, and validator paths.

- [ ] **Step 5: Commit**

Run: git add server/src/main/kotlin/com/example/coachserver/Composers.kt server/src/main/kotlin/com/example/coachserver/OpeningService.kt server/src/main/kotlin/com/example/coachserver/Application.kt server/src/test/kotlin/com/example/coachserver/OpeningServiceTest.kt server/src/test/kotlin/com/example/coachserver/LlmComposerHttpTest.kt && git commit -m "feat: attach opening composition diagnostics"

### Task 4: Emit a final diagnostic event for chat

**Files:**

- Modify: server/src/main/kotlin/com/example/coachserver/PositionChatService.kt
- Modify: server/src/main/kotlin/com/example/coachserver/Application.kt
- Test: server/src/test/kotlin/com/example/coachserver/PositionChatRouteTest.kt
- Test: server/src/test/kotlin/com/example/coachserver/ChatStreamingChunkCountTest.kt

**Interfaces:**

- ChatChunk.Diagnostics(val diagnostics: CloudDiagnostics) maps to TYPE_DIAGNOSTICS.
- PositionChatService.chat emits diagnostics strictly after its existing Done or Fallback.
- Chat captures raw provider chunks separately from cleaned visible chunks and records the same terminal reasons.

- [ ] **Step 1: Write failing ordering tests**

~~~kotlin
@Test
fun validatedChatEmitsDiagnosticsAfterDone() = testApplication {
    val events = postChatAndParseEvents(validProviderService())
    assertEquals("done", events[events.lastIndex - 1].type)
    assertEquals("diagnostics", events.last().type)
    assertEquals("completed", events.last().diagnostics!!.finishReason)
}

@Test
fun validatorFallbackRetainsReason() = testApplication {
    val events = postChatAndParseEvents(rejectingProviderService())
    assertEquals("fallback", events[events.lastIndex - 1].type)
    assertEquals("validator_rejected", events.last().diagnostics!!.finishReason)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :server:test --tests "com.example.coachserver.PositionChatRouteTest"

Expected: FAIL because no diagnostics SSE event exists.

- [ ] **Step 3: Implement final diagnostics**

Capture retrieved IDs before composition; emit exactly one diagnostic chunk after the terminal chunk for template, valid provider, budget, provider-error, and validator paths. Retain raw output in that chunk/log. Extend only server test parsing; do not change app parser/UI.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :server:test --tests "com.example.coachserver.PositionChatRouteTest" --tests "com.example.coachserver.ChatStreamingChunkCountTest"

Expected: PASS with unchanged assembled text and one final diagnostics event per completed stream.

- [ ] **Step 5: Commit**

Run: git add server/src/main/kotlin/com/example/coachserver/PositionChatService.kt server/src/main/kotlin/com/example/coachserver/Application.kt server/src/test/kotlin/com/example/coachserver/PositionChatRouteTest.kt server/src/test/kotlin/com/example/coachserver/ChatStreamingChunkCountTest.kt && git commit -m "feat: emit terminal chat diagnostics"

### Task 5: Retain complete sample evidence

**Files:**

- Modify: tools/collect_cloud_samples.sh
- Create: tools/test_collect_cloud_samples.sh
- Modify: README.md
- Modify: docs/plans/cloud-eval-honesty-followups.md

**Interfaces:**

- tools/collect_cloud_samples.sh [base-url] [output-directory] creates a timestamped directory by default and refuses to overwrite.
- It writes raw opening JSON, raw chat SSE, and summary.json with request, visible sanitized answer, terminal event, and parsed diagnostics.

- [ ] **Step 1: Write failing fixture test**

~~~bash
tmpdir=$(mktemp -d)
start_fixture_server "$tmpdir"
tools/collect_cloud_samples.sh "$FIXTURE_URL" "$tmpdir/output"
test -f "$tmpdir/output/summary.json"
jq -e '.samples[0].opening.diagnostics.releaseVersion == "test-release"' "$tmpdir/output/summary.json"
jq -e '.samples[0].chat.terminalEvent == "done"' "$tmpdir/output/summary.json"
~~~

- [ ] **Step 2: Verify RED**

Run: bash tools/test_collect_cloud_samples.sh

Expected: FAIL because the collector only prints text.

- [ ] **Step 3: Implement artifact collection**

Save raw wire payloads before parsing. Use Python 3 to assemble summary.json; fail if diagnostics or terminal event is missing. Print concise human-readable diagnostic metadata while retaining raw output only in artifacts.

- [ ] **Step 4: Document and verify GREEN**

Document retained-artifact invocation and revise the follow-up checklist without marking deployment/R-1 complete. Run: bash tools/test_collect_cloud_samples.sh

Expected: PASS.

- [ ] **Step 5: Commit**

Run: git add tools/collect_cloud_samples.sh tools/test_collect_cloud_samples.sh README.md docs/plans/cloud-eval-honesty-followups.md && git commit -m "feat: retain cloud sample diagnostics"

### Task 6: Gate deterministic diagnostics in evals

**Files:**

- Modify: evals/src/main/kotlin/com/example/evals/EvalMain.kt
- Modify: evals/src/main/kotlin/com/example/evals/EvalScorer.kt
- Create: evals/src/test/kotlin/com/example/evals/CloudDiagnosticsEvalTest.kt
- Modify: evals/scorecard.md
- Modify: .github/workflows/ai-coach-evals.yml

**Interfaces:**

- DiagnosticScore(retrievalCorrect, terminalCorrect, corpusReady)
- EvalScorer.scoreDiagnostics(expectedEco: String?, diagnostics: CloudDiagnostics): DiagnosticScore
- Deterministic opening/chat route violations enter the current automated regression failure.

- [ ] **Step 1: Write failing eval tests**

~~~kotlin
@Test
fun matchingDiagnosticsPass() {
    assertTrue(EvalScorer.scoreDiagnostics("C20", fixtureDiagnostics()).retrievalCorrect)
}

@Test
fun wrongDiagnosticRetrievalFailsDespiteGroundedProse() {
    assertFalse(EvalScorer.scoreDiagnostics("C20", fixtureDiagnostics(retrievedPassageIds = listOf("eval-E06"))).retrievalCorrect)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :evals:test --tests "com.example.evals.CloudDiagnosticsEvalTest"

Expected: FAIL because evals do not score diagnostics.

- [ ] **Step 3: Implement HTTP diagnostic scoring**

Read opening diagnostics from the response and final chat diagnostics from SSE. Render a retrieval/terminal/corpus row in the scorecard and fold deterministic violations into the automated regression check. Provider-only fields remain optional.

- [ ] **Step 4: Verify GREEN and CI visibility**

Add a workflow assertion that the scorecard contains the diagnostics row. Run: ./gradlew :evals:test --tests "com.example.evals.CloudDiagnosticsEvalTest" && ./gradlew :evals:run

Expected: PASS with a regenerated scorecard and explicitly unavailable provider fields without credentials.

- [ ] **Step 5: Commit**

Run: git add evals/src/main/kotlin/com/example/evals/EvalMain.kt evals/src/main/kotlin/com/example/evals/EvalScorer.kt evals/src/test/kotlin/com/example/evals/CloudDiagnosticsEvalTest.kt evals/scorecard.md .github/workflows/ai-coach-evals.yml && git commit -m "test: gate cloud diagnostics in evals"

### Task 7: Full verification

**Files:**

- Modify only files required to correct an observed failure.

- [ ] **Step 1: Run the complete cloud suite**

Run: ./gradlew :server:test :evals:run

Expected: PASS with Docker. Without Docker, run all non-container targets and report that the retrieval gate was skipped rather than passed.

- [ ] **Step 2: Inspect workspace**

Run: git diff --check origin/main...HEAD && git status --short

Expected: no whitespace errors; preserve pre-existing XML reports, binary/, diff.txt, and pr_body.md.

- [ ] **Step 3: Audit deploy documentation**

Confirm README order: deploy image, inspect JSON health/release, seed from serving image, run :server:verifyCorpus, collect artifacts, review samples, then run deployed retrieval checks. Do not claim a live deployment check was run locally.

