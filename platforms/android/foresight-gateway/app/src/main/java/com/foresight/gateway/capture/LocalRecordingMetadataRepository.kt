package com.foresight.gateway.capture

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal enum class LocalEventMappingState {
    STARTED,
    READY,
    INTERRUPTED,
    FAILED,
}

internal enum class EventMediaExtractionState {
    PENDING,
    EXTRACTING,
    READY,
    FAILED,
}

internal data class LocalRecordingMetadata(
    val recordingId: String,
    val sourceSessionId: String,
    val captureGeneration: Int,
    val recordingStartUtc: Instant,
    val recordingStartMonotonicMillis: Long,
    val localMediaFileName: String,
    val recordingStopUtc: Instant? = null,
    val finalized: Boolean = false,
    val interrupted: Boolean = false,
    val failureDetail: String? = null,
    val byteSize: Long? = null,
    val sha256: String? = null,
    val container: String = "mp4",
    val width: Int = 1280,
    val height: Int = 720,
    val videoCodec: String = "h264",
    val configuredVideoBitrate: Int = 2_000_000,
    val videoFps: Int = 30,
    val audioCodec: String = "aac",
    val audioSampleRate: Int = 44_100,
    val audioChannels: Int = 2,
)

internal data class LocalEventMapping(
    val eventId: String,
    val recordingId: String,
    val observedStartUtc: Instant,
    val observedStartMonotonicMillis: Long,
    val startOffsetMillis: Long,
    val observedEndUtc: Instant? = null,
    val observedEndMonotonicMillis: Long? = null,
    val endOffsetMillis: Long? = null,
    val durationMillis: Long? = null,
    val state: LocalEventMappingState = LocalEventMappingState.STARTED,
    val failureDetail: String? = null,
)

internal data class EventMediaMetadata(
    val eventId: String,
    val recordingId: String,
    val sourceRecordingSha256: String,
    val outputFileName: String,
    val extractionMethod: String,
    val requestedStartOffsetMillis: Long,
    val requestedEndOffsetMillis: Long,
    val actualStartOffsetMillis: Long? = null,
    val actualEndOffsetMillis: Long? = null,
    val boundaryAdjustmentStartMillis: Long? = null,
    val boundaryAdjustmentEndMillis: Long? = null,
    val outputDurationMillis: Long? = null,
    val outputByteSize: Long? = null,
    val outputSha256: String? = null,
    val videoPresent: Boolean? = null,
    val audioPresent: Boolean? = null,
    val extractionState: EventMediaExtractionState = EventMediaExtractionState.PENDING,
    val failureDetail: String? = null,
)

internal fun interface LocalRecordingRepositoryLogger {
    fun warn(message: String, error: Throwable?)
}

/**
 * App-private durable ledger for local media provenance. The ledger never stores an absolute
 * path, and callers can only address media by a validated filename below the private recordings
 * directory.
 */
internal class LocalRecordingMetadataRepository(
    private val metadataDirectory: File,
    private val recordingsDirectory: File,
    private val logger: LocalRecordingRepositoryLogger = LocalRecordingRepositoryLogger { _, _ -> },
) {
    private var ledger = loadLedger()

    init {
        val recovered = recoverInterruptedRecords(ledger)
        if (recovered != ledger) {
            ledger = recovered
            persist(ledger)
        }
    }

    @Synchronized
    fun createRecording(context: LocalRecordingContext): LocalRecordingMetadata {
        require(context.isRecording) { "a persisted recording must be active at creation" }
        validateFileName(context.localMediaFileName)
        val existing = ledger.recordings[context.recordingId]
        if (existing != null) {
            require(existing.localMediaFileName == context.localMediaFileName) {
                "recording ID already refers to another local media file"
            }
            return existing
        }
        val record = LocalRecordingMetadata(
            recordingId = context.recordingId,
            sourceSessionId = context.sourceSessionId,
            captureGeneration = context.captureGeneration,
            recordingStartUtc = context.startedUtc,
            recordingStartMonotonicMillis = context.startedMonotonicMillis,
            localMediaFileName = context.localMediaFileName,
        )
        ledger = ledger.copy(recordings = ledger.recordings + (record.recordingId to record))
        persist(ledger)
        return record
    }

    @Synchronized
    fun recordAuthoritativeStart(boundary: LocalEventBoundary): LocalEventMapping {
        require(boundary.recordingId in ledger.recordings) { "event start references an unknown recording" }
        val existing = ledger.events[boundary.eventId]
        if (existing != null) {
            require(existing.recordingId == boundary.recordingId && existing.state == LocalEventMappingState.STARTED) {
                "event ID already has a persisted mapping"
            }
            return existing
        }
        val event = LocalEventMapping(
            eventId = boundary.eventId,
            recordingId = boundary.recordingId,
            observedStartUtc = boundary.receiptUtc,
            observedStartMonotonicMillis = boundary.receiptMonotonicMillis,
            startOffsetMillis = boundary.recordingOffsetMillis,
        )
        ledger = ledger.copy(events = ledger.events + (event.eventId to event))
        persist(ledger)
        return event
    }

    @Synchronized
    fun recordAuthoritativeEnd(start: LocalEventBoundary, end: LocalEventBoundary): LocalEventMapping {
        require(start.eventId == end.eventId) { "event start and end IDs differ" }
        require(start.recordingId == end.recordingId) { "event boundaries refer to different recordings" }
        val current = requireNotNull(ledger.events[start.eventId]) { "no persisted event start exists" }
        require(current.recordingId == end.recordingId) { "event end references another recording" }
        require(current.state == LocalEventMappingState.STARTED) { "event is not awaiting an end boundary" }
        val ready = current.copy(
            observedEndUtc = end.receiptUtc,
            observedEndMonotonicMillis = end.receiptMonotonicMillis,
            endOffsetMillis = end.recordingOffsetMillis,
            durationMillis = (end.recordingOffsetMillis - current.startOffsetMillis).coerceAtLeast(0L),
            state = LocalEventMappingState.READY,
        )
        ledger = ledger.copy(events = ledger.events + (ready.eventId to ready))
        persist(ledger)
        return ready
    }

    @Synchronized
    fun finalizeRecording(context: LocalRecordingContext, stopUtc: Instant): LocalRecordingMetadata {
        val current = requireNotNull(ledger.recordings[context.recordingId]) { "unknown recording finalization" }
        val media = recordingFile(current.localMediaFileName)
        require(media.isFile) { "finalized local media file is missing" }
        val finalized = current.copy(
            recordingStopUtc = stopUtc,
            finalized = true,
            interrupted = false,
            failureDetail = null,
            byteSize = media.length(),
            sha256 = sha256(media),
        )
        ledger = ledger.copy(recordings = ledger.recordings + (finalized.recordingId to finalized))
        persist(ledger)
        return finalized
    }

    @Synchronized
    fun beginEventMediaExtraction(eventId: String): EventMediaExtractionDecision {
        val event = ledger.events[eventId]
        val recording = event?.let { ledger.recordings[it.recordingId] }
        val existing = ledger.eventMedia[eventId]
        val decision = EventMediaExtractionPolicy.decide(recording, event, existing)
        if (decision is EventMediaExtractionDecision.Rejected) {
            // A concurrent worker remains authoritative while it is EXTRACTING. All other
            // precondition failures are durable so the service can surface/retry them later.
            if (decision.reason != "event-media extraction is already active") {
                persistExtractionRejection(eventId, event, recording, existing, decision.reason)
            }
            return decision
        }
        if (decision is EventMediaExtractionDecision.ExistingReady) return decision
        val plan = (decision as EventMediaExtractionDecision.Extract).plan
        if (!recordingFile(plan.recording.localMediaFileName).isFile) {
            val reason = "finalized source recording file is missing"
            persistExtractionRejection(eventId, event, recording, existing, reason)
            return EventMediaExtractionDecision.Rejected(reason)
        }
        val extracting = EventMediaMetadata(
            eventId = plan.eventId,
            recordingId = plan.recording.recordingId,
            sourceRecordingSha256 = requireNotNull(plan.recording.sha256),
            outputFileName = plan.outputFileName,
            extractionMethod = ANDROID_REMUX_METHOD,
            requestedStartOffsetMillis = plan.requestedStartOffsetMillis,
            requestedEndOffsetMillis = plan.requestedEndOffsetMillis,
            extractionState = EventMediaExtractionState.EXTRACTING,
        )
        ledger = ledger.copy(eventMedia = ledger.eventMedia + (eventId to extracting))
        persist(ledger)
        return decision
    }

    private fun persistExtractionRejection(
        eventId: String,
        event: LocalEventMapping?,
        recording: LocalRecordingMetadata?,
        existing: EventMediaMetadata?,
        reason: String,
    ) {
        val failed = (existing ?: EventMediaMetadata(
            eventId = eventId,
            recordingId = event?.recordingId.orEmpty(),
            sourceRecordingSha256 = recording?.sha256.orEmpty(),
            outputFileName = if (eventId.matches(SAFE_EVENT_ID)) "event-$eventId.mp4" else "",
            extractionMethod = ANDROID_REMUX_METHOD,
            requestedStartOffsetMillis = event?.startOffsetMillis ?: 0L,
            requestedEndOffsetMillis = event?.endOffsetMillis ?: 0L,
        )).copy(
            extractionState = EventMediaExtractionState.FAILED,
            failureDetail = reason,
        )
        ledger = ledger.copy(eventMedia = ledger.eventMedia + (eventId to failed))
        persist(ledger)
    }

    @Synchronized
    fun completeEventMediaExtraction(
        plan: EventMediaExtractionPlan,
        actualStartOffsetMillis: Long,
        actualEndOffsetMillis: Long,
        outputByteSize: Long,
        outputSha256: String,
        videoPresent: Boolean,
        audioPresent: Boolean,
    ): EventMediaMetadata {
        val current = requireNotNull(ledger.eventMedia[plan.eventId]) { "event extraction was not started" }
        require(current.extractionState == EventMediaExtractionState.EXTRACTING) { "event extraction is not active" }
        val ready = current.copy(
            actualStartOffsetMillis = actualStartOffsetMillis,
            actualEndOffsetMillis = actualEndOffsetMillis,
            boundaryAdjustmentStartMillis = actualStartOffsetMillis - current.requestedStartOffsetMillis,
            boundaryAdjustmentEndMillis = actualEndOffsetMillis - current.requestedEndOffsetMillis,
            outputDurationMillis = (actualEndOffsetMillis - actualStartOffsetMillis).coerceAtLeast(0L),
            outputByteSize = outputByteSize,
            outputSha256 = outputSha256,
            videoPresent = videoPresent,
            audioPresent = audioPresent,
            extractionState = EventMediaExtractionState.READY,
            failureDetail = null,
        )
        ledger = ledger.copy(eventMedia = ledger.eventMedia + (ready.eventId to ready))
        persist(ledger)
        return ready
    }

    @Synchronized
    fun failEventMediaExtraction(eventId: String, detail: String): EventMediaMetadata? {
        val current = ledger.eventMedia[eventId] ?: return null
        if (current.extractionState == EventMediaExtractionState.READY) return current
        val failed = current.copy(extractionState = EventMediaExtractionState.FAILED, failureDetail = detail)
        ledger = ledger.copy(eventMedia = ledger.eventMedia + (eventId to failed))
        persist(ledger)
        return failed
    }

    @Synchronized
    fun markEventMediaConflict(eventId: String, detail: String): EventMediaMetadata? {
        val current = ledger.eventMedia[eventId] ?: return null
        val failed = current.copy(extractionState = EventMediaExtractionState.FAILED, failureDetail = detail)
        ledger = ledger.copy(eventMedia = ledger.eventMedia + (eventId to failed))
        persist(ledger)
        return failed
    }

    @Synchronized
    fun readyEventIdsForRecording(recordingId: String): List<String> = ledger.events.values
        .filter { event ->
            event.recordingId == recordingId && event.state == LocalEventMappingState.READY &&
                ledger.eventMedia[event.eventId]?.extractionState != EventMediaExtractionState.READY
        }
        .map { it.eventId }

    @Synchronized
    fun markRecordingInterrupted(recordingId: String, detail: String): LocalRecordingMetadata? {
        val current = ledger.recordings[recordingId] ?: return null
        if (current.finalized) return current
        val interrupted = current.copy(interrupted = true, failureDetail = detail)
        val interruptedEvents = ledger.events.mapValues { (_, event) ->
            if (event.recordingId == recordingId && event.state != LocalEventMappingState.FAILED) {
                event.copy(state = LocalEventMappingState.INTERRUPTED, failureDetail = detail)
            } else {
                event
            }
        }
        ledger = ledger.copy(recordings = ledger.recordings + (recordingId to interrupted), events = interruptedEvents)
        persist(ledger)
        return interrupted
    }

    @Synchronized
    fun snapshot(): LocalRecordingLedger = ledger

    private fun recoverInterruptedRecords(current: LocalRecordingLedger): LocalRecordingLedger {
        val unfinishedIds = current.recordings.values.filter { !it.finalized && !it.interrupted }.map { it.recordingId }.toSet()
        val recoveredRecordings = current.recordings.mapValues { (_, record) ->
            if (record.recordingId in unfinishedIds) {
                record.copy(interrupted = true, failureDetail = "recording ownership was lost before finalization")
            } else {
                record
            }
        }
        val recoveredEvents = current.events.mapValues { (_, event) ->
            if (event.recordingId in unfinishedIds && event.state != LocalEventMappingState.FAILED) {
                event.copy(
                    state = LocalEventMappingState.INTERRUPTED,
                    failureDetail = "recording ownership was lost before finalization",
                )
            } else {
                event
            }
        }
        val recoveredEventMedia = current.eventMedia.mapValues { (_, media) ->
            if (media.extractionState == EventMediaExtractionState.EXTRACTING) {
                media.copy(
                    extractionState = EventMediaExtractionState.FAILED,
                    failureDetail = "process ended during event-media extraction",
                )
            } else {
                media
            }
        }
        val recovered = LocalRecordingLedger(recoveredRecordings, recoveredEvents, recoveredEventMedia)
        return if (recovered == current) current else recovered
    }

    private fun loadLedger(): LocalRecordingLedger {
        val file = ledgerFile()
        if (!file.exists()) return LocalRecordingLedger()
        return try {
            decode(file.readText(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            preserveCorruptLedger(file)
            logger.warn("Local recording metadata is malformed; the original ledger was preserved.", error)
            LocalRecordingLedger()
        }
    }

    private fun persist(value: LocalRecordingLedger) {
        if (!metadataDirectory.exists() && !metadataDirectory.mkdirs()) {
            error("unable to create private recording metadata directory")
        }
        val target = ledgerFile()
        val temporary = File(metadataDirectory, "$LEDGER_FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encode(value).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun preserveCorruptLedger(file: File) {
        if (!metadataDirectory.exists()) return
        val backup = File(metadataDirectory, "$LEDGER_FILE_NAME.corrupt-${System.currentTimeMillis()}")
        runCatching { Files.copy(file.toPath(), backup.toPath()) }
            .onFailure { logger.warn("Unable to preserve malformed local recording metadata.", it) }
    }

    private fun recordingFile(fileName: String): File {
        validateFileName(fileName)
        return File(recordingsDirectory, fileName)
    }

    private fun validateFileName(fileName: String) {
        require(fileName == File(fileName).name && fileName.endsWith(".mp4")) {
            "local media reference must be a private MP4 filename"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun ledgerFile(): File = File(metadataDirectory, LEDGER_FILE_NAME)

    private fun encode(value: LocalRecordingLedger): String = JSONObject().apply {
        put("schema_version", 1)
        put("recordings", JSONArray().apply { value.recordings.values.sortedBy { it.recordingId }.forEach { put(it.toJson()) } })
        put("events", JSONArray().apply { value.events.values.sortedBy { it.eventId }.forEach { put(it.toJson()) } })
        put("event_media", JSONArray().apply { value.eventMedia.values.sortedBy { it.eventId }.forEach { put(it.toJson()) } })
    }.toString()

    private fun decode(serialized: String): LocalRecordingLedger {
        val root = JSONObject(serialized)
        require(root.getInt("schema_version") == 1) { "unsupported local recording metadata schema" }
        val recordings = root.getJSONArray("recordings").toList { it.toRecording() }.associateBy { it.recordingId }
        val events = root.getJSONArray("events").toList { it.toEvent() }.associateBy { it.eventId }
        val eventMediaArray = root.optJSONArray("event_media") ?: JSONArray()
        val eventMedia = eventMediaArray.toList { it.toEventMedia() }.associateBy { it.eventId }
        require(recordings.size == root.getJSONArray("recordings").length()) { "duplicate recording IDs in metadata" }
        require(events.size == root.getJSONArray("events").length()) { "duplicate event IDs in metadata" }
        require(eventMedia.size == eventMediaArray.length()) { "duplicate event-media IDs in metadata" }
        return LocalRecordingLedger(recordings, events, eventMedia)
    }

    private fun LocalRecordingMetadata.toJson(): JSONObject = JSONObject().apply {
        put("recording_id", recordingId)
        put("source_session_id", sourceSessionId)
        put("capture_generation", captureGeneration)
        put("recording_start_utc", recordingStartUtc.toString())
        put("recording_start_monotonic_ms", recordingStartMonotonicMillis)
        put("local_media_file_name", localMediaFileName)
        put("recording_stop_utc", recordingStopUtc?.toString())
        put("finalized", finalized)
        put("interrupted", interrupted)
        put("failure_detail", failureDetail)
        put("byte_size", byteSize)
        put("sha256", sha256)
        put("container", container)
        put("width", width)
        put("height", height)
        put("video_codec", videoCodec)
        put("configured_video_bitrate", configuredVideoBitrate)
        put("video_fps", videoFps)
        put("audio_codec", audioCodec)
        put("audio_sample_rate", audioSampleRate)
        put("audio_channels", audioChannels)
    }

    private fun LocalEventMapping.toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("recording_id", recordingId)
        put("observed_start_utc", observedStartUtc.toString())
        put("observed_start_monotonic_ms", observedStartMonotonicMillis)
        put("start_offset_ms", startOffsetMillis)
        put("observed_end_utc", observedEndUtc?.toString())
        put("observed_end_monotonic_ms", observedEndMonotonicMillis)
        put("end_offset_ms", endOffsetMillis)
        put("duration_ms", durationMillis)
        put("state", state.name)
        put("failure_detail", failureDetail)
    }

    private fun EventMediaMetadata.toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("recording_id", recordingId)
        put("source_recording_sha256", sourceRecordingSha256)
        put("output_file_name", outputFileName)
        put("extraction_method", extractionMethod)
        put("requested_start_offset_ms", requestedStartOffsetMillis)
        put("requested_end_offset_ms", requestedEndOffsetMillis)
        put("actual_start_offset_ms", actualStartOffsetMillis)
        put("actual_end_offset_ms", actualEndOffsetMillis)
        put("boundary_adjustment_start_ms", boundaryAdjustmentStartMillis)
        put("boundary_adjustment_end_ms", boundaryAdjustmentEndMillis)
        put("output_duration_ms", outputDurationMillis)
        put("output_byte_size", outputByteSize)
        put("output_sha256", outputSha256)
        put("video_present", videoPresent)
        put("audio_present", audioPresent)
        put("extraction_state", extractionState.name)
        put("failure_detail", failureDetail)
    }

    private fun JSONObject.toRecording(): LocalRecordingMetadata = LocalRecordingMetadata(
        recordingId = getString("recording_id"),
        sourceSessionId = getString("source_session_id"),
        captureGeneration = getInt("capture_generation"),
        recordingStartUtc = Instant.parse(getString("recording_start_utc")),
        recordingStartMonotonicMillis = getLong("recording_start_monotonic_ms"),
        localMediaFileName = getString("local_media_file_name"),
        recordingStopUtc = optNullableString("recording_stop_utc")?.let(Instant::parse),
        finalized = getBoolean("finalized"),
        interrupted = getBoolean("interrupted"),
        failureDetail = optNullableString("failure_detail"),
        byteSize = optNullableLong("byte_size"),
        sha256 = optNullableString("sha256"),
        container = getString("container"),
        width = getInt("width"),
        height = getInt("height"),
        videoCodec = getString("video_codec"),
        configuredVideoBitrate = getInt("configured_video_bitrate"),
        videoFps = getInt("video_fps"),
        audioCodec = getString("audio_codec"),
        audioSampleRate = getInt("audio_sample_rate"),
        audioChannels = getInt("audio_channels"),
    )

    private fun JSONObject.toEvent(): LocalEventMapping = LocalEventMapping(
        eventId = getString("event_id"),
        recordingId = getString("recording_id"),
        observedStartUtc = Instant.parse(getString("observed_start_utc")),
        observedStartMonotonicMillis = getLong("observed_start_monotonic_ms"),
        startOffsetMillis = getLong("start_offset_ms"),
        observedEndUtc = optNullableString("observed_end_utc")?.let(Instant::parse),
        observedEndMonotonicMillis = optNullableLong("observed_end_monotonic_ms"),
        endOffsetMillis = optNullableLong("end_offset_ms"),
        durationMillis = optNullableLong("duration_ms"),
        state = LocalEventMappingState.valueOf(getString("state")),
        failureDetail = optNullableString("failure_detail"),
    )

    private fun JSONObject.toEventMedia(): EventMediaMetadata = EventMediaMetadata(
        eventId = getString("event_id"),
        recordingId = getString("recording_id"),
        sourceRecordingSha256 = getString("source_recording_sha256"),
        outputFileName = getString("output_file_name"),
        extractionMethod = getString("extraction_method"),
        requestedStartOffsetMillis = getLong("requested_start_offset_ms"),
        requestedEndOffsetMillis = getLong("requested_end_offset_ms"),
        actualStartOffsetMillis = optNullableLong("actual_start_offset_ms"),
        actualEndOffsetMillis = optNullableLong("actual_end_offset_ms"),
        boundaryAdjustmentStartMillis = optNullableLong("boundary_adjustment_start_ms"),
        boundaryAdjustmentEndMillis = optNullableLong("boundary_adjustment_end_ms"),
        outputDurationMillis = optNullableLong("output_duration_ms"),
        outputByteSize = optNullableLong("output_byte_size"),
        outputSha256 = optNullableString("output_sha256"),
        videoPresent = optNullableBoolean("video_present"),
        audioPresent = optNullableBoolean("audio_present"),
        extractionState = EventMediaExtractionState.valueOf(getString("extraction_state")),
        failureDetail = optNullableString("failure_detail"),
    )

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.optNullableBoolean(name: String): Boolean? =
        if (isNull(name)) null else getBoolean(name)

    private fun <T> JSONArray.toList(mapper: (JSONObject) -> T): List<T> =
        List(length()) { index -> mapper(getJSONObject(index)) }

    private companion object {
        const val LEDGER_FILE_NAME = "local-recording-ledger.json"
        const val HASH_BUFFER_BYTES = 64 * 1024
        const val ANDROID_REMUX_METHOD = "android_mediaextractor_mediummuxer_remux"
        val SAFE_EVENT_ID = Regex("[A-Za-z0-9_-]+")
    }
}

internal data class LocalRecordingLedger(
    val recordings: Map<String, LocalRecordingMetadata> = emptyMap(),
    val events: Map<String, LocalEventMapping> = emptyMap(),
    val eventMedia: Map<String, EventMediaMetadata> = emptyMap(),
)
