# The MiniLM weights are pulled once and shared by both later stages: the build stage embeds the
# corpus with them, the runtime stage embeds incoming queries with them.
FROM eclipse-temurin:21-jre-jammy AS models

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

ARG MINILM_REVISION=5641a7880f40ebf4035d05e60c5f9b7a9c272c84
RUN mkdir -p /opt/models \
    && curl --fail --location --retry 3 \
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/${MINILM_REVISION}/onnx/model.onnx?download=true" \
        --output /opt/models/model.onnx \
    && curl --fail --location --retry 3 \
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/${MINILM_REVISION}/vocab.txt?download=true" \
        --output /opt/models/vocab.txt

FROM ghcr.io/cirruslabs/android-sdk:36 AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :server:installDist

# Embed the corpus now, so the deployed server never has to. Doing this at boot instead would add
# ~3,800 MiniLM forward passes to every cold start, which is what `min_machines_running = 0` in
# fly.toml cannot afford. The result is a ~6 MB file that replaces an always-on Postgres machine.
COPY --from=models /opt/models /opt/models
RUN COACH_EMBEDDING_MODEL=/opt/models/model.onnx \
    COACH_EMBEDDING_VOCAB=/opt/models/vocab.txt \
    COACH_CORPUS_DIR=/workspace/server/corpus \
    COACH_CORPUS_INDEX=/workspace/corpus-index.bin \
    java -cp "server/build/install/server/lib/*" com.example.coachserver.BuildCorpusIndexMain

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=models /opt/models /opt/models

ENV COACH_EMBEDDING_MODEL=/opt/models/model.onnx
ENV COACH_EMBEDDING_VOCAB=/opt/models/vocab.txt
ENV PORT=8080

WORKDIR /opt/coach-server
COPY --from=build /workspace/server/build/install/server/ ./
# The retrieval corpus, embeddings included. Without it the server has nothing to retrieve from and
# refuses to boot (CorpusIndexFile.read), which is the intended failure — a silently empty index
# would answer every request with fluent, cited, wrong prose.
COPY --from=build /workspace/corpus-index.bin ./corpus-index.bin
# The TSVs stay for the Postgres seed path (`java -cp "lib/*" SeedMain`), which is no longer part of
# deployment but is still how the reference implementation and its Testcontainers tests are fed.
COPY --from=build /workspace/server/corpus/ ./corpus/

EXPOSE 8080
ENTRYPOINT ["bin/server"]
