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
    BOARDING("First deposit settling"),
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
}
