package com.example.myapplication.bench

import android.content.Context
import android.os.Build
import com.example.ondeviceai.OnDeviceRulesQaAnswerer
import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.createBundledRuleLookupTool
import com.example.ondeviceai.probeAvailableLocalVendors
import java.io.File

internal suspend fun runAndroidRulesQaBench(context: Context, iterations: Int) {
    require(iterations > 0)
    val override = File(context.filesDir, "golden/rules_qa_fixtures.json")
    val input = if (override.exists()) override.readText()
    else context.assets.open("golden/rules_qa_fixtures.json").bufferedReader().use { it.readText() }
    val fixtures = RulesQaFixtures.load(input)
    val file = File(context.filesDir, "bench/rules_qa.jsonl")
    file.parentFile?.mkdirs()
    file.writeText("")

    val vendors = probeAvailableLocalVendors()
    val answerer = OnDeviceRulesQaAnswerer(VendorRouteExecutor(), createBundledRuleLookupTool())
    repeat(iterations) {
        for (fixture in fixtures) {
            val row = RulesQaFixtures.measure(fixture, vendors, Build.MODEL, Build.VERSION.RELEASE) { record ->
                RulesQaAnswerer { question, route -> answerer.answer(question, route, record) }
            }
            file.appendText(row + "\n")
        }
    }
}
