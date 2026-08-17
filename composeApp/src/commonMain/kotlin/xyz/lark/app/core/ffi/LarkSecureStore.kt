package xyz.lark.app.core.ffi

/**
 * Where the device keeps the things the Rust core does not persist for us: the mnemonic, the fact
 * that the user has written it down (KTD-11), and the user's standing request for money to arrive.
 *
 * Implemented by the platform, beside [LarkCoreDelegate]. Every member is synchronous — reading the
 * Keychain is a local call, and making it asynchronous would put a suspension point in the middle
 * of the seam's synchronous `createWallet`/`backupWords` for no gain.
 *
 * The words are the wallet. An implementation must store them somewhere that survives app
 * restarts but is not backed up off-device by the platform's own means, and must never log them.
 */
interface LarkSecureStore {

    /**
     * The directory holding the wallet database, created if absent. bark owns everything inside
     * it; the app only needs to know where it is and whether it has been used.
     */
    val datadir: String

    /**
     * Whether a wallet database already exists in [datadir].
     *
     * The one thing that has to be answerable *synchronously at launch*: the resting route is
     * decided before any async open can finish, so without this a returning user is shown the
     * welcome screen and then bounced to home.
     */
    fun walletFileExists(): Boolean

    /** The stored mnemonic, or null if this device has never created a wallet. */
    fun loadWords(): List<String>?

    /** Persist [words]. Returns false if secure storage refused, which must not be silent. */
    fun storeWords(words: List<String>): Boolean

    /** Whether the user has confirmed writing the words down. */
    fun isBackedUp(): Boolean

    /** Record that the user has confirmed writing the words down. */
    fun markBackedUp()

    /**
     * When the user last asked for money to arrive, as epoch millis, or null if never.
     *
     * A third device-local fact for the same reason as the backup flag: bark does not persist it,
     * and it has to survive the app being closed — the deposit it authorises can take hours, and
     * the user will not sit and watch.
     */
    fun loadFundingArmedAt(): Long?

    /** Persist the funding intent, or clear it when [millis] is null. */
    fun storeFundingArmedAt(millis: Long?)

    /**
     * When the current or last unilateral exit began and finished, or null if none has begun.
     *
     * A fourth device-local fact, for the funding intent's reason: bark records the exit's states,
     * but neither the moment the holder asked for one nor whether they were ever told it finished.
     * Without this the receipt is shown again on every launch, or — if it were session-only — lost
     * entirely when the app is killed between the last claim and the holder next opening it, which
     * on a multi-hour exit is the likely case rather than the unlucky one.
     */
    fun loadExitTimes(): ExitTimes?

    /** Persist the exit's timings, or clear them when [times] is null. */
    fun storeExitTimes(times: ExitTimes?)
}

/**
 * When an exit began, and when it finished if it has.
 *
 * One value rather than two stored facts because they describe one exit and only ever change
 * together — and because a start with no matching finish is meaningful (an exit in flight) while a
 * finish with no start is not. [completedAt] being non-null means "finished, and the holder has
 * not dismissed the receipt yet"; the whole value is cleared when they do.
 */
data class ExitTimes(
    val startedAt: Long,
    val completedAt: Long? = null,
)
