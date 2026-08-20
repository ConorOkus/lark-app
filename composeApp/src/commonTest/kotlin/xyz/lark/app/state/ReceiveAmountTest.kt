package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Get paid's requested amount reaching the core, and its code reaching the screen (plan U7).
 *
 * The receive keypad used to drop its digits on the way back — the amount annotated nothing and
 * the code never changed. Now it is the input to a code that can carry a Lightning invoice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveAmountTest {

    /** Records the amounts asked for and answers with an amount-specific code. */
    private class RecordingReceiveCore(
        private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    ) : LarkCore by fake {
        val asked = mutableListOf<Long>()

        override suspend fun requestReceiveCode(sats: Long): String {
            asked += sats
            return "${fake.receiveCode}&lightning=lntbs${sats}invoice"
        }
    }

    private fun TestScope.machineWith(core: LarkCore): AppStateMachine = AppStateMachine(
        core = core,
        demo = null,
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
    )

    private fun AppStateMachine.requestAmount(digits: String) {
        goReceiveAmount()
        digits.forEach { keyPress(it) }
        keypadConfirm()
    }

    // --- The amount reaches the core ---

    @Test
    fun confirmingAReceiveAmountAsksTheCoreForThatExactAmount() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        runCurrent()

        assertEquals(listOf(520L), core.asked)
    }

    @Test
    fun theRequestedAmountCodeIsWhatTheScreenRenders() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        runCurrent()

        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }

    @Test
    fun theRequestedAmountIsShownAlongsideTheCode() = runTest {
        val m = machineWith(RecordingReceiveCore())

        m.requestAmount("520")
        runCurrent()

        assertEquals("₿520", m.model.value.receive.requestedAmount)
    }

    /** Confirming returns to Get paid — the screen the "Set amount" affordance is reached from. */
    @Test
    fun confirmingAReceiveAmountLandsBackOnGetPaid() = runTest {
        val m = machineWith(RecordingReceiveCore())
        m.go(Route.RECEIVE)
        m.goReceiveAmount()
        assertEquals(Route.AMOUNT, m.model.value.route)

        m.type("520")
        m.keypadConfirm()
        assertEquals(Route.RECEIVE, m.model.value.route)
    }

    // --- The amountless code is unchanged (R9) ---

    @Test
    fun withNoAmountTheCoreIsNeverAskedAndTheAmountlessCodeStands() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        assertEquals(core.receiveCode, m.model.value.receive.code)
        assertNull(m.model.value.receive.requestedAmount)
        assertTrue(core.asked.isEmpty())
    }

    @Test
    fun clearingTheAmountReturnsToTheAmountlessCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.requestAmount("520")
        runCurrent()
        assertEquals("₿520", m.model.value.receive.requestedAmount)

        m.clearReceiveAmount()
        runCurrent()

        assertNull(m.model.value.receive.requestedAmount)
        assertEquals(core.receiveCode, m.model.value.receive.code)
    }

    /** The single affordance sets an amount when there is none and drops it when there is. */
    @Test
    fun theAmountAffordanceTogglesBetweenSettingAndClearing() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        m.toggleReceiveAmount()
        assertEquals(Route.AMOUNT, m.model.value.route, "with no amount set, it opens the keypad")
        m.type("520")
        m.keypadConfirm()
        runCurrent()
        assertEquals("₿520", m.model.value.receive.requestedAmount)

        m.toggleReceiveAmount()
        runCurrent()
        assertNull(m.model.value.receive.requestedAmount, "with one set, it clears it")
        assertEquals(Route.RECEIVE, m.model.value.route, "and stays on Get paid")
    }

    // --- Asynchrony discipline ---

    /** Until the code lands, the amountless code stands: never blank, never a stale attribution. */
    @Test
    fun theAmountlessCodeStandsUntilTheRequestedCodeLands() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        // Deliberately before runCurrent(): the mint has not answered yet.
        assertEquals(core.receiveCode, m.model.value.receive.code)
        assertEquals("₿520", m.model.value.receive.requestedAmount, "the ask is visible immediately")

        runCurrent()
        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }

    @Test
    fun aSecondAmountSupersedesTheFirstCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        m.requestAmount("1000")
        runCurrent()

        assertEquals("₿1,000", m.model.value.receive.requestedAmount)
        assertEquals("${core.receiveCode}&lightning=lntbs1000invoice", m.model.value.receive.code)
    }

    /** A core that declines to mint still yields a usable code, with no error state. */
    @Test
    fun aCoreThatOffersOnlyTheArkCodeStillRendersAScannableCode() = runTest {
        val fake = FakeLarkCore(startWithWallet = true)
        val m = machineWith(fake) // inherits the seam default: the amountless code
        m.requestAmount("520")
        runCurrent()

        assertEquals(fake.receiveCode, m.model.value.receive.code)
        assertEquals("₿520", m.model.value.receive.requestedAmount, "the ask is still stated")
    }

    /**
     * A core asked for a code before it has minted an address answers with an empty string. That
     * must not stick: once a later poll produces a usable code, Get paid has to show it, or the
     * QR and code box stay blank until the user toggles the amount.
     */
    @Test
    fun anEmptyAnswerDoesNotPinTheCodeBlankOnceTheCoreHasOne() = runTest {
        val core = LateMintingCore()
        val m = machineWith(core)

        m.requestAmount("520")
        runCurrent()
        assertNull(m.model.value.receive.code, "nothing minted yet, so there is no code")

        core.mint("bitcoin:?ark=ark1qf7late")
        m.go(Route.RECEIVE) // any re-render

        assertEquals("bitcoin:?ark=ark1qf7late", m.model.value.receive.code)
    }

    /**
     * The case that shipped a QR of the empty string: a wallet that opened without reaching the
     * Ark server never mints, so [LarkCore.receiveCode] stays "" for the whole session.
     *
     * That has to surface as no code at all. An empty string encodes to a valid, scannable QR, so
     * rendering it gave the user a code to hand out that no payment could ever reach.
     */
    @Test
    fun aCoreThatNeverMintsRendersNoCodeRatherThanAnEmptyOne() = runTest {
        val m = machineWith(LateMintingCore()) // never told to mint
        m.go(Route.RECEIVE)

        assertNull(m.model.value.receive.code)
    }

    // --- Asking again for a code that never came ---

    /**
     * The retry runs the poll cycle that mints, and leaves the user on Get paid.
     *
     * The screen-level point: `runRefresh` — the app's other retry — routes through SENDING and
     * lands on home, which would take Get paid away from someone standing on it waiting.
     */
    @Test
    fun retryingRefreshesTheCoreWithoutLeavingGetPaid() = runTest {
        val core = LateMintingCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        m.retryReceiveCode()
        runCurrent()

        assertEquals(1, core.refreshes, "the retry is a poll cycle, which is what mints")
        assertEquals(Route.RECEIVE, m.model.value.route)
    }

    /** A code that lands on the retry's own cycle reaches the screen. */
    @Test
    fun aCodeMintedByTheRetryShowsUp() = runTest {
        val core = LateMintingCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)
        assertNull(m.model.value.receive.code)

        core.mint("bitcoin:?ark=ark1qf7retry")
        m.retryReceiveCode()
        runCurrent()

        assertEquals("bitcoin:?ark=ark1qf7retry", m.model.value.receive.code)
        assertFalse(m.model.value.receive.retrying, "and the affordance is live again")
    }

    /** A second tap while one is in flight must not queue another cycle behind it. */
    @Test
    fun retryingIsQuietWhileOneIsAlreadyRunning() = runTest {
        val core = LateMintingCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        m.retryReceiveCode()
        assertTrue(m.model.value.receive.retrying)
        m.retryReceiveCode()
        runCurrent()

        assertEquals(1, core.refreshes)
    }

    /** A core whose receive code only appears after a later poll, as the gateway's does. */
    private class LateMintingCore(
        private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    ) : LarkCore by fake {
        private var minted: String = ""
        var refreshes = 0
            private set

        override val receiveCode: String get() = minted

        override suspend fun refresh() {
            refreshes++
        }

        fun mint(code: String) {
            minted = code
        }

        // Mirrors GatewayLarkCore: with no address yet there is nothing to build a code from.
        override suspend fun requestReceiveCode(sats: Long): String = minted
    }

    /** Copy must copy what is on screen — the composed code, not the bare address. */
    @Test
    fun copyingCopiesTheRenderedCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.requestAmount("520")
        runCurrent()

        m.copyCode()
        assertEquals("Copied", m.model.value.receive.copyLabel)
        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }

    // --- The funding route that needs nothing on-chain (U6) ---

    @Test
    fun theLightningFundingRouteOpensTheAmountKeypadInReceiveMode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.goFundOverLightning()
        runCurrent()

        // Straight to the amount, because a Lightning destination cannot exist until someone has
        // said how much — the ask is the first half of the gesture, not friction before it.
        assertEquals(Route.AMOUNT, m.model.value.route)
        assertTrue(core.asked.isEmpty(), "nothing is minted before an amount exists")
    }

    @Test
    fun confirmingTheAmountFromTheFundingRouteLandsOnGetPaidWithAPayableCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.goFundOverLightning()
        "5000".forEach { m.keyPress(it) }
        m.keypadConfirm()
        runCurrent()

        // Get paid, not back to the funding screen: the holder came here for a code, so landing
        // anywhere the code is not would strand the whole route.
        assertEquals(Route.RECEIVE, m.model.value.route)
        assertEquals(listOf(5_000L), core.asked)
        assertTrue(
            m.model.value.receive.code?.contains("lightning=") == true,
            "the code a first-run holder shows must carry a Lightning destination",
        )
    }

    @Test
    fun leavingTheFundingRouteWithoutAnAmountStillLandsSomewhereCoherent() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.goFundOverLightning()
        m.back()
        runCurrent()

        // Get paid with the amountless code, and no half-finished ask left behind.
        assertEquals(Route.RECEIVE, m.model.value.route)
        assertNull(m.model.value.receive.requestedAmount)
        assertTrue(core.asked.isEmpty())
    }

}

/** Types each digit of [digits] on the keypad. */
private fun AppStateMachine.type(digits: String) = digits.forEach { keyPress(it) }
