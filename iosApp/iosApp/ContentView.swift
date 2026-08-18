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
    /// LarkColors.Background (#0B0C0E) — behind the status bar and home indicator, so the
    /// app reads as one dark surface instead of Compose's rectangle floating in white bands.
    private static let background = Color(red: 0x0B / 255, green: 0x0C / 255, blue: 0x0E / 255)

    var body: some View {
        // Compose owns its insets: the Kotlin root pads content by WindowInsets.systemBars,
        // which is only non-zero once the host view actually covers the safe areas. Letting
        // SwiftUI inset the view instead stacked its band on top of each screen's own top
        // padding and pushed every first row a status bar too low.
        ComposeView()
            .ignoresSafeArea()
            .background(Self.background.ignoresSafeArea())
    }
}
