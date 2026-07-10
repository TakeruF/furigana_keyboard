package com.example.furiganakeyboard.view

enum class CandidateKind { CHARACTER, WORD, STATUS }

data class CandidateUiModel(
    val text: String,
    val readings: List<String> = emptyList(),
    val kind: CandidateKind
)
