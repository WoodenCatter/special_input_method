package com.example.input_ds.personalization

data class ConversationTurn(
    val role: String,
    val text: String
)

data class InitialSentenceRequest(
    val requestId: String,
    val blocksKey: String,
    val currentText: String,
    val conversation: List<ConversationTurn>,
    val localCandidates: List<String>,
    val limit: Int = 16
)

data class InitialSentenceCandidate(
    val id: String,
    val text: String,
    val source: String
)

data class InitialSentenceResponse(
    val requestId: String,
    val blocksKey: String,
    val candidates: List<InitialSentenceCandidate>,
    val hasMore: Boolean,
    val timings: PredictionTimings
)

data class ContextCompletionRequest(
    val requestId: String,
    val currentText: String,
    val conversation: List<ConversationTurn>,
    val localCandidates: List<String>,
    val knownEntities: List<String> = emptyList(),
    val clientTime: String? = null,
    val limit: Int = 12
)

data class ContextCompletionCandidate(
    val id: String,
    val fullText: String,
    val appendText: String,
    val source: String
)

data class ContextCompletionResponse(
    val requestId: String,
    val currentText: String,
    val candidates: List<ContextCompletionCandidate>,
    val timings: PredictionTimings
)

data class PredictionTimings(
    val indexMs: Double = 0.0,
    val queueMs: Double = 0.0,
    val llmMs: Double = 0.0,
    val validateMs: Double = 0.0,
    val postprocessMs: Double = 0.0,
    val totalMs: Double = 0.0
)
