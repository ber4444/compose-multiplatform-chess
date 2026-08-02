package com.example.ondeviceai.bench

import com.example.ondeviceai.AiRoutePolicyDecider

interface BenchProbe {
    fun onInitStart()
    fun onInitEnd()
    fun onGenerateStart()
    fun onFirstToken()
    fun onGenerateComplete(tokenCount: Int)
    fun onFallback(reason: AiRoutePolicyDecider.FallbackReason)
    /** The accumulated raw text handed to JSON parsing, before any validation. Default no-op so
     *  existing implementers don't need to change; the bench runners override it to capture what
     *  the model actually produced, since onFallback's reason string can't distinguish a JSON-parse
     *  failure from a post-parse validation rejection. */
    fun onRawOutput(text: String) {}
}

object NoOpBenchProbe : BenchProbe {
    override fun onInitStart() = Unit
    override fun onInitEnd() = Unit
    override fun onGenerateStart() = Unit
    override fun onFirstToken() = Unit
    override fun onGenerateComplete(tokenCount: Int) = Unit
    override fun onFallback(reason: AiRoutePolicyDecider.FallbackReason) = Unit
}
