import AuthenticationServices
import Shared
import UIKit

/// Login mit dem Chattia-Konto im System-Browser (ASWebAuthenticationSession).
/// Der Browser teilt Cookies mit Safari: Wer schon in Cloud oder Website angemeldet ist, muss nichts eingeben.
@MainActor
final class CentralLogin: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    /// Öffnet den Login und liefert die Rücksprungadresse nova://auth?code=… (nil = abgebrochen).
    func start(url: String) async -> String? {
        guard let start = URL(string: url) else { return nil }
        return await withCheckedContinuation { continuation in
            let session = ASWebAuthenticationSession(url: start, callbackURLScheme: "nova") { callback, _ in
                continuation.resume(returning: callback?.absoluteString)
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            if !session.start() { continuation.resume(returning: nil) }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}

extension AppModel {
    /// Kompletter Ablauf: Browser → Einmalcode → Anmeldung mit Geräteschlüssel.
    func loginWithChattia() async -> LoginResult? {
        let url = nova.auth.centralLoginUrl()
        guard let callback = await centralLogin.start(url: url) else {
            return nil
        }
        return try? await nova.auth.completeCentralLogin(callbackUrl: callback)
    }
}
