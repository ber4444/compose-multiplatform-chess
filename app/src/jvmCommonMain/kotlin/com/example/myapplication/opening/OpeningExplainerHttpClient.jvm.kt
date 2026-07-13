package com.example.myapplication.opening

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun openingExplainerHttpClientEngine(): HttpClientEngine = CIO.create()
