import Shared
import SwiftUI

@main
struct NovaIOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(Color.brand)
        }
    }
}

extension Color {
    static let brand = Color(red: 0, green: 0x71 / 255, blue: 0xE3 / 255)
}

/// Brücke zwischen dem gemeinsamen Kotlin-Modul und SwiftUI: beobachtet die StateFlows und veröffentlicht sie.
@MainActor
final class AppModel: ObservableObject {
    let nova: Shared.NovaApp
    @Published private(set) var session: SessionState = SessionState.Unknown.shared
    @Published private(set) var chat: ChatState
    @Published private(set) var conversations: [ConversationDto] = []
    @Published private(set) var bundle: AppContentBundle
    @Published var setupPending = false
    private var observations: [Observation] = []

    init() {
        let url = Bundle.main.object(forInfoDictionaryKey: "NovaBackendURL") as? String ?? "http://localhost:8080"
        let keychain = KeychainStore()
        nova = Shared.NovaApp(baseUrl: url, device: SecureEnclaveIdentity(keychain: keychain), store: keychain)
        chat = nova.chat.state.value as! ChatState
        bundle = nova.content.bundle.value as! AppContentBundle
        observations = [
            NovaAppKt.observe(nova.auth.state) { [weak self] in self?.session = $0 as! SessionState },
            NovaAppKt.observe(nova.chat.state) { [weak self] in self?.chat = $0 as! ChatState },
            NovaAppKt.observe(nova.chat.conversations) { [weak self] in self?.conversations = ($0 as? [ConversationDto]) ?? [] },
            NovaAppKt.observe(nova.content.bundle) { [weak self] in self?.bundle = $0 as! AppContentBundle },
        ]
        nova.start()
    }

    deinit { observations.forEach { $0.cancel() } }

    /// Text aus dem CMS mit Platzhaltern.
    func t(_ key: String, _ values: [String: String] = [:]) -> String {
        ContentRepository.companion.format(bundle: bundle, key: key, values: values)
    }

    func step(_ key: String) -> OnboardingStep? {
        bundle.onboarding.first { $0.key == key }
    }

    func fill(_ text: String?, _ values: [String: String] = [:]) -> String {
        guard let text else { return "" }
        var result = text.replacingOccurrences(of: "{appName}", with: bundle.appName)
        for (key, value) in values { result = result.replacingOccurrences(of: "{\(key)}", with: value) }
        return result
    }
}
