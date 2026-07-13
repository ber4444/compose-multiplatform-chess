package com.example.myapplication.bench

data class BenchResult(
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val modelIdentifier: String,
    val isWarm: Boolean,
    val timestampMs: Long,
    val initStartMs: Long,
    val initEndMs: Long,
    val generateStartMs: Long,
    val firstTokenMs: Long,
    val completeMs: Long,
    val tokenCount: Int,
    val peakMemoryBytes: Long,
    val thermalStatusBefore: Int,
    val thermalStatusAfter: Int,
    val fallbackTriggered: Boolean,
    val isEmulator: Boolean,
)
