package com.example.myapplication

import android.content.res.AssetManager
import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException

class StockfishEngine(
    private val nativeLibraryDir: String,
    private val filesDir: File,
    private val assetManager: AssetManager,
    private val supportedAbis: Array<String>
) : BaseStockfishEngine() {

    companion object {
        private const val ASSET_DIRECTORY = "stockfish"
        private val ENGINE_FILE_NAMES = listOf("stockfish", "libstockfish.so", "libpenguin.so")
        private val logger = Logger.withTag("StockfishEngine")
    }

    private var executableFile: File? = null

    /** Always true — embedded CPU fallback is always available. */
    fun isAvailable(): Boolean = true

    override fun resolveExecutablePath(): String? = resolveEngineFile()?.absolutePath

    private fun resolveEngineFile(): File? {
        executableFile?.let { if (it.exists() && it.canExecute()) return it }

        findEngineInNativeLibs()?.let {
            executableFile = it
            logger.i { "Using Stockfish executable from native library dir: ${it.absolutePath}" }
            return it
        }

        findEngineInAssets()?.let {
            executableFile = it
            logger.i { "Using Stockfish executable from assets: ${it.absolutePath}" }
            return it
        }

        logger.w {
            "No Stockfish executable found. Checked assets/$ASSET_DIRECTORY/<abi>/ and " +
                "$nativeLibraryDir for ${ENGINE_FILE_NAMES.joinToString()}"
        }
        return null
    }

    private fun findEngineInNativeLibs(): File? {
        for (fileName in ENGINE_FILE_NAMES) {
            val candidate = File(nativeLibraryDir, fileName)
            if (candidate.exists()) {
                if (candidate.canExecute()) return candidate
                logger.w { "Found Stockfish candidate but it is not executable: ${candidate.absolutePath}" }
            }
        }
        return null
    }

    private fun findEngineInAssets(): File? {
        val abiSearchOrder = buildList { addAll(supportedAbis.asList()); add("") }
        for (abi in abiSearchOrder) {
            for (fileName in ENGINE_FILE_NAMES) {
                val assetPath = if (abi.isBlank()) "$ASSET_DIRECTORY/$fileName"
                               else "$ASSET_DIRECTORY/$abi/$fileName"
                if (assetExists(assetPath)) return extractAssetExecutable(assetPath, abi, fileName)
            }
        }
        return null
    }

    private fun assetExists(path: String): Boolean =
        try { assetManager.open(path).use { true } } catch (_: IOException) { false }

    private fun extractAssetExecutable(assetPath: String, abi: String, fileName: String): File? {
        val outputDir = if (abi.isBlank()) File(filesDir, ASSET_DIRECTORY)
                        else File(filesDir, "$ASSET_DIRECTORY/$abi")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            logger.e { "Failed to create Stockfish extraction directory: ${outputDir.absolutePath}" }
            return null
        }

        val outputName = if (fileName.endsWith(".so")) "stockfish" else fileName
        val outputFile = File(outputDir, outputName)

        return try {
            assetManager.open(assetPath).use { input ->
                outputFile.outputStream().use { input.copyTo(it) }
            }
            if (!outputFile.setReadable(true, true))
                logger.w { "Unable to explicitly mark Stockfish readable: ${outputFile.absolutePath}" }
            if (!outputFile.setExecutable(true, true)) {
                logger.e { "Failed to mark Stockfish executable: ${outputFile.absolutePath}" }
                return null
            }
            outputFile
        } catch (e: IOException) {
            logger.e(e) { "Failed to extract Stockfish asset: $assetPath" }
            null
        }
    }
}
