package xyz.lark.app

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.CoreMode
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.gateway.BarkdApiVariant
import xyz.lark.app.core.gateway.BarkdFixtures
import xyz.lark.app.core.gateway.BarkdScript
import xyz.lark.app.core.gateway.GATEWAY_BASE_URL
import xyz.lark.app.core.gateway.Paths
import xyz.lark.app.core.gateway.barkdEngine
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Composition-root selection (plan U6, R10/R12): [buildCore] with CoreConfig-shaped inputs
 * wires either the demo fake (with demo controls) or the MockEngine-backed gateway core
 * (without them), and the machine's model reflects the active core.
 */
class GatewayWiringTest {

    /** The factory exactly as the GATEWAY branch of AppGraph calls it, on a scripted engine. */
    private fun TestScope.gatewaySelection(script: BarkdScript): CoreSelection = buildCore(
        mode = CoreMode.GATEWAY,
        scope = backgroundScope,
        gatewayBaseUrl = GATEWAY_BASE_URL,
        expectedNetwork = "mutinynet",
        // Pinned, not inherited from CoreConfig: these fixtures describe the stock 0.4 surface, so
        // a build pointed at the fork would otherwise fail here for reasons that have nothing to
        // do with the wiring under test.
        apiVariant = BarkdApiVariant.STOCK_0_4,
        engine = { barkdEngine(script) },
    )

    private fun TestScope.machine(selection: CoreSelection): AppStateMachine =
        AppStateMachine(core = selection.core, demo = selection.demo, scope = backgroundScope)

    @Test
    fun gatewayModeFeedsTheModelFromTheGatewayWithoutTheDemoRail() = runTest {
        val script = BarkdScript()
        // A balance distinct from the demo constant proves the model is gateway-fed.
        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.BALANCE.replace("412350", "777000")))
        val selection = gatewaySelection(script)
        assertNull(selection.demo, "gateway mode must not expose demo controls (R10)")
        val m = machine(selection)
        runCurrent() // first poll cycle settles and re-renders the model

        val model = m.model.value
        assertNull(model.demoHealth, "the Advanced DEMO rail must vanish in gateway mode")
        assertEquals(777_000L, selection.core.balanceSats.value)
        assertEquals("₿777,000", model.balance.primary)
        assertEquals("mutinynet", model.networkLabel, "settings footer reads the active core (R12)")
    }

    @Test
    fun demoModeWiresTheFakeAsBothCoreAndDemoControls() = runTest {
        val selection = buildCore(mode = CoreMode.DEMO, scope = backgroundScope)
        assertTrue(selection.core is FakeLarkCore, "DEMO composes the in-process fake (AE4)")
        assertSame(selection.core, selection.demo, "the fake is its own demo seam, as today")

        val model = machine(selection).model.value
        assertNotNull(model.demoHealth, "the Advanced DEMO rail stays in demo mode")
        assertEquals("₿412,350", model.balance.primary, "the demo balance, unchanged")
    }

    @Test
    fun gatewayMnemonicFlowsIntoTheBackupModel() = runTest {
        val selection = gatewaySelection(BarkdScript())
        val m = machine(selection)
        runCurrent() // wallet adopted; rendering touches backupWords, starting the fetch
        runCurrent() // the mnemonic response lands
        m.push(Route.BACKUP) // re-render after the fetch settled

        assertEquals(12, m.model.value.backup.words.size)
        assertEquals("tide", m.model.value.backup.words.first())
    }

    @Test
    fun gatewayWithoutMnemonicExposureYieldsEmptyBackupWords() = runTest {
        val script = BarkdScript()
        // 404 = --expose-mnemonic off (the hosted default): words-unavailable, never fake words (R5).
        script.sticky(
            Paths.MNEMONIC,
            BarkdScript.Json("""{"error": "mnemonic not exposed"}""", HttpStatusCode.NotFound),
        )
        val selection = gatewaySelection(script)
        val m = machine(selection)
        runCurrent() // wallet adopted; rendering touches backupWords, starting the fetch
        runCurrent() // the 404 lands
        m.push(Route.BACKUP) // re-render after the fetch settled

        assertTrue(
            m.model.value.backup.words.isEmpty(),
            "words-unavailable must reach the model as empty words, never fake ones (R5)",
        )
    }
}
