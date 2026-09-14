package com.foresight.gateway.vision.concepts

/** Small orchestration facade; callers opt in explicitly and it never reads frames or changes UI. */
class VisualConceptMemory(
    private val repository: VisualConceptRepository,
    private val recognizer: ConceptRecognitionEngine = ConceptRecognitionEngine(),
    private val learningPolicy: ConceptLearningPolicy = ConceptLearningPolicy(),
) {
    fun createCandidate(concept: VisualConcept): Result<VisualConcept> = runCatching {
        require(concept.state == VisualConceptState.CANDIDATE) { "New concepts must begin as candidates." }
        repository.create(concept)
    }

    fun recordObservation(example: VisualConceptExample, evidence: Collection<ConceptEvidence> = emptyList()): Result<VisualConcept> = runCatching {
        repository.recordObservation(example, evidence)
        val decision = learningPolicy.evaluate(repository.listExamples(example.conceptId), repository.listEvidence(example.conceptId))
        repository.updateStateAndConfidence(example.conceptId, decision.state, example.confidence)
    }

    fun recognize(query: VisualEmbedding): Result<List<ConceptRecognitionEngine.ConceptMatch>> =
        recognizer.recognize(query, repository.listConcepts(), repository.listExamples())
}
