package xyz.lark.app.core

import xyz.lark.app.core.model.HealthState

/**
 * Demo-only controls, deliberately outside [LarkCore] (KTD-3): only [FakeLarkCore] implements
 * this, and only demo UI (the Advanced screen's DEMO section) may depend on it.
 */
interface DemoControls {

    /** Forces the wallet into [health] immediately, no delay. */
    fun forceHealth(health: HealthState)
}
