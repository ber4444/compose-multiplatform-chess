package com.example.ondeviceai

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

internal actual fun defaultNowMs(): Long = jsDateNow().toLong()
