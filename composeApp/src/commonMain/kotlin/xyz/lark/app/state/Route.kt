package xyz.lark.app.state

/**
 * The 22 prototype routes (design source: `docs/design/lark-wallet/LARK Wallet.dc.html`).
 * Routes carry no arguments — screen-specific selection (e.g. which transaction) lives in
 * [AppStateMachine]'s state, exactly like the prototype's Component class.
 *
 * [screenLabel] is the prototype's human label for the route (its `labels` map).
 */
enum class Route(val screenLabel: String) {
    WELCOME("Onboarding 1 — welcome"),
    HOW_IT_WORKS("Onboarding 2 — three promises"),
    FUND("Onboarding 3 — add money"),
    /**
     * The on-chain deposit address, shown so a tester can fund their own wallet.
     *
     * Not in the design spec, which has no on-chain deposit screen: with keys on device the money
     * has to arrive somewhere the user can see, and leaving the address in Advanced only would make
     * self-funding a scavenger hunt.
     */
    DEPOSIT("Add money — on-chain deposit"),
    RESTORE("Restore"),
    HOME("Home"),
    ACTIVITY("Activity"),
    TX_DETAIL("Payment detail"),
    TX_TECH("Technical details"),
    SEND_INPUT("Pay — recipient"),
    SCAN("Scan"),
    AMOUNT("Amount keypad"),
    REVIEW("Pay — review"),
    SENDING("Working"),
    SENT("Sent"),
    /** Accepted but not yet settled: the honest landing for [xyz.lark.app.core.model.SendResult.Pending]. */
    PENDING("On its way"),
    FAILED("Failed"),
    RECEIVE("Get paid"),
    SETTINGS("Settings"),
    BACKUP("Backup"),
    HEALTH("Wallet status"),
    ADVANCED("Advanced"),
    EXIT("Move on-chain"),
    ;

    /**
     * Whether this route is part of getting set up.
     *
     * Needed because "a wallet exists" stopped meaning "onboarding is done": the on-device core
     * has to create the wallet before the funding step can show a deposit address, so back-ing out
     * of onboarding must land on welcome rather than on the resting route it would otherwise
     * compute (see [AppStateMachine.back]).
     */
    val isOnboarding: Boolean
        get() = this in ONBOARDING_ROUTES
}

private val ONBOARDING_ROUTES = setOf(
    Route.WELCOME,
    Route.HOW_IT_WORKS,
    Route.FUND,
    Route.DEPOSIT,
    Route.RESTORE,
)
