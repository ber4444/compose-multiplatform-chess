package com.example.ondeviceai

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
actual annotation class Generable()

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
actual annotation class Guide(actual val description: String)
