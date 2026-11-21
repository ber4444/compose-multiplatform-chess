package com.example.myapplication.bench

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

@OptIn(ExperimentalForeignApi::class)
internal fun appendToFile(path: String, text: String) {
    val file = fopen(path, "a")
    if (file != null) {
        fputs(text, file)
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeFile(path: String, text: String) {
    val file = fopen(path, "w")
    if (file != null) {
        fputs(text, file)
        fclose(file)
    }
}
