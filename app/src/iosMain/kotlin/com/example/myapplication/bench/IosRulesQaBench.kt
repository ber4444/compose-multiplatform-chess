package com.example.myapplication.bench

import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.createBundledRuleLookupTool
import com.example.ondeviceai.defaultRulesQaAnswerer
import com.example.ondeviceai.probeAvailableLocalVendors
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.uname
import platform.posix.utsname
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
suspend fun runIosRulesQaBench(iterations: Int) {
    require(iterations > 0)
    val documents = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, inDomains = 1uL)
        .first() as NSURL
    val fixturePath = documents.URLByAppendingPathComponent("golden/rules_qa_fixtures.json")!!.path!!
    val input = checkNotNull(
        NSString.stringWithContentsOfFile(fixturePath, encoding = NSUTF8StringEncoding, error = null),
    ) { "Missing Rules Q&A fixtures at $fixturePath" }
    val fixtures = RulesQaFixtures.load(input)
    val file = documents.URLByAppendingPathComponent("rules_qa_results.jsonl")!!.path!!
    writeFile(file, "")

    val answerer = checkNotNull(defaultRulesQaAnswerer(createBundledRuleLookupTool())) {
        "The Foundation Models Rules Q&A bridge is not registered"
    }
    val vendors = probeAvailableLocalVendors()
    val process = NSProcessInfo.processInfo
    val device = process.environment["SIMULATOR_DEVICE_NAME"] as? String ?: memScoped {
        val info = alloc<utsname>()
        if (uname(info.ptr) == 0) info.machine.toKString() else "iPhone"
    }
    repeat(iterations) {
        for (fixture in fixtures) {
            val row = RulesQaFixtures.measure(fixture, vendors, device, process.operatingSystemVersionString) { record ->
                RulesQaAnswerer { question, route -> answerer.answer(question, route).also { record(it, null) } }
            }
            appendToFile(file, row + "\n")
        }
    }
    println("IosRulesQaBench: wrote ${fixtures.size * iterations} rows to $file")
}
