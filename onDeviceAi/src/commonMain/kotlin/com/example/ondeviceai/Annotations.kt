package com.example.ondeviceai

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
expect annotation class Generable()

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
expect annotation class Guide(
    val description: String = "",
    val maxItems: Int = -1,
    val minItems: Int = -1,
    val maximum: Double = Double.NaN,
    val minimum: Double = Double.NaN,
    val enumValues: Array<String> = []
)
