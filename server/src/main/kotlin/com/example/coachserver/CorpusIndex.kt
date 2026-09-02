package com.example.coachserver

import com.example.coachapi.Passage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Book hits are capped so the vector tiers still contribute related material.
 *
 * Shared by [PostgresPassageRepository] and [InMemoryPassageRepository] so the two cannot drift on
 * how many book rows a retrieval is allowed to be made of.
 */
internal const val BOOK_LIMIT = 2

/** One corpus row with its embedding — the unit both the baked index and the seeder deal in. */
data class IndexedPassage(
    val passage: Passage,
    val embedding: FloatArray,
    val eco: String?,
    val moves: String?,
) {
    // FloatArray gives the generated data-class equals identity semantics, which silently breaks
    // any assertEquals over a decoded index. Compare the vector by content instead.
    override fun equals(other: Any?): Boolean = this === other || (
        other is IndexedPassage &&
            passage == other.passage &&
            embedding.contentEquals(other.embedding) &&
            eco == other.eco &&
            moves == other.moves
        )

    override fun hashCode(): Int {
        var result = passage.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (eco?.hashCode() ?: 0)
        result = 31 * result + (moves?.hashCode() ?: 0)
        return result
    }
}

/** The whole retrieval corpus, embeddings included, as one immutable value. */
data class CorpusIndex(
    val manifest: CorpusSeedManifest,
    val rows: List<IndexedPassage>,
)

/**
 * The on-disk form of [CorpusIndex], written at Docker build time and read at boot.
 *
 * This file is what replaced the Postgres machine. The corpus is 3,825 rows derived deterministically
 * from the TSVs in `server/corpus` — checked into git, never written at request time (every
 * `INSERT` in `Database.kt` is on the seed path) — so a dedicated always-on database and a 10 GB volume were
 * being rented to serve ~6 MB of read-only data. Baking the rows and their vectors into the image
 * removes that machine, its volume and its snapshots, and makes the app stateless enough to sleep.
 *
 * **Embeddings are computed at build time, not at boot, and that ordering is the point.** Running
 * 3,825 MiniLM forward passes during startup would add 30–60 s to every cold start on a
 * shared-cpu-1x, which is precisely what `min_machines_running = 0` cannot afford.
 *
 * Strings are length-prefixed UTF-8 rather than `writeUTF`, whose 64 KB-per-string ceiling is a
 * trap waiting for the first long corpus passage.
 */
object CorpusIndexFile {

    private const val MAGIC = "CHESSIDX"
    private const val FORMAT_VERSION = 1

    fun write(path: Path, index: CorpusIndex) {
        path.parent?.let(Files::createDirectories)
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { out ->
            out.writeBytes(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeString(index.manifest.version)
            out.writeInt(index.manifest.expectedRowCount)
            out.writeString(index.manifest.finalSourceId)
            out.writeInt(OpeningService.EMBEDDING_DIMENSIONS)
            out.writeInt(index.rows.size)
            index.rows.forEach { row ->
                out.writeString(row.passage.sourceId)
                out.writeString(row.passage.title)
                out.writeString(row.passage.text)
                out.writeString(row.eco.orEmpty())
                out.writeString(row.moves.orEmpty())
                require(row.embedding.size == OpeningService.EMBEDDING_DIMENSIONS) {
                    "${row.passage.sourceId} has ${row.embedding.size} dimensions"
                }
                row.embedding.forEach(out::writeFloat)
            }
        }
    }

    fun read(path: Path): CorpusIndex {
        require(Files.exists(path)) {
            "Corpus index $path is missing. It is generated at image build time by " +
                "BuildCorpusIndexMain (see server/Dockerfile); build it locally with " +
                "`./gradlew :server:buildCorpusIndex`."
        }
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            val magic = ByteArray(MAGIC.length).also(input::readFully).decodeToString()
            check(magic == MAGIC) { "$path is not a corpus index (bad magic '$magic')" }
            val formatVersion = input.readInt()
            check(formatVersion == FORMAT_VERSION) {
                "Corpus index $path is format v$formatVersion; this build reads v$FORMAT_VERSION. " +
                    "Rebuild the image so the index is regenerated."
            }
            val manifest = CorpusSeedManifest(
                version = input.readString(),
                expectedRowCount = input.readInt(),
                finalSourceId = input.readString(),
            )
            val dimensions = input.readInt()
            check(dimensions == OpeningService.EMBEDDING_DIMENSIONS) {
                "Corpus index $path has $dimensions dimensions; expected ${OpeningService.EMBEDDING_DIMENSIONS}"
            }
            val rowCount = input.readInt()
            val vectorBytes = ByteArray(dimensions * Float.SIZE_BYTES)
            val rows = ArrayList<IndexedPassage>(rowCount)
            repeat(rowCount) {
                val sourceId = input.readString()
                val title = input.readString()
                val text = input.readString()
                val eco = input.readString().takeIf(String::isNotEmpty)
                val moves = input.readString().takeIf(String::isNotEmpty)
                input.readFully(vectorBytes)
                val embedding = FloatArray(dimensions)
                // writeFloat is big-endian, which is ByteBuffer's default.
                ByteBuffer.wrap(vectorBytes).asFloatBuffer().get(embedding)
                rows += IndexedPassage(Passage(sourceId, title, text), embedding, eco, moves)
            }
            check(rows.size == manifest.expectedRowCount) {
                "Corpus index $path holds ${rows.size} rows but its manifest expects ${manifest.expectedRowCount}"
            }
            return CorpusIndex(manifest, rows)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val bytes = ByteArray(readInt())
        readFully(bytes)
        return bytes.decodeToString()
    }
}
