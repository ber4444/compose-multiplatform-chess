FROM ghcr.io/cirruslabs/android-sdk:36 AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-jammy

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

ENV COACH_EMBEDDING_MODEL=/opt/models/model.onnx
ENV COACH_EMBEDDING_VOCAB=/opt/models/vocab.txt
ENV PORT=8080

WORKDIR /opt/coach-server
COPY --from=build /workspace/server/build/install/server/ ./
# The corpus is NOT part of installDist output, so without this the runtime image ships no TSVs and
# the documented in-container seed (`java -cp "lib/*" SeedMain`) dies on NoSuchFileException for
# ./corpus. That is the only seed path that works on Fly — `.flycast` is unreachable from outside
# the private network — so omitting it made the documented procedure impossible to run.
COPY --from=build /workspace/server/corpus/ ./corpus/

EXPOSE 8080
ENTRYPOINT ["bin/server"]
