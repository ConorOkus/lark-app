package xyz.lark.app.core.gateway

import io.ktor.client.engine.HttpClientEngine

/**
 * The platform HTTP engine backing [BarkdApi] (plan U6): OkHttp on Android, Darwin on iOS.
 * Created per call; the composition root builds exactly one for the app-scoped core.
 */
expect fun httpClientEngine(): HttpClientEngine
