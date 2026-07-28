package com.example.ondeviceai

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
expect annotation class Generable()

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
expect annotation class Guide(val description: String)
