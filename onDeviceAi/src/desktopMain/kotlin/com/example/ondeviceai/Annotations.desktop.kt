package com.example.ondeviceai

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
actual annotation class Generable()

@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
actual annotation class Guide(
    actual val description: String = "",
    actual val maxItems: Int = -1,
    actual val minItems: Int = -1,
    actual val maximum: Double = Double.NaN,
    actual val minimum: Double = Double.NaN,
    actual val enumValues: Array<String> = []
)
