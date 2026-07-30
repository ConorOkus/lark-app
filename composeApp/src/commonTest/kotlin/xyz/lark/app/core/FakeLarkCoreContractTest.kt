package xyz.lark.app.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** [LarkCoreContractTest] against [FakeLarkCore]: no wallet at start, virtual-time delays. */
class FakeLarkCoreContractTest : LarkCoreContractTest() {

    override fun TestScope.fixture(): CoreFixture = object : CoreFixture {
        override val core = FakeLarkCore(startWithWallet = false)

        override fun settle() = runCurrent()

        /** The fake debits locally inside [FakeLarkCore.send]; nothing to acknowledge. */
        override fun acknowledgeDebit(newBalanceSats: Long) = Unit
    }
}
