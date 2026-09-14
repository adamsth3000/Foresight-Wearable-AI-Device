package com.foresight.gateway.voice.conversation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ConversationUnavailableException(val userMessage: String) : IllegalStateException(userMessage)

data class ConversationResourceSnapshot(
    val availableMemoryBytes: Long,
    val thermalStatus: Int,
)

class ConversationResourcePolicy(
    private val minimumAvailableMemoryBytes: Long = 1_500L * 1024L * 1024L,
    private val severeThermalStatus: Int = 4,
) {
    fun refusal(snapshot: ConversationResourceSnapshot): String? = when {
        snapshot.availableMemoryBytes < minimumAvailableMemoryBytes ->
            "Local AI is temporarily unavailable because the device is under heavy load."
        snapshot.thermalStatus >= severeThermalStatus ->
            "Local AI is temporarily unavailable because the device is under heavy load."
        else -> null
    }
}

/** CPU-only, serialized LiteRT-LM adapter. It owns no capture, preview, or sensor state. */
class LiteRtLmConversationEngine(
    context: Context,
    private val installer: GemmaModelInstaller = GemmaModelInstaller(context.applicationContext),
    private val contextRenderer: ForesightContextRenderer = ForesightContextRenderer(),
    private val requestFormatter: ConversationRequestFormatter = ConversationRequestFormatter(contextRenderer),
    private val resourcePolicy: ConversationResourcePolicy = ConversationResourcePolicy(),
    private val resourceSnapshot: () -> ConversationResourceSnapshot = systemResourceSnapshot(context.applicationContext),
) : ConversationEngine, ConversationMemoryAware {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ForesightLiteRtLm") }
    private val idleUnloader = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "ForesightLiteRtLmIdle") }
    private val lock = Any()
    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var activeWork: Future<*>? = null
    private var idleUnload: ScheduledFuture<*>? = null
    @Volatile private var state = installer.snapshot()
    @Volatile private var closed = false

    override fun readiness(): ConversationReadiness = state.toConversationReadiness()

    fun refreshReadiness() {
        if (engine == null) state = installer.snapshot()
    }

    override suspend fun respond(request: ConversationRequest): ConversationResponse = suspendCoroutine { continuation ->
        val refusal = resourcePolicy.refusal(resourceSnapshot())
        if (refusal != null) {
            continuation.resumeWith(Result.failure(ConversationUnavailableException(refusal)))
            return@suspendCoroutine
        }
        val installed = installer.snapshot()
        if (installed == GemmaModelInstallState.MODEL_NOT_INSTALLED || installed == GemmaModelInstallState.MODEL_DOWNLOADING || installed == GemmaModelInstallState.FAILED) {
            continuation.resumeWith(Result.failure(ConversationUnavailableException(installed.toConversationReadiness().userMessage())))
            return@suspendCoroutine
        }
        synchronized(lock) {
            if (closed) {
                continuation.resumeWith(Result.failure(ConversationUnavailableException("Local AI is unavailable right now.")))
                return@synchronized
            }
            idleUnload?.cancel(false)
            activeWork = worker.submit {
                runCatching {
                    val loadedEngine = loadEngine()
                    val prompt = requestFormatter.format(request)
                    Log.i(TAG, "FORESIGHT_AI_REQUEST_START")
                    Log.i(TAG, "FORESIGHT_AI_CONTEXT_READY")
                    Log.i(TAG, "FORESIGHT_AI_GENERATION_START")
                    val started = SystemClock.elapsedRealtime()
                    val reply = loadedEngine.createConversation(conversationConfig(request)).use { conversation ->
                        synchronized(lock) { activeConversation = conversation }
                        messageText(conversation.sendMessage(prompt))
                    }
                    synchronized(lock) { activeConversation = null }
                    Log.i(TAG, "FORESIGHT_AI_GENERATION_COMPLETE elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    ConversationResponse(
                        spokenText = sanitize(reply),
                        modelIdentity = "${GemmaModelSpec.modelIdentity};litertlm=${GemmaModelSpec.runtimeVersion}",
                    )
                }.onSuccess { response ->
                    scheduleIdleUnload()
                    continuation.resumeWith(Result.success(response))
                }.onFailure { error ->
                    synchronized(lock) { activeConversation = null }
                    state = GemmaModelInstallState.FAILED
                    Log.e(TAG, "FORESIGHT_AI_FAILURE type=GENERATION_${error.javaClass.simpleName}")
                    continuation.resumeWith(Result.failure(ConversationUnavailableException("Local AI could not answer that right now.")))
                }
            }
        }
    }

    override fun cancelActiveRequest() {
        synchronized(lock) {
            activeConversation?.cancelProcess()
            activeWork?.cancel(true)
            activeConversation = null
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= TRIM_MEMORY_RUNNING_LOW) unload()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancelActiveRequest()
        unload()
        worker.shutdownNow()
        idleUnloader.shutdownNow()
    }

    private fun loadEngine(): Engine {
        synchronized(lock) { engine?.let { return it } }
        state = GemmaModelInstallState.MODEL_LOADING
        Log.i(TAG, "FORESIGHT_AI_MODEL_LOAD_START")
        val modelPath = installer.validateForLoad()
            ?: throw ConversationUnavailableException("Local AI is unavailable right now.")
        resourcePolicy.refusal(resourceSnapshot())?.let { throw ConversationUnavailableException(it) }
        return Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = FileCacheDirectory.pathFor(modelPath),
            ),
        ).also { created ->
            created.initialize()
            synchronized(lock) { engine = created }
            state = GemmaModelInstallState.READY
            Log.i(TAG, "FORESIGHT_AI_MODEL_READY")
        }
    }

    private fun conversationConfig(request: ConversationRequest): ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(ConversationPolicy.systemInstruction),
        initialMessages = request.history.map { turn ->
            when (turn.role) {
                ConversationTurnRole.USER -> Message.user(turn.text)
                ConversationTurnRole.ASSISTANT -> Message.model(turn.text)
            }
        },
        samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
        maxOutputToken = MAX_OUTPUT_TOKENS,
    )

    private fun messageText(message: Message): String = message.contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

    private fun sanitize(value: String): String = value
        .replace(Regex("[`#*_]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_OUTPUT_CHARACTERS)
        .ifBlank { "Local AI could not answer that right now." }

    private fun scheduleIdleUnload() {
        synchronized(lock) {
            idleUnload?.cancel(false)
            idleUnload = idleUnloader.schedule(::unload, IDLE_UNLOAD_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun unload() {
        val released = synchronized(lock) {
            val current = engine ?: return
            engine = null
            activeConversation = null
            state = installer.snapshot()
            current
        }
        runCatching(released::close)
        Log.i(TAG, "FORESIGHT_AI_ENGINE_UNLOAD")
    }

    private object FileCacheDirectory {
        fun pathFor(modelPath: String): String = java.io.File(modelPath).parentFile!!.resolve("litert-cache").apply { mkdirs() }.absolutePath
    }

    private companion object {
        const val TAG = "LiteRtLmConversation"
        const val MAX_OUTPUT_TOKENS = 96
        const val MAX_OUTPUT_CHARACTERS = 700
        const val IDLE_UNLOAD_MILLIS = 5 * 60 * 1000L
        const val TRIM_MEMORY_RUNNING_LOW = 10

        fun systemResourceSnapshot(context: Context): () -> ConversationResourceSnapshot = {
            val memory = ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            } else {
                0
            }
            ConversationResourceSnapshot(memory.availMem, thermal)
        }
    }
}

private fun GemmaModelInstallState.toConversationReadiness(): ConversationReadiness = when (this) {
    GemmaModelInstallState.READY -> ConversationReadiness.READY
    GemmaModelInstallState.MODEL_LOADING -> ConversationReadiness.LOADING
    GemmaModelInstallState.MODEL_READY_NOT_LOADED -> ConversationReadiness.READY_NOT_LOADED
    GemmaModelInstallState.MODEL_NOT_INSTALLED -> ConversationReadiness.MODEL_NOT_INSTALLED
    GemmaModelInstallState.MODEL_DOWNLOADING -> ConversationReadiness.MODEL_DOWNLOADING
    GemmaModelInstallState.FAILED -> ConversationReadiness.FAILED
}
