package xyz.lark.app.core

import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * What the repo is allowed to ship.
 *
 * [CoreConfig.mode] is a hand-edited constant, and [CoreMode.DEMO] is the one value that is
 * indistinguishable from a working wallet at runtime: [FakeLarkCore] reports a confident balance,
 * five invented transactions, and a receive code rendered exactly like a payable one. Every other
 * mode fails loudly when it cannot reach its engine — DEMO succeeds at lying, which in a wallet is
 * the worse failure.
 *
 * Flipping the constant locally is expected and fine (today it is the only way to run the Android
 * app at all, its FFI adapter being blocked). Committing it is not. This test is the line between
 * the two.
 */
class ShippedCoreConfigTest {

    @Test
    fun theShippedBuildNeverComposesTheDemoFake() {
        assertNotEquals(
            CoreMode.DEMO,
            CoreConfig.mode,
            "CoreConfig.mode is committed as DEMO, which would ship FakeLarkCore's invented balance " +
                "and receive code as if they were real funds. Set it back to FFI (or GATEWAY) before " +
                "merging and keep the DEMO edit local.",
        )
    }
}
