package com.example.myapplication.opening

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

internal actual fun openingExplainerHttpClientEngine(): HttpClientEngine = Js.create()
