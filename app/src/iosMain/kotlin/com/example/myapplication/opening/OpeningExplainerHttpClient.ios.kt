package com.example.myapplication.opening

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun openingExplainerHttpClientEngine(): HttpClientEngine = Darwin.create()
