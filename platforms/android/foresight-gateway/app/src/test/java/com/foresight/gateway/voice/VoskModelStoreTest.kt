package com.foresight.gateway.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class VoskModelStoreTest {
    @Test
    fun `recursively copies the asset tree then reuses a validated cache`() = withTemporaryDirectory { directory ->
        val source = FakeAssets(requiredModelAssets())
        val store = VoskModelStore(source, directory)

        val extracted = store.prepare(MODEL_ROOT)
        assertTrue(extracted is VoskModelPrepareResult.Extracted)
        val modelDirectory = File(directory, MODEL_ROOT)
        assertTrue(File(modelDirectory, "graph/phones/word_boundary.int").isFile)
        assertTrue(File(modelDirectory, VoskModelStore.COMPLETION_MARKER).isFile)

        val reused = store.prepare(MODEL_ROOT)
        assertTrue(reused is VoskModelPrepareResult.Reused)
        assertEquals(1, source.openCountFor("$MODEL_ROOT/am/final.mdl"))
    }

    @Test
    fun `incomplete destination is replaced from assets`() = withTemporaryDirectory { directory ->
        val source = FakeAssets(requiredModelAssets())
        val modelDirectory = File(directory, MODEL_ROOT).apply { mkdirs() }
        File(modelDirectory, VoskModelStore.COMPLETION_MARKER).writeText("complete\n")
        File(modelDirectory, "am").mkdirs()
        File(modelDirectory, "am/final.mdl").writeText("partial")

        val result = VoskModelStore(source, directory).prepare(MODEL_ROOT)

        assertTrue(result is VoskModelPrepareResult.Extracted)
        assertEquals("am/final.mdl", File(modelDirectory, "am/final.mdl").readText())
        assertTrue(File(modelDirectory, "graph/Gr.fst").isFile)
    }

    @Test
    fun `missing critical asset reports its precise relative path`() = withTemporaryDirectory { directory ->
        val assets = requiredModelAssets().toMutableMap().apply { remove("$MODEL_ROOT/graph/Gr.fst") }

        val result = VoskModelStore(FakeAssets(assets), directory).prepare(MODEL_ROOT)

        assertTrue(result is VoskModelPrepareResult.Failed)
        assertEquals("validate_staging", (result as VoskModelPrepareResult.Failed).stage)
        assertEquals("graph/Gr.fst", result.missingRelativePath)
        assertFalse(File(directory, MODEL_ROOT).exists())
    }

    private fun requiredModelAssets(): Map<String, ByteArray> = buildMap {
        VoskModelStore.REQUIRED_MODEL_FILES.forEach { relativePath ->
            put("$MODEL_ROOT/$relativePath", relativePath.toByteArray())
        }
        put("$MODEL_ROOT/README", "model readme".toByteArray())
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("vosk-model-store-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeAssets(private val files: Map<String, ByteArray>) : VoskModelAssetSource {
        private val opens = mutableMapOf<String, Int>()

        override fun list(path: String): Array<String> {
            val prefix = "$path/"
            return files.keys.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .sorted()
                .toList()
                .toTypedArray()
        }

        override fun open(path: String): java.io.InputStream {
            val bytes = files[path] ?: throw FileNotFoundException(path)
            opens[path] = (opens[path] ?: 0) + 1
            return ByteArrayInputStream(bytes)
        }

        fun openCountFor(path: String): Int = opens[path] ?: 0
    }

    private companion object {
        const val MODEL_ROOT = "vosk-model-small-en-us-0.15"
    }
}
