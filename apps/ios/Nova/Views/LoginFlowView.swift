import AuthenticationServices
import Shared
import SwiftUI

/// Anmeldung: Willkommen → Apple oder E-Mail → Code → ggf. Zusatzbestätigung per SMS.
struct LoginFlowView: View {
    enum Step { case welcome, signIn, email, emailCode, stepUp, blocked }

    @EnvironmentObject var model: AppModel
    @State private var step: Step = .welcome
    @State private var email = ""
    @State private var marketing = false
    @State private var challengeId = ""
    @State private var target = ""
    @State private var code = ""
    @State private var message: String?
    @State private var busy = false
    @State private var appleEnabled = false
    @State private var centralEnabled = false

    var body: some View {
        content
            .task {
                if let providers = try? await model.nova.api.providers() {
                    appleEnabled = providers.apple
                    centralEnabled = providers.central
                }
            }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome:
            let s = model.step("onboarding.welcome")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body)) {
                Image(systemName: "sparkles").font(.system(size: 88)).foregroundStyle(Color.brand)
                    .frame(maxWidth: .infinity).padding(.top, 32)
            } actions: {
                PrimaryButton(title: s?.primaryCta ?? model.t("common.continue")) { step = .signIn }
                if let secondary = s?.secondaryCta { Button(secondary) { step = .signIn } }
            }

        case .signIn:
            let s = model.step("onboarding.signIn")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body)) {
                EmptyView()
            } actions: {
                if centralEnabled {
                    PrimaryButton(title: model.t("auth.continueWithChattia"), loading: busy) { Task { await loginWithChattia() } }
                    Text(model.t("auth.chattiaHint")).font(.footnote).foregroundStyle(.secondary)
                }
                if appleEnabled {
                    SignInWithAppleButton(.continue) { request in
                        request.requestedScopes = [.fullName, .email]
                    } onCompletion: { result in
                        Task { await handleApple(result) }
                    }
                    .signInWithAppleButtonStyle(.black)
                    .frame(height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                PrimaryButton(title: model.t("auth.continueWithEmail")) { message = nil; step = .email }
                ErrorText(text: message)
                Text(model.t("auth.legalNotice")).font(.footnote).foregroundStyle(.secondary)
            }

        case .email:
            StepView(title: model.t("email.title"), message: model.t("email.body")) {
                TextField(model.t("auth.emailLabel"), text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                Toggle(model.t("auth.marketingConsent"), isOn: $marketing).font(.subheadline)
                ErrorText(text: message)
            } actions: {
                PrimaryButton(title: model.t("common.continue"), loading: busy, enabled: email.contains("@")) { Task { await sendEmailCode() } }
                Button(model.t("common.back")) { step = .signIn }
            }

        case .emailCode:
            let s = model.step("onboarding.verifyEmail")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body, ["email": target])) {
                CodeField(label: model.t("code.label"), code: $code)
                ErrorText(text: message)
                Text(model.t("code.noEmail")).font(.footnote).foregroundStyle(.secondary)
            } actions: {
                PrimaryButton(title: s?.primaryCta ?? model.t("common.continue"), loading: busy, enabled: code.count == 6) { Task { await verifyEmail() } }
                if let resend = s?.secondaryCta { Button(resend) { Task { await sendEmailCode() } } }
                if let other = s?.tertiaryCta { Button(other) { step = .email } }
            }
            .onChange(of: code) { _, new in if new.count == 6 && !busy { Task { await verifyEmail() } } }

        case .stepUp:
            StepView(title: model.t("security.stepUp.title"), message: model.t("security.stepUp.body") + (target.isEmpty ? "" : "\n\n" + model.fill(model.step("onboarding.phoneVerify")?.body, ["phoneMasked": target]))) {
                CodeField(label: model.t("code.label"), code: $code)
                ErrorText(text: message)
            } actions: {
                PrimaryButton(title: model.t("common.continue"), loading: busy, enabled: code.count == 6) { Task { await verifyStepUp() } }
                Button(model.t("common.back")) { step = .email }
            }
            .onChange(of: code) { _, new in if new.count == 6 && !busy { Task { await verifyStepUp() } } }

        case .blocked:
            StepView(title: model.t("security.blocked.title"), message: message ?? model.t("security.blocked.body")) {
                SecurityHint(text: model.t("security.neverShareBanner"))
            } actions: {
                PrimaryButton(title: model.t("common.back")) { message = nil; step = .signIn }
            }
        }
    }

    // MARK: - Aktionen

    private func sendEmailCode() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let challenge = try await model.nova.auth.startEmail(email: email.trimmingCharacters(in: .whitespaces), marketingConsent: marketing)
            challengeId = challenge.challengeId
            target = challenge.target ?? email
            code = ""
            step = .emailCode
        } catch {
            message = (error as NSError).kotlinMessage ?? model.t("error.network")
        }
    }

    private func verifyEmail() async {
        busy = true
        defer { busy = false }
        handle(try? await model.nova.auth.verifyEmail(challengeId: challengeId, code: code))
    }

    private func verifyStepUp() async {
        busy = true
        defer { busy = false }
        handle(try? await model.nova.auth.verifyStepUp(challengeId: challengeId, code: code))
    }

    private func loginWithChattia() async {
        busy = true
        message = nil
        defer { busy = false }
        guard let result = await model.loginWithChattia() else { return } // abgebrochen
        if result is LoginResult.Success {
            model.setupPending = false // Name und Gruppen kommen aus dem Chattia-Konto
        } else {
            handle(result)
        }
    }

    private func handleApple(_ result: Result<ASAuthorization, Error>) async {
        guard case .success(let authorization) = result,
              let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken,
              let token = String(data: tokenData, encoding: .utf8) else {
            message = model.t("error.generic")
            return
        }
        busy = true
        defer { busy = false }
        handle(try? await model.nova.auth.loginWithApple(idToken: token, firstName: credential.fullName?.givenName))
    }

    private func handle(_ result: LoginResult?) {
        switch result {
        case let success as LoginResult.Success:
            model.setupPending = success.isNewUser
        case let stepUp as LoginResult.StepUpRequired:
            challengeId = stepUp.challenge.challengeId
            target = stepUp.challenge.target ?? ""
            code = ""
            message = nil
            step = .stepUp
        case let blocked as LoginResult.Blocked:
            message = blocked.message
            step = .blocked
        case let failed as LoginResult.Failed:
            message = failed.message
        default:
            message = model.t("error.network")
        }
    }
}

extension NSError {
    /// Fehlermeldung einer Kotlin-Exception (NovaApiException.message), falls vorhanden.
    var kotlinMessage: String? {
        (userInfo["KotlinException"] as? KotlinThrowable)?.message
    }
}
