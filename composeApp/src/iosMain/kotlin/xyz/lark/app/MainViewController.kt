package xyz.lark.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import xyz.lark.app.core.CoreConfig
import xyz.lark.app.core.CoreMode
import xyz.lark.app.core.ffi.DelegateBackedLarkCore
import xyz.lark.app.core.ffi.FfiCoreProvider
import xyz.lark.app.core.ffi.FfiWalletConfig
import xyz.lark.app.core.ffi.LarkCoreDelegate
import xyz.lark.app.core.ffi.LarkSecureStore

/**
 * The app's root view controller.
 *
 * Takes the two things only Swift can provide — the Rust-backed [delegate] and the Keychain-backed
 * [store] — and registers the in-process core with [FfiCoreProvider] *before* composition starts,
 * because the first composition builds the object graph and picks the resting route.
 *
 * Both are ignored unless [CoreConfig.mode] is [CoreMode.FFI], so a DEMO or GATEWAY build runs
 * unchanged on iOS with the delegate simply unused.
 */
@Suppress("FunctionNaming") // UIKit factory convention
fun MainViewController(
    delegate: LarkCoreDelegate,
    store: LarkSecureStore,
): UIViewController {
    if (CoreConfig.mode == CoreMode.FFI) {
        // Both are load-bearing for an on-device wallet and neither has a usable default: without a
        // chain source the crate cannot even confirm which network it is on, and without captaind
        // there is nobody to cosign. Failing here beats a wallet that opens and then cannot pay.
        require(CoreConfig.chainSource.isNotBlank()) {
            "CoreMode.FFI needs CoreConfig.chainSource (an esplora URL): bdk confirms the network through it"
        }
        require(CoreConfig.arkServerUrl.isNotBlank()) {
            "CoreMode.FFI needs CoreConfig.arkServerUrl (captaind): sends and receives require it"
        }
        FfiCoreProvider.factory = { scope ->
            DelegateBackedLarkCore(
                delegate = delegate,
                store = store,
                scope = scope,
                config = FfiWalletConfig(
                    datadir = store.datadir,
                    network = CoreConfig.expectedNetwork,
                    arkServer = CoreConfig.arkServerUrl,
                    esplora = CoreConfig.chainSource,
                ),
                networkLabel = CoreConfig.networkLabel,
            )
        }
    }
    return ComposeUIViewController { App() }
}
