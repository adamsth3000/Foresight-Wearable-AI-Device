package com.foresight.gateway.voice

import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Copies the bundled Vosk model exactly once into app-private storage. */
class VoskModelStore(
    private val assets: VoskModelAssetSource,
    private val modelsDirectory: File,
) {
    fun prepare(modelAssetRoot: String): VoskModelPrepareResult {
        val destination = File(modelsDirectory, modelAssetRoot)
        if (validate(destination) == null && File(destination, COMPLETION_MARKER).isFile) {
            return VoskModelPrepareResult.Reused(destination)
        }

        val staging = File(modelsDirectory, "$modelAssetRoot.extracting")
        deleteRecursively(staging)
        if (!staging.mkdirs() && !staging.isDirectory) {
            return VoskModelPrepareResult.Failed("create_staging", staging, null)
        }

        return try {
            copyTree(modelAssetRoot, staging, "")
            val missing = validate(staging)
            if (missing != null) {
                deleteRecursively(staging)
                VoskModelPrepareResult.Failed("validate_staging", staging, missing)
            } else {
                File(staging, COMPLETION_MARKER).writeText("complete\n")
                deleteRecursively(destination)
                if (!staging.renameTo(destination)) {
                    deleteRecursively(staging)
                    VoskModelPrepareResult.Failed("promote_staging", destination, null)
                } else {
                    VoskModelPrepareResult.Extracted(destination)
                }
            }
        } catch (error: IOException) {
            deleteRecursively(staging)
            VoskModelPrepareResult.Failed("copy_assets", staging, error.message)
        }
    }

    private fun copyTree(assetRoot: String, destinationRoot: File, relativePath: String) {
        val assetPath = listOf(assetRoot, relativePath).filter(String::isNotEmpty).joinToString("/")
        val children = assets.list(assetPath)
        if (children.isEmpty()) {
            if (relativePath.isEmpty()) throw FileNotFoundException("Asset root has no files: $assetRoot")
            val destination = File(destinationRoot, relativePath)
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
            return
        }
        children.forEach { child ->
            val childRelativePath = listOf(relativePath, child).filter(String::isNotEmpty).joinToString("/")
            copyTree(assetRoot, destinationRoot, childRelativePath)
        }
    }

    private fun validate(directory: File): String? = REQUIRED_MODEL_FILES.firstOrNull { relativePath ->
        !File(directory, relativePath).isFile
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    companion object {
        const val COMPLETION_MARKER = ".foresight-vosk-ready"

        val REQUIRED_MODEL_FILES = listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/Gr.fst",
            "graph/HCLr.fst",
            "graph/disambig_tid.int",
            "graph/phones/word_boundary.int",
            "ivector/final.dubm",
            "ivector/final.ie",
            "ivector/final.mat",
            "ivector/global_cmvn.stats",
            "ivector/online_cmvn.conf",
            "ivector/splice.conf",
        )
    }
}

interface VoskModelAssetSource {
    @Throws(IOException::class)
    fun list(path: String): Array<String>

    @Throws(IOException::class)
    fun open(path: String): java.io.InputStream
}

class AndroidVoskModelAssetSource(private val assets: AssetManager) : VoskModelAssetSource {
    override fun list(path: String): Array<String> = assets.list(path) ?: emptyArray()
    override fun open(path: String): java.io.InputStream = assets.open(path)
}

/**
 * Coordinates access to the single app-private copy of the bundled Vosk model.
 * Individual recognition sessions own their own native Model/Recognizer objects.
 */
object VoskModelDirectoryProvider {
    const val MODEL_ASSET_ROOT = "vosk-model-small-en-us-0.15"

    private val prepareLock = Any()

    fun prepare(context: android.content.Context): VoskModelPrepareResult = synchronized(prepareLock) {
        val destinationRoot = File(context.filesDir, "models")
        VoskModelStore(
            AndroidVoskModelAssetSource(context.assets),
            destinationRoot,
        ).prepare(MODEL_ASSET_ROOT)
    }
}

sealed interface VoskModelPrepareResult {
    val directory: File

    data class Reused(override val directory: File) : VoskModelPrepareResult
    data class Extracted(override val directory: File) : VoskModelPrepareResult
    data class Failed(
        val stage: String,
        override val directory: File,
        val missingRelativePath: String?,
    ) : VoskModelPrepareResult
}
