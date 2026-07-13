package com.example.ondeviceai.bench

interface BenchProbe {
    fun onInitStart()
    fun onInitEnd()
    fun onGenerateStart()
    fun onFirstToken()
    fun onGenerateComplete(tokenCount: Int)
    fun onFallback(reason: String)
}

object NoOpBenchProbe : BenchProbe {
    override fun onInitStart() = Unit
    override fun onInitEnd() = Unit
    override fun onGenerateStart() = Unit
    override fun onFirstToken() = Unit
    override fun onGenerateComplete(tokenCount: Int) = Unit
    override fun onFallback(reason: String) = Unit
}
