package xyz.lark.app.core.gateway

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Health mapping and poll-loop scheduling (plan R6/R7): OFFLINE classification, backoff +
 * ping recovery, STALE/TIDYING, and the long-poll loop that never drives OFFLINE.
 * All timing is virtual: the MockEngine is pinned to the test scheduler.
 */
class GatewayLarkCoreHealthTest {

    @Test
    fun firstPollCycleFetchesWalletStateAndReportsReady() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.READY, core.health.value)
        assertTrue(core.walletExists.value)
        assertEquals(412_350L, core.balanceSats.value)
        assertEquals(1, script.countOf(Paths.BALANCE))
        assertEquals(1, script.countOf(Paths.VTXOS))
        assertEquals(1, script.countOf(Paths.HISTORY))
    }

    @Test
    fun ae1TimeoutGoesOfflineThenPingRecoveryReturnsReady() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.READY, core.health.value)

        script.sticky(Paths.BALANCE, BarkdScript.Broken("connect timeout"))
        advanceThrough(15.seconds)
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.UNREACHABLE, core.offlineReason.value)
        assertEquals(SendResult.Failure, core.send("ark1qf7demo", 100))

        script.clearSticky(Paths.BALANCE)
        advanceThrough(1.seconds) // first backoff step: ping answers, full cycle re-runs
        assertEquals(HealthState.READY, core.health.value)
        assertNull(core.offlineReason.value)
    }

    @Test
    fun offlineBackoffSchedulePinnedAndPingGatesRecovery() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.BALANCE, BarkdScript.Broken())
        script.sticky(Paths.PING, BarkdScript.Broken())
        val core = gatewayCore(barkdEngine(script), backoff = listOf(1.seconds, 2.seconds, 4.seconds))
        runCurrent()
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(0, script.countOf(Paths.PING))

        advanceThrough(1.seconds) // t=1: first probe
        assertEquals(1, script.countOf(Paths.PING))
        advanceThrough(2.seconds) // t=3: second probe
        assertEquals(2, script.countOf(Paths.PING))
        advanceThrough(4.seconds) // t=7: third probe
        assertEquals(3, script.countOf(Paths.PING))
        advanceThrough(4.seconds) // t=11: schedule caps at its last step
        assertEquals(4, script.countOf(Paths.PING))
        // No full poll cycles while the ping probe keeps failing.
        assertEquals(1, script.countOf(Paths.BALANCE))

        script.clearSticky(Paths.PING)
        script.clearSticky(Paths.BALANCE)
        advanceThrough(4.seconds) // t=15: ping answers -> full cycle -> READY
        assertEquals(HealthState.READY, core.health.value)
        assertEquals(2, script.countOf(Paths.BALANCE))
    }

    @Test
    fun persistentServerErrorStreakGoesOfflineWhileGatewayReachable() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.BALANCE, BarkdScript.Json("""{"error": "boom"}""", HttpStatusCode.InternalServerError))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.READY, core.health.value) // streak 1: tolerated as transient
        advanceThrough(15.seconds)
        assertEquals(HealthState.READY, core.health.value) // streak 2: still tolerated
        advanceThrough(15.seconds)
        assertEquals(HealthState.OFFLINE, core.health.value) // streak 3: persistent
        assertEquals(GatewayOfflineReason.SERVER_ERROR, core.offlineReason.value)

        // Recovery probes answer (the gateway is reachable) but cycles keep 5xx-ing: stays OFFLINE.
        advanceThrough(1.seconds)
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertTrue(script.countOf(Paths.PING) >= 1)
    }

    @Test
    fun arkServerDisconnectedGoesOfflineAndRecovers() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.CONNECTED, BarkdScript.Json("""{"connected": false}"""))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.ARK_DISCONNECTED, core.offlineReason.value)

        script.clearSticky(Paths.CONNECTED)
        advanceThrough(1.seconds)
        assertEquals(HealthState.READY, core.health.value)
    }

    @Test
    fun authRequiredGoesOfflineWithDistinguishableReason() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.BALANCE, BarkdScript.Json("""{"error": "auth required"}""", HttpStatusCode.Unauthorized))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.AUTH_REQUIRED, core.offlineReason.value)
        assertEquals(SendResult.Failure, core.send("ark1qf7demo", 100))
        assertEquals(0, script.countOf(Paths.SEND))
    }

    @Test
    fun longPollFailuresReissueTheWaitAndNeverDriveOffline() = runTest {
        // Default script: notifications/wait always fails (timeout-equivalent Unreachable).
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        repeat(10) { advanceThrough(10.seconds) }
        assertEquals(HealthState.READY, core.health.value)
        assertNull(core.offlineReason.value)
        assertTrue(script.countOf(Paths.WAIT) >= 5, "long poll should keep re-issuing")
    }

    @Test
    fun movementNotificationTriggersImmediatePollCycle() = runTest {
        val script = BarkdScript()
        script.enqueue(
            Paths.WAIT,
            BarkdScript.Json(
                """
                {
                  "notifications": [{"type": "movement-created", "movement": ${BarkdFixtures.MOVEMENT}}],
                  "last_pushed_at": "2026-07-28T10:00:06Z"
                }
                """.trimIndent(),
            ),
        )
        gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(2, script.countOf(Paths.BALANCE), "movement event should trigger an immediate cycle")
        val waits = script.requests(Paths.WAIT)
        assertNull(waits[0].url.parameters["since"])
        assertEquals("2026-07-28T10:00:06Z", waits[1].url.parameters["since"])
    }

    @Test
    fun channelLaggingTriggersFullResyncAndResetsSince() = runTest {
        val script = BarkdScript()
        script.enqueue(
            Paths.WAIT,
            BarkdScript.Json(
                """
                {
                  "notifications": [{"type": "channel-lagging"}],
                  "last_pushed_at": "2026-07-28T10:00:06Z"
                }
                """.trimIndent(),
            ),
        )
        gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(2, script.countOf(Paths.BALANCE), "lagging should force a resync cycle")
        val waits = script.requests(Paths.WAIT)
        assertNull(waits[1].url.parameters["since"], "lagging must reset since to re-read the whole buffer")
    }

    @Test
    fun staleWhenSoonestVtxoExpiryNearsChainTip() = runTest {
        val script = BarkdScript()
        // The vendored fixture: expiry 918,402 vs tip 916,214 = 2,188 blocks left, under 12,960 / 2.
        script.sticky(Paths.VTXOS, BarkdScript.Json(BarkdFixtures.VTXOS))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.STALE, core.health.value)
    }

    @Test
    fun refreshPostsRefreshAllAndPresentsTidyingThenReady() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.REFRESH, BarkdScript.Slow(1_000, BarkdScript.Json(BarkdFixtures.PENDING_ROUND)))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        val refreshing = launch { core.refresh() }
        runCurrent()
        assertEquals(HealthState.TIDYING, core.health.value)
        advanceThrough(500.milliseconds)
        assertEquals(HealthState.TIDYING, core.health.value)
        advanceThrough(600.milliseconds)
        assertEquals(HealthState.READY, core.health.value)
        assertTrue(refreshing.isCompleted)
        assertEquals(HttpMethod.Post, script.requests(Paths.REFRESH).single().method)
    }

    @Test
    fun pollingStopsWhenTheScopeIsCancelled() = runTest {
        val script = BarkdScript()
        val loopScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        gatewayCore(barkdEngine(script), scope = loopScope)
        runCurrent()
        assertEquals(1, script.countOf(Paths.BALANCE))

        loopScope.cancel()
        advanceThrough(120.seconds)
        assertEquals(1, script.countOf(Paths.BALANCE), "no polling after scope cancel")
        assertEquals(1, script.countOf(Paths.WAIT), "no long-polling after scope cancel")
    }
}
