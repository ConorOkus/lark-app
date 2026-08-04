package xyz.lark.app.core.ffi

import kotlinx.coroutines.CoroutineScope
import xyz.lark.app.core.LarkCore

/**
 * How a platform hands its in-process core to the composition root.
 *
 * The FFI core cannot be built in shared code: it needs a [LarkCoreDelegate] and a
 * [LarkSecureStore] that only the platform can supply (on iOS, both are written in Swift). So the
 * platform entry point sets [factory] before composition and `buildCore` calls it for
 * [xyz.lark.app.core.CoreMode.FFI].
 *
 * A single assignment made once at startup, before any composition — not a general service locator.
 * Reading it when nothing has been set is a programming error and fails loudly rather than silently
 * falling back to another core, because "the demo wallet appeared instead of yours" is a far worse
 * outcome than a crash on launch.
 */
object FfiCoreProvider {

    /** Builds the core in [CoroutineScope]; set by the platform, null everywhere else. */
    var factory: ((CoroutineScope) -> LarkCore)? = null
}
