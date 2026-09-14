package com.foresight.gateway.vision.concepts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualConceptRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: VisualConceptRepository

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        repository = VisualConceptRepository(context)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun conceptExamplesEvidenceAndRelationsSurviveReopen() {
        val concept = VisualConcept("bottle", "red insulated bottle", aliases = listOf("red bottle"), type = VisualConceptType.PRODUCT, provenance = "developer-test", firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH)
        repository.create(concept)
        repository.recordObservation(
            VisualConceptExample("example-1", concept.id, VisualEmbedding.normalized(floatArrayOf(1f, 0.05f, 0f)), 7L, Instant.ofEpochMilli(10), NormalizedVisualRegion(0.1f, 0.1f, 0.5f, 0.8f), 0.9f),
            listOf(ConceptEvidence("evidence-1", concept.id, ConceptEvidenceSource.DETECTOR_LABEL, "bottle", 0.9f, Instant.ofEpochMilli(10))),
        )
        repository.addRelation(VisualConceptRelation("relation-1", concept.id, concept.id, VisualConceptRelationType.RELATED_TO))
        repository.close()
        repository = VisualConceptRepository(context)

        val restored = requireNotNull(repository.read(concept.id))
        assertEquals(1, restored.exampleCount)
        assertEquals(listOf("red bottle"), restored.aliases)
        assertEquals(1, repository.listExamples(concept.id).size)
        assertEquals(1, repository.listEvidence(concept.id).size)
        assertEquals(1, repository.listRelations(concept.id).size)
        assertTrue(repository.deleteForTests(concept.id))
    }

    private companion object { const val DATABASE_NAME = "foresight-visual-concepts.db" }
}
