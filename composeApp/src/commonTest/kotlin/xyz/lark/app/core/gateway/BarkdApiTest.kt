package xyz.lark.app.core.gateway

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://barkd.test"

class BarkdApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    /** Routes every endpoint of the U2 subset to its spec-derived fixture. */
    private fun fixtureEngine() = MockEngine { request ->
        val path = request.url.encodedPath
        if (path == "/ping") {
            respond("pong", HttpStatusCode.OK)
        } else {
            respond(BarkdFixtures.byPath.getValue(path), HttpStatusCode.OK, jsonHeaders)
        }
    }

    /** Routes the FORK_BETA6 surface to its fork-spec fixtures. */
    private fun forkFixtureEngine() = MockEngine { request ->
        respond(BarkdFixtures.forkByPath.getValue(request.url.encodedPath), HttpStatusCode.OK, jsonHeaders)
    }

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        MockEngine { respond(body, status, jsonHeaders) }

    private fun api(engine: HttpClientEngine, auth: AuthDecorator = NoAuth) =
        BarkdApi(engine, BASE_URL, auth)

    private fun forkApi(engine: HttpClientEngine, auth: AuthDecorator = NoAuth) =
        BarkdApi(engine, BASE_URL, auth, BarkdApiVariant.FORK_BETA6)

    private fun <T> BarkdResult<T>.okValue(): T = assertIs<BarkdResult.Ok<T>>(this).value

    private fun MockEngine.singleRequestLine(): Pair<HttpMethod, String> {
        val request = requestHistory.single()
        return request.method to request.url.encodedPath
    }

    /** One call per endpoint in the U2 subset, so whole-surface tests cannot miss one. */
    private suspend fun callAllEndpoints(api: BarkdApi): List<BarkdResult<*>> = listOf(
        api.ping(),
        api.walletExists(),
        api.balance(),
        api.vtxos(),
        api.history(),
        api.bip321(Bip321UriRequest(amountSat = 21_000)),
        api.send(SendRequest(destination = "ark1qf7demo", amountSat = 520)),
        api.refreshAll(),
        api.createWallet(CreateWalletRequest(network = "signet")),
        api.mnemonic(),
        api.waitNotifications(),
        api.connected(),
        api.tip(),
        api.arkInfo(),
    )

    // --- Happy path per endpoint ---

    @Test
    fun pingHitsTopLevelPathAndReturnsOk() = runTest {
        val engine = fixtureEngine()
        val result = api(engine).ping()
        assertEquals(Unit, result.okValue())
        assertEquals(HttpMethod.Get to "/ping", engine.singleRequestLine())
    }

    @Test
    fun walletExistsDecodesFingerprint() = runTest {
        val engine = fixtureEngine()
        val response = api(engine).walletExists().okValue()
        assertEquals("f00dbabe", response.fingerprint)
        assertEquals(HttpMethod.Get to "/api/v1/wallet", engine.singleRequestLine())
    }

    @Test
    fun walletExistsDecodesNullFingerprintWhenNoWallet() = runTest {
        val engine = jsonEngine("""{"fingerprint": null}""")
        val response = api(engine).walletExists().okValue()
        assertNull(response.fingerprint)
    }

    @Test
    fun balanceDecodesSpendableAndPendingFields() = runTest {
        val engine = fixtureEngine()
        val balance = api(engine).balance().okValue()
        assertEquals(412_350L, balance.spendableSat)
        assertEquals(1_200L, balance.pendingLightningSendSat)
        assertEquals(800L, balance.claimableLightningReceiveSat)
        assertEquals(5_000L, balance.pendingInRoundSat)
        assertEquals(20_000L, balance.pendingBoardSat)
        assertEquals(6_200L, balance.pendingExitSat)
        assertEquals(HttpMethod.Get to "/api/v1/wallet/balance", engine.singleRequestLine())
    }

    @Test
    fun vtxosDecodesAmountExpiryAndState() = runTest {
        val engine = fixtureEngine()
        val vtxos = api(engine).vtxos().okValue()
        val vtxo = vtxos.single()
        assertEquals(103_087L, vtxo.amountSat)
        assertEquals(918_402L, vtxo.expiryHeight)
        assertEquals("spendable", vtxo.state.type)
        assertTrue(vtxo.id.endsWith(":0"))
        assertEquals(HttpMethod.Get to "/api/v1/wallet/vtxos", engine.singleRequestLine())
    }

    @Test
    fun historyDecodesMovements() = runTest {
        val engine = fixtureEngine()
        val movements = api(engine).history().okValue()
        val movement = movements.single()
        assertEquals(7, movement.id)
        assertEquals("successful", movement.status)
        assertEquals("arkoor", movement.subsystem.name)
        assertEquals(-14_200L, movement.intendedBalanceSat)
        assertEquals(-14_250L, movement.effectiveBalanceSat)
        assertEquals(50L, movement.offchainFeeSat)
        assertEquals("ark", movement.sentTo.single().destination.type)
        assertEquals(14_200L, movement.sentTo.single().amountSat)
        assertEquals(1, movement.inputVtxos.size)
        assertEquals(1, movement.outputVtxos.size)
        assertTrue(movement.exitedVtxos.isEmpty())
        assertEquals("2026-07-28T10:00:00Z", movement.time.createdAt)
        assertEquals(HttpMethod.Get to "/api/v1/history", engine.singleRequestLine())
    }

    @Test
    fun bip321PostsRequestAndDecodesUri() = runTest {
        val engine = fixtureEngine()
        val request = Bip321UriRequest(amountSat = 21_000, label = "Lark")
        val response = api(engine).bip321(request).okValue()
        assertEquals("bitcoin:bc1qf7demo?ark=ark1qf7demo", response.bip321)
        assertEquals("ark1qf7demo", response.ark)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/bip321", engine.singleRequestLine())
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"amount_sat\":21000"), body)
        assertTrue(body.contains("\"label\":\"Lark\""), body)
    }

    @Test
    fun sendPostsDestinationAndDecodesMessage() = runTest {
        val engine = fixtureEngine()
        val response = api(engine).send(SendRequest(destination = "ark1qf7demo", amountSat = 520)).okValue()
        assertEquals("Payment sent", response.message)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/send", engine.singleRequestLine())
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"destination\":\"ark1qf7demo\""), body)
        assertTrue(body.contains("\"amount_sat\":520"), body)
    }

    @Test
    fun refreshAllPostsAndDecodesPendingRound() = runTest {
        val engine = fixtureEngine()
        val round = api(engine).refreshAll().okValue()
        assertEquals(3, round.id)
        assertEquals("pending", round.status.status)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/refresh/all", engine.singleRequestLine())
    }

    @Test
    fun createWalletPostsNetworkAndDecodesFingerprint() = runTest {
        val engine = fixtureEngine()
        val request = CreateWalletRequest(network = "signet", mnemonic = "tide margin ocean")
        val response = api(engine).createWallet(request).okValue()
        assertEquals("f00dbabe", response.fingerprint)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/create", engine.singleRequestLine())
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"network\":\"signet\""), body)
    }

    @Test
    fun mnemonicDecodesPhrase() = runTest {
        val engine = fixtureEngine()
        val response = api(engine).mnemonic().okValue()
        assertTrue(response.mnemonic.startsWith("tide margin"))
        assertEquals(HttpMethod.Get to "/api/v1/wallet/mnemonic", engine.singleRequestLine())
    }

    @Test
    fun waitNotificationsPassesSinceQueryParameter() = runTest {
        val engine = fixtureEngine()
        api(engine).waitNotifications(since = "2026-07-28T00:00:00Z")
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v1/notifications/wait", request.url.encodedPath)
        assertEquals("2026-07-28T00:00:00Z", request.url.parameters["since"])
    }

    @Test
    fun waitNotificationsOmitsSinceWhenNull() = runTest {
        val engine = fixtureEngine()
        api(engine).waitNotifications()
        assertNull(engine.requestHistory.single().url.parameters["since"])
    }

    @Test
    fun waitNotificationsDecodesAllThreeUnionVariants() = runTest {
        val engine = fixtureEngine()
        val response = api(engine).waitNotifications().okValue()
        assertEquals(3, response.notifications.size)
        val created = assertIs<WalletNotification.MovementCreated>(response.notifications[0])
        assertEquals(7, created.movement.id)
        val updated = assertIs<WalletNotification.MovementUpdated>(response.notifications[1])
        assertEquals("successful", updated.movement.status)
        assertIs<WalletNotification.ChannelLagging>(response.notifications[2])
        assertEquals("2026-07-28T10:00:06Z", response.lastPushedAt)
    }

    @Test
    fun connectedDecodesFlag() = runTest {
        val engine = fixtureEngine()
        assertTrue(api(engine).connected().okValue().connected)
        assertEquals(HttpMethod.Get to "/api/v1/wallet/connected", engine.singleRequestLine())
    }

    @Test
    fun tipDecodesHeight() = runTest {
        val engine = fixtureEngine()
        assertEquals(916_214L, api(engine).tip().okValue().tipHeight)
        assertEquals(HttpMethod.Get to "/api/v1/bitcoin/tip", engine.singleRequestLine())
    }

    @Test
    fun arkInfoDecodesNetworkAndDeltas() = runTest {
        val engine = fixtureEngine()
        val info = api(engine).arkInfo().okValue()
        assertEquals("signet", info.network)
        assertEquals("30s", info.roundInterval)
        assertEquals(12_960, info.vtxoExpiryDelta)
        assertEquals(12, info.vtxoExitDelta)
        assertEquals(HttpMethod.Get to "/api/v1/wallet/ark-info", engine.singleRequestLine())
    }

    @Test
    fun everyEndpointDecodesItsFixture() = runTest {
        val results = callAllEndpoints(api(fixtureEngine()))
        results.forEachIndexed { index, result ->
            assertIs<BarkdResult.Ok<*>>(result, "endpoint #$index was not Ok: $result")
        }
    }

    // --- Variant route table (FORK_BETA6) ---

    @Test
    fun historyRoutesThroughWalletHistoryOnFork() = runTest {
        val engine = forkFixtureEngine()
        val movements = forkApi(engine).history().okValue()
        assertEquals(7, movements.single().id)
        assertEquals(HttpMethod.Get to "/api/v1/wallet/history", engine.singleRequestLine())
    }

    @Test
    fun forkCreateWalletPostsArkServerAndEsploraChainSource() = runTest {
        val engine = forkFixtureEngine()
        val request = ForkCreateWalletRequest(
            network = "mutinynet",
            arkServer = "https://captaind.test",
            chainSource = ChainSourceConfig(esplora = EsploraChainSource(url = "https://esplora.test")),
        )
        val response = forkApi(engine).createWallet(request).okValue()
        assertEquals("f00dbabe", response.fingerprint)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/create", engine.singleRequestLine())
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"ark_server\":\"https://captaind.test\""), body)
        assertTrue(body.contains("\"chain_source\":{\"esplora\":{\"url\":\"https://esplora.test\"}}"), body)
        assertTrue(!body.contains("bitcoind"), body)
    }

    @Test
    fun stockCreateWalletBodyKeepsStockShape() = runTest {
        val engine = fixtureEngine()
        api(engine).createWallet(CreateWalletRequest(network = "signet")).okValue()
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"network\":\"signet\""), body)
        assertTrue(!body.contains("chain_source"), body)
        assertTrue(!body.contains("ark_server"), body)
    }

    @Test
    fun channelsDecodesLiveObservedShapeWithoutExpiryHeight() = runTest {
        val engine = forkFixtureEngine()
        val channel = forkApi(engine).channels().okValue().single()
        assertEquals("741e8bd3", channel.channelId)
        assertEquals("024fb4d3", channel.counterparty)
        assertEquals(1_000_000L, channel.capacitySat)
        assertEquals(500_000_000L, channel.localBalanceMsat)
        assertTrue(!channel.isUsable)
        assertTrue(!channel.isChannelReady)
        assertNull(channel.expiryHeight)
        assertEquals(144, channel.forceCloseSpendDelay)
        assertEquals(HttpMethod.Get to "/api/v1/lightning/channels", engine.singleRequestLine())
    }

    @Test
    fun channelsBalanceDecodesBalanceSat() = runTest {
        val engine = forkFixtureEngine()
        assertEquals(500_000L, forkApi(engine).channelsBalance().okValue().balanceSat)
        assertEquals(HttpMethod.Get to "/api/v1/lightning/channels/balance", engine.singleRequestLine())
    }

    @Test
    fun nextAddressPostsAndDecodesAddress() = runTest {
        val engine = forkFixtureEngine()
        assertEquals("ark1qf2knext", forkApi(engine).nextAddress().okValue().address)
        assertEquals(HttpMethod.Post to "/api/v1/wallet/addresses/next", engine.singleRequestLine())
    }

    @Test
    fun forkBalanceWithExplicitNullPendingExitDecodes() = runTest {
        val balance = forkApi(forkFixtureEngine()).balance().okValue()
        assertEquals(412_350L, balance.spendableSat)
        assertNull(balance.pendingExitSat)
    }

    @Test
    fun forkArkInfoWithSupportsChannelsAndNoFeesDecodes() = runTest {
        val info = forkApi(forkFixtureEngine()).arkInfo().okValue()
        assertEquals("mutinynet", info.network)
        assertEquals(12_960, info.vtxoExpiryDelta)
        assertEquals(true, info.supportsChannels)
    }

    @Test
    fun stockArkInfoDecodesWithoutSupportsChannels() = runTest {
        assertNull(api(fixtureEngine()).arkInfo().okValue().supportsChannels)
    }

    // --- Auth decoration ---

    @Test
    fun authDecoratorIsAppliedToEveryRequest() = runTest {
        val engine = fixtureEngine()
        val marker = AuthDecorator { it.headers.append("X-Test-Auth", "marker-1") }
        callAllEndpoints(api(engine, marker))
        assertEquals(14, engine.requestHistory.size)
        engine.requestHistory.forEach { request ->
            assertEquals(
                "marker-1",
                request.headers["X-Test-Auth"],
                "auth decorator skipped on ${request.url.encodedPath}",
            )
        }
    }

    // --- Error mapping ---

    @Test
    fun unauthorizedMapsToHttpErrorWithAuthRequired() = runTest {
        val engine = jsonEngine("""{"error": "auth required"}""", HttpStatusCode.Unauthorized)
        val error = assertIs<BarkdResult.HttpError>(api(engine).balance())
        assertEquals(401, error.status)
        assertTrue(error.isAuthRequired)
        assertTrue(error.body.contains("auth required"))
    }

    @Test
    fun forbiddenMapsToHttpErrorWithAuthRequired() = runTest {
        val engine = jsonEngine("""{"error": "bad token"}""", HttpStatusCode.Forbidden)
        val error = assertIs<BarkdResult.HttpError>(api(engine).mnemonic())
        assertEquals(403, error.status)
        assertTrue(error.isAuthRequired)
    }

    @Test
    fun serverErrorMapsToHttpErrorWithBody() = runTest {
        val engine = jsonEngine("""{"error": "round failed"}""", HttpStatusCode.InternalServerError)
        val error = assertIs<BarkdResult.HttpError>(api(engine).refreshAll())
        assertEquals(500, error.status)
        assertTrue(error.body.contains("round failed"))
        assertTrue(!error.isAuthRequired)
    }

    @Test
    fun connectFailureMapsToUnreachable() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }
        val result = api(engine).balance()
        val unreachable = assertIs<BarkdResult.Unreachable>(result)
        assertTrue(unreachable.message.contains("connection refused"), unreachable.message)
    }

    @Test
    fun malformedJsonMapsToContractErrorNotCrash() = runTest {
        val engine = jsonEngine("""{"spendable_sat": """)
        val error = assertIs<BarkdResult.HttpError>(api(engine).balance())
        assertEquals(200, error.status)
        assertTrue(error.body.contains("contract error"), error.body)
    }

    @Test
    fun missingRequiredFieldMapsToContractErrorNotCrash() = runTest {
        val engine = jsonEngine("""{"spendable_sat": 1}""")
        val error = assertIs<BarkdResult.HttpError>(api(engine).balance())
        assertEquals(200, error.status)
        assertTrue(error.body.contains("contract error"), error.body)
    }

    @Test
    fun unknownJsonFieldsAreIgnored() = runTest {
        val withExtras = BarkdFixtures.BALANCE.replaceFirst(
            "{",
            """{"future_field": {"nested": true}, "another": [1, 2],""",
        )
        val balance = api(jsonEngine(withExtras)).balance().okValue()
        assertEquals(412_350L, balance.spendableSat)
    }
}
