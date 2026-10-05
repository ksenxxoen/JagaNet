import ComposeApp
import SwiftUI
import UIKit

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.keyboard)
        }
    }
}

/// Hosts the shared Compose UI (composeApp/src/iosMain/.../IosPlatform.kt).
struct ComposeView: UIViewControllerRepresentable {
    private static let bridge = NativeBridge()

    func makeUIViewController(context: Context) -> UIViewController {
        let api = (Bundle.main.object(forInfoDictionaryKey: "JagaNetApiUrl") as? String) ?? "http://localhost:4000"
        return IosPlatformKt.MainViewController(bridge: Self.bridge, apiUrl: api)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
