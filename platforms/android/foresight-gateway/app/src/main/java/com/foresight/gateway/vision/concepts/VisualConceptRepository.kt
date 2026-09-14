package com.foresight.gateway.vision.concepts

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import org.json.JSONArray

/** App-private SQLite store for textual concept records and normalized embedding blobs only. */
class VisualConceptRepository(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), AutoCloseable {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL("""CREATE TABLE concepts (
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, aliases TEXT NOT NULL, definition TEXT,
            state TEXT NOT NULL, type TEXT NOT NULL, parent_concept_id TEXT, confidence REAL NOT NULL,
            provenance TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
            example_count INTEGER NOT NULL)""".trimIndent())
        database.execSQL("""CREATE TABLE examples (
            id TEXT PRIMARY KEY NOT NULL, concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
            embedding BLOB NOT NULL, source_frame_id INTEGER, observed_ms INTEGER NOT NULL,
            box_left REAL, box_top REAL, box_right REAL, box_bottom REAL, confidence REAL NOT NULL, crop_reference TEXT)""".trimIndent())
        database.execSQL("""CREATE TABLE evidence (
            id TEXT PRIMARY KEY NOT NULL, concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
            source TEXT NOT NULL, value TEXT NOT NULL, confidence REAL NOT NULL, observed_ms INTEGER NOT NULL)""".trimIndent())
        database.execSQL("""CREATE TABLE relations (
            id TEXT PRIMARY KEY NOT NULL, source_concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
            target_concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE, type TEXT NOT NULL)""".trimIndent())
        database.execSQL("CREATE INDEX examples_concept_index ON examples(concept_id)")
        database.execSQL("CREATE INDEX evidence_concept_index ON evidence(concept_id)")
        database.execSQL("CREATE INDEX relations_source_index ON relations(source_concept_id)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("No destructive concept-memory migration is permitted: $oldVersion to $newVersion")
    }

    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Concept-memory downgrade is unsupported: $oldVersion to $newVersion")
    }

    @Synchronized fun create(concept: VisualConcept): VisualConcept {
        writableDatabase.insertOrThrow("concepts", null, concept.values())
        return concept
    }

    @Synchronized fun read(id: String): VisualConcept? = readableDatabase.queryOne(
        "concepts", "id = ?", arrayOf(id), ::conceptFromCursor,
    )

    @Synchronized fun listConcepts(): List<VisualConcept> = readableDatabase.queryList(
        "concepts", null, null, "name COLLATE NOCASE, id", ::conceptFromCursor,
    )

    /** Inserts an example and optional evidence atomically, then updates durable observation fields. */
    @Synchronized fun recordObservation(example: VisualConceptExample, evidence: Collection<ConceptEvidence> = emptyList()): VisualConcept {
        val database = writableDatabase
        database.beginTransaction()
        try {
            require(readIn(database, example.conceptId) != null) { "Unknown visual concept ${example.conceptId}." }
            database.insertOrThrow("examples", null, example.values())
            evidence.forEach {
                require(it.conceptId == example.conceptId) { "Evidence must belong to the observed concept." }
                database.insertOrThrow("evidence", null, it.values())
            }
            database.execSQL(
                "UPDATE concepts SET example_count = example_count + 1, last_seen_ms = MAX(last_seen_ms, ?) WHERE id = ?",
                arrayOf<Any>(example.observedAt.toEpochMilli(), example.conceptId),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return requireNotNull(read(example.conceptId))
    }

    @Synchronized fun listExamples(conceptId: String? = null): List<VisualConceptExample> = readableDatabase.queryList(
        "examples", conceptId?.let { "concept_id = ?" }, conceptId?.let { arrayOf(it) }, "observed_ms, id", ::exampleFromCursor,
    )

    @Synchronized fun listEvidence(conceptId: String): List<ConceptEvidence> = readableDatabase.queryList(
        "evidence", "concept_id = ?", arrayOf(conceptId), "observed_ms, id", ::evidenceFromCursor,
    )

    @Synchronized fun addRelation(relation: VisualConceptRelation): VisualConceptRelation {
        writableDatabase.insertOrThrow("relations", null, relation.values())
        return relation
    }

    @Synchronized fun listRelations(conceptId: String): List<VisualConceptRelation> = readableDatabase.queryList(
        "relations", "source_concept_id = ? OR target_concept_id = ?", arrayOf(conceptId, conceptId), "id", ::relationFromCursor,
    )

    @Synchronized fun updateStateAndConfidence(id: String, state: VisualConceptState, confidence: Float): VisualConcept {
        require(confidence.isFinite() && confidence in 0f..1f)
        val changed = writableDatabase.update("concepts", ContentValues().apply {
            put("state", state.name); put("confidence", confidence)
        }, "id = ?", arrayOf(id))
        require(changed == 1) { "Unknown visual concept $id." }
        return requireNotNull(read(id))
    }

    /** Test-only cleanup hook; production code has no broad destructive operation. */
    @Synchronized internal fun deleteForTests(id: String): Boolean = writableDatabase.delete("concepts", "id = ?", arrayOf(id)) == 1

    private fun readIn(database: SQLiteDatabase, id: String): VisualConcept? = database.queryOne(
        "concepts", "id = ?", arrayOf(id), ::conceptFromCursor,
    )

    private fun VisualConcept.values() = ContentValues().apply {
        put("id", id); put("name", name); put("aliases", JSONArray(aliases).toString()); put("definition", definition)
        put("state", state.name); put("type", type.name); put("parent_concept_id", parentConceptId); put("confidence", confidence)
        put("provenance", provenance); put("first_seen_ms", firstSeenAt.toEpochMilli()); put("last_seen_ms", lastSeenAt.toEpochMilli())
        put("example_count", exampleCount)
    }

    private fun VisualConceptExample.values() = ContentValues().apply {
        put("id", id); put("concept_id", conceptId); put("embedding", embedding.toBlob()); put("source_frame_id", sourceFrameId)
        put("observed_ms", observedAt.toEpochMilli()); put("box_left", boundingBox?.left); put("box_top", boundingBox?.top)
        put("box_right", boundingBox?.right); put("box_bottom", boundingBox?.bottom); put("confidence", confidence); put("crop_reference", cropReference)
    }

    private fun ConceptEvidence.values() = ContentValues().apply {
        put("id", id); put("concept_id", conceptId); put("source", source.name); put("value", value); put("confidence", confidence); put("observed_ms", observedAt.toEpochMilli())
    }

    private fun VisualConceptRelation.values() = ContentValues().apply {
        put("id", id); put("source_concept_id", sourceConceptId); put("target_concept_id", targetConceptId); put("type", type.name)
    }

    private fun conceptFromCursor(cursor: Cursor) = VisualConcept(
        id = cursor.string("id"), name = cursor.string("name"), aliases = JSONArray(cursor.string("aliases")).let { array -> List(array.length()) { array.getString(it) } },
        definition = cursor.nullableString("definition"), state = VisualConceptState.valueOf(cursor.string("state")), type = VisualConceptType.valueOf(cursor.string("type")),
        parentConceptId = cursor.nullableString("parent_concept_id"), confidence = cursor.float("confidence"), provenance = cursor.string("provenance"),
        firstSeenAt = Instant.ofEpochMilli(cursor.long("first_seen_ms")), lastSeenAt = Instant.ofEpochMilli(cursor.long("last_seen_ms")), exampleCount = cursor.int("example_count"),
    )

    private fun exampleFromCursor(cursor: Cursor) = VisualConceptExample(
        id = cursor.string("id"), conceptId = cursor.string("concept_id"), embedding = VisualEmbedding.fromBlob(cursor.blob("embedding")),
        sourceFrameId = cursor.nullableLong("source_frame_id"), observedAt = Instant.ofEpochMilli(cursor.long("observed_ms")),
        boundingBox = cursor.nullableFloat("box_left")?.let { left -> NormalizedVisualRegion(left, cursor.float("box_top"), cursor.float("box_right"), cursor.float("box_bottom")) },
        confidence = cursor.float("confidence"), cropReference = cursor.nullableString("crop_reference"),
    )

    private fun evidenceFromCursor(cursor: Cursor) = ConceptEvidence(cursor.string("id"), cursor.string("concept_id"), ConceptEvidenceSource.valueOf(cursor.string("source")), cursor.string("value"), cursor.float("confidence"), Instant.ofEpochMilli(cursor.long("observed_ms")))
    private fun relationFromCursor(cursor: Cursor) = VisualConceptRelation(cursor.string("id"), cursor.string("source_concept_id"), cursor.string("target_concept_id"), VisualConceptRelationType.valueOf(cursor.string("type")))

    private fun <T> SQLiteDatabase.queryOne(table: String, selection: String, arguments: Array<String>, mapper: (Cursor) -> T): T? = query(table, null, selection, arguments, null, null, null).use { cursor -> if (cursor.moveToFirst()) mapper(cursor) else null }
    private fun <T> SQLiteDatabase.queryList(table: String, selection: String?, arguments: Array<String>?, orderBy: String, mapper: (Cursor) -> T): List<T> = query(table, null, selection, arguments, null, null, orderBy).use { cursor -> buildList { while (cursor.moveToNext()) add(mapper(cursor)) } }
    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.float(column: String) = getFloat(getColumnIndexOrThrow(column))
    private fun Cursor.nullableFloat(column: String): Float? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getFloat(it) }
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.blob(column: String) = getBlob(getColumnIndexOrThrow(column))

    private companion object { const val DATABASE_NAME = "foresight-visual-concepts.db"; const val DATABASE_VERSION = 1 }
}
