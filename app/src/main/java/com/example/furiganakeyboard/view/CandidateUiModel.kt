package com.example.furiganakeyboard.view

enum class CandidateKind { CHARACTER, WORD, STATUS, SEGMENT_START, SEGMENT_SHRINK, SEGMENT_EXPAND }

data class CandidateUiModel(
    val text: String,
    val readings: List<String> = emptyList(),
    val kind: CandidateKind
)
