package com.foresight.gateway.voice.conversation

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object GemmaModelSpec {
    const val runtimeVersion = "0.16.1"
    const val repository = "google/gemma-3n-E2B-it-litert-lm"
    const val revision = "c03b6f60b8da6c5400b6838a2cf26420f80c0a01"
    const val artifactFileName = "gemma-3n-E2B-it-int4.litertlm"
    const val expectedBytes = 3_655_827_456L
    const val modelIdentity = "google/gemma-3n-E2B-it-litert-lm@$revision/$artifactFileName"
    const val termsUrl = "https://ai.google.dev/gemma/terms"
    fun downloadUrl() = "https://huggingface.co/$repository/resolve/$revision/$artifactFileName"
}

enum class GemmaModelInstallState { MODEL_NOT_INSTALLED, MODEL_DOWNLOADING, MODEL_READY_NOT_LOADED, MODEL_LOADING, READY, FAILED }
sealed interface GemmaInstallState {
    data object NotInstalled : GemmaInstallState
    data object ValidatingAccess : GemmaInstallState
    data object ReadyToDownload : GemmaInstallState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : GemmaInstallState
    data object Verifying : GemmaInstallState
    data object Installing : GemmaInstallState
    data class Installed(val modelPath: String, val version: String, val sha256: String) : GemmaInstallState
    data class Failed(val userMessage: String, val retryable: Boolean) : GemmaInstallState
}

class GemmaTermsAcceptanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("foresight_gemma_terms", Context.MODE_PRIVATE)
    fun isAccepted() = preferences.getString("accepted_terms_url", null) == GemmaModelSpec.termsUrl
    fun accept() = preferences.edit().putString("accepted_terms_url", GemmaModelSpec.termsUrl).apply()
}

/** Observable, one-process installer. Tokens are transient and are never persisted or logged. */
class GemmaModelInstaller(private val context: Context, private val terms: GemmaTermsAcceptanceStore = GemmaTermsAcceptanceStore(context)) {
    private val directory = File(context.filesDir, "models/gemma-3n-e2b")
    private val model = File(directory, GemmaModelSpec.artifactFileName)
    private val stage = File(directory, "${GemmaModelSpec.artifactFileName}.part")
    private val manifest = File(directory, "model.properties")
    private val marker = File(directory, ".foresight-gemma-ready")
    private val shared = installs.computeIfAbsent(directory.absolutePath) { SharedState() }

    fun installState(): GemmaInstallState {
        if (!shared.active && completeInstall()) {
            val props = properties()!!
            shared.state = GemmaInstallState.Installed(model.absolutePath, GemmaModelSpec.revision, props.getProperty("sha256"))
        }
        return shared.state
    }
    fun snapshot(): GemmaModelInstallState = when (installState()) {
        GemmaInstallState.NotInstalled -> GemmaModelInstallState.MODEL_NOT_INSTALLED
        is GemmaInstallState.Installed -> GemmaModelInstallState.MODEL_READY_NOT_LOADED
        is GemmaInstallState.Failed -> GemmaModelInstallState.FAILED
        else -> GemmaModelInstallState.MODEL_DOWNLOADING
    }
    fun acceptTerms() = terms.accept()
    fun validateForLoad(): String? = model.absolutePath.takeIf { completeInstall() && sha256(model) == properties()!!.getProperty("sha256") }

    fun install(accessToken: String, executor: Executor = installExecutor, onComplete: (Result<Unit>) -> Unit) {
        synchronized(shared) {
            if (shared.active) return onComplete(Result.failure(IllegalStateException("A Local AI installation is already in progress.")))
            if (!terms.isAccepted()) return onComplete(Result.failure(InstallFailure("ACCESS_CHECK", "Gemma terms must be accepted before installation.", false)))
            if (accessToken.isBlank()) return onComplete(Result.failure(InstallFailure("ACCESS_CHECK", "Hugging Face token was not accepted.", false)))
            if (completeInstall()) return onComplete(Result.success(Unit))
            shared.active = true; shared.state = GemmaInstallState.ValidatingAccess
        }
        Log.i(TAG, "AI_INSTALL_REQUEST")
        executor.execute {
            val result = runCatching { accessCheck(accessToken); shared.state = GemmaInstallState.ReadyToDownload; download(accessToken) }
            synchronized(shared) { shared.active = false }
            result.onSuccess { installState(); Log.i(TAG, "AI_INSTALL_COMPLETE") }
                .onFailure { error ->
                    val failure = error as? InstallFailure
                    shared.state = GemmaInstallState.Failed(failure?.message ?: "Local AI setup failed.", failure?.retryable ?: true)
                    Log.e(TAG, "AI_INSTALL_FAILURE stage=${failure?.stage ?: "UNKNOWN"} type=${error.javaClass.simpleName} httpStatus=${failure?.status ?: "none"}")
                }
            onComplete(result)
        }
    }

    private fun accessCheck(token: String) {
        Log.i(TAG, "AI_ACCESS_CHECK_START")
        val connection = connection(token, 0L).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val status = connection.responseCode
            Log.i(TAG, "AI_ACCESS_CHECK_HTTP status=$status")
            if (status !in setOf(200, 206)) throw httpFailure("ACCESS_CHECK", status)
            connection.inputStream.close(); Log.i(TAG, "AI_ACCESS_AUTHORIZED")
        } catch (error: UnknownHostException) { throw InstallFailure("ACCESS_CHECK", "Could not reach Hugging Face.", true, cause = error) }
        catch (error: InstallFailure) { throw error }
        catch (error: java.io.IOException) { throw InstallFailure("ACCESS_CHECK", "Could not reach Hugging Face.", true, cause = error) }
        finally { connection.disconnect() }
    }

    private fun download(token: String) {
        directory.mkdirs()
        var offset = stage.takeIf(File::exists)?.length() ?: 0L
        if (directory.usableSpace < GemmaModelSpec.expectedBytes - offset) throw InstallFailure("STORAGE", "Not enough storage to install Local AI.", false)
        marker.delete(); shared.state = GemmaInstallState.Downloading(offset, GemmaModelSpec.expectedBytes)
        Log.i(TAG, "AI_DOWNLOAD_START expectedBytes=${GemmaModelSpec.expectedBytes}")
        if (offset > 0) Log.i(TAG, "AI_DOWNLOAD_RESUME offset=$offset")
        val connection = connection(token, offset)
        try {
            val status = connection.responseCode
            if (status == 416 && offset > 0L) { stage.delete(); return download(token) }
            if (status !in setOf(200, 206)) throw httpFailure("DOWNLOAD", status)
            val append = offset > 0 && status == 206
            if (!append) { stage.delete(); offset = 0L }
            connection.inputStream.use { input -> FileOutputStream(stage, append).use { output ->
                val buffer = ByteArray(BUFFER); var current = offset; var lastReported = current
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count); current += count
                    if (current - lastReported >= PROGRESS_BYTES || current == GemmaModelSpec.expectedBytes) {
                        shared.state = GemmaInstallState.Downloading(current, GemmaModelSpec.expectedBytes)
                        Log.i(TAG, "AI_DOWNLOAD_PROGRESS bytes=$current percent=${current * 100 / GemmaModelSpec.expectedBytes}")
                        lastReported = current
                    }
                }
            } }
        } finally { connection.disconnect() }
        if (stage.length() != GemmaModelSpec.expectedBytes) throw InstallFailure("DOWNLOAD", "Model download was incomplete.", true)
        shared.state = GemmaInstallState.Verifying; Log.i(TAG, "AI_VERIFY_START")
        val checksum = sha256(stage); Log.i(TAG, "AI_VERIFY_COMPLETE sha256=$checksum")
        shared.state = GemmaInstallState.Installing; writeManifest(checksum)
        model.delete()
        if (!stage.renameTo(model)) throw InstallFailure("INSTALL", "Could not finalize the Local AI install.", true)
        marker.writeText("ready\n")
    }

    private fun connection(token: String, offset: Long) = (URL(GemmaModelSpec.downloadUrl()).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000; readTimeout = 60_000; requestMethod = "GET"; setRequestProperty("Authorization", "Bearer $token")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
    }
    private fun httpFailure(stage: String, status: Int) = when (status) {
        401 -> InstallFailure(stage, "Hugging Face token was not accepted.", false, status)
        403 -> InstallFailure(stage, "Your Hugging Face account does not have access to this Gemma model. Accept the model access conditions on Hugging Face and try again.", false, status)
        404 -> InstallFailure(stage, "The configured Gemma model artifact was not found.", false, status)
        429 -> InstallFailure(stage, "Hugging Face is rate limiting the download. Try again later.", true, status)
        in 500..599 -> InstallFailure(stage, "Hugging Face is temporarily unavailable.", true, status)
        else -> InstallFailure(stage, "Local AI setup failed while contacting Hugging Face.", true, status)
    }
    private fun completeInstall() = marker.isFile && model.isFile && model.length() == GemmaModelSpec.expectedBytes && properties()?.let { it.getProperty("revision") == GemmaModelSpec.revision && it.getProperty("sha256") != null } == true
    private fun properties(): Properties? = manifest.takeIf(File::isFile)?.let { file -> Properties().also { p -> FileInputStream(file).use(p::load) } }
    private fun writeManifest(hash: String) = Properties().apply {
        setProperty("repository", GemmaModelSpec.repository); setProperty("revision", GemmaModelSpec.revision); setProperty("artifact", GemmaModelSpec.artifactFileName)
        setProperty("bytes", GemmaModelSpec.expectedBytes.toString()); setProperty("sha256", hash); setProperty("runtime", GemmaModelSpec.runtimeVersion)
    }.also { properties -> FileOutputStream(manifest).use { output -> properties.store(output, "Foresight Gemma model manifest") } }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input -> val buffer = ByteArray(BUFFER); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        const val TAG = "ForesightAIInstall"; const val BUFFER = 128 * 1024; const val PROGRESS_BYTES = 32L * 1024 * 1024
        val installs = ConcurrentHashMap<String, SharedState>(); val installExecutor = Executors.newSingleThreadExecutor { Thread(it, "ForesightGemmaInstall") }
    }
}
private class SharedState { @Volatile var active = false; @Volatile var state: GemmaInstallState = GemmaInstallState.NotInstalled }
private class InstallFailure(val stage: String, message: String, val retryable: Boolean, val status: Int? = null, cause: Throwable? = null) : IllegalStateException(message, cause)
