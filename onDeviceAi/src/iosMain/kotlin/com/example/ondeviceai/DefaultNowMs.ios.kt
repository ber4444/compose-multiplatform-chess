package com.example.ondeviceai

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun defaultNowMs(): Long = (NSDate().timeIntervalSince1970() * 1000).toLong()
