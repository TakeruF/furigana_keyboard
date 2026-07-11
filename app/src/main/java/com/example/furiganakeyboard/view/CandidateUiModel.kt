package com.example.furiganakeyboard.view

enum class CandidateKind { CHARACTER, WORD, BUNSETSU, STATUS, SEGMENT_SHRINK, SEGMENT_EXPAND }

data class CandidateUiModel(
    val text: String,
    val readings: List<String> = emptyList(),
    val kind: CandidateKind,
    /** Reading prefix committed by a bunsetsu candidate. */
    val bunsetsuReading: String? = null,
)
