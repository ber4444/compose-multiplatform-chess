package com.example.ondeviceai

/**
 * The web build has no rules model, but it has the bundled rulebook, and answering from that costs
 * nothing. So the Rules screen works on the web, answering with the best-matching rule and its
 * source. See [RetrievalOnlyRulesQaAnswerer].
 */
actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? =
    RetrievalOnlyRulesQaAnswerer(lookupTool)
