package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun Flow<AiTokenOrFinal>.withAntiRepetitionGuard(
    ngramSize: Int?,
    stopSequences: List<String>
): Flow<AiTokenOrFinal> = flow {
    val collectedText = StringBuilder()
    var isStopped = false

    collect { piece ->
        if (isStopped) return@collect
        
        when (piece) {
            is AiTokenOrFinal.Token -> {
                val newText = piece.text
                if (newText.isEmpty()) {
                    emit(piece)
                    return@collect
                }
                
                // If a stop sequence is found in the newly formed string
                val beforeAppend = collectedText.toString()
                collectedText.append(newText)
                val currentStr = collectedText.toString()
                
                var stopIdx = -1
                for (stopSeq in stopSequences) {
                    val idx = currentStr.indexOf(stopSeq)
                    if (idx != -1 && (stopIdx == -1 || idx < stopIdx)) {
                        stopIdx = idx
                    }
                }
                
                if (stopIdx != -1) {
                    // Truncate the new text to just before the stop sequence
                    val keepLength = stopIdx - beforeAppend.length
                    if (keepLength > 0) {
                        emit(AiTokenOrFinal.Token(newText.substring(0, keepLength)))
                    }
                    isStopped = true
                    return@collect
                }
                
                if (ngramSize != null && currentStr.hasRepeatNgram(ngramSize)) {
                    isStopped = true
                    return@collect
                }
                
                emit(piece)
            }
            is AiTokenOrFinal.Final -> {
                var finalStr = piece.text
                if (finalStr.isNotEmpty()) {
                    for (stopSeq in stopSequences) {
                        val idx = finalStr.indexOf(stopSeq)
                        if (idx != -1) {
                            finalStr = finalStr.substring(0, idx)
                        }
                    }
                    if (ngramSize != null) {
                        finalStr = finalStr.truncateAtRepetition(ngramSize)
                    }
                }
                emit(
                    AiTokenOrFinal.Final(
                        text = finalStr,
                        metrics = piece.metrics.copy(
                            tokenCount = finalStr.split(Regex("\\s+")).count { it.isNotBlank() }
                        )
                    )
                )
            }
        }
    }
}

internal fun String.hasRepeatNgram(n: Int): Boolean {
    val words = this.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < n * 2) return false
    val lastN = words.subList(words.size - n, words.size)
    for (i in 0..words.size - n - 1) {
        if (words.subList(i, i + n) == lastN) return true
    }
    return false
}

internal fun String.truncateAtRepetition(n: Int): String {
    val words = this.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < n * 2) return this
    
    for (i in n..words.size - n) {
        val candidate = words.subList(i, i + n)
        for (j in 0..i - n) {
            if (words.subList(j, j + n) == candidate) {
                // Cheap truncation: just join the words before `i`
                return words.subList(0, i).joinToString(" ")
            }
        }
    }
    return this
}
