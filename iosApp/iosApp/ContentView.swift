import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // The two platform-owned pieces the in-process core needs, handed to Kotlin before
        // composition starts (see MainViewController). Ignored unless CoreConfig.mode is FFI.
        MainViewControllerKt.MainViewController(
            delegate: FfiLarkCoreDelegate(),
            store: KeychainSecureStore()
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}
