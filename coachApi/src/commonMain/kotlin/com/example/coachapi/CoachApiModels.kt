package com.example.coachapi

import kotlinx.serialization.Serializable

/**
 * Wire models for the opening-explainer API.
 *
 * PUBLIC_OR_SYNTHETIC contract: requests contain chess position data and locale preferences only.
 * They must never gain user identifiers, account data, device identifiers, or free-form user text.
 */
@Serializable
data class OpeningExplainRequest(
    val fen: String,
    val movesSan: List<String>,
    val eco: String? = null,
    val locale: String? = null,
)

@Serializable
data class Passage(
    val sourceId: String,
    val title: String,
    val text: String,
)

@Serializable
data class OpeningExplainResponse(
    val text: String,
    val passages: List<Passage>,
    val composerId: String,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)
