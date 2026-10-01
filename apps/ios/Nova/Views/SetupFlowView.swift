import Shared
import SwiftUI

/// Nach der ersten Anmeldung: Telefon (optional), Personalisieren (optional), KI-Hinweis.
struct SetupFlowView: View {
    let firstName: String?
    @EnvironmentObject var model: AppModel
    @State private var step = 0
    @State private var phone = ""
    @State private var challengeId = ""
    @State private var target = ""
    @State private var code = ""
    @State private var name = ""
    @State private var useCase: String?
    @State private var message: String?
    @State private var busy = false

    private let useCases = ["work", "study", "writing", "everyday", "coding"]

    var body: some View {
        switch step {
        case 0:
            let s = model.step("onboarding.phone")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body)) {
                TextField(model.t("phone.example"), text: $phone)
                    .textContentType(.telephoneNumber)
                    .keyboardType(.phonePad)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                ErrorText(text: message)
            } actions: {
                PrimaryButton(title: s?.primaryCta ?? "SMS", loading: busy, enabled: phone.count >= 8) { Task { await startPhone("sms") } }
                SecondaryButton(title: s?.secondaryCta ?? "Anruf", enabled: phone.count >= 8 && !busy) { Task { await startPhone("call") } }
                Button(s?.tertiaryCta ?? model.t("common.later")) { step = 2 }
            }
            .onAppear { name = firstName ?? "" }

        case 1:
            let s = model.step("onboarding.phoneVerify")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body, ["phoneMasked": target])) {
                CodeField(label: model.t("code.label"), code: $code)
                ErrorText(text: message)
            } actions: {
                PrimaryButton(title: s?.primaryCta ?? model.t("common.continue"), loading: busy, enabled: code.count == 6) { Task { await verifyPhone() } }
                if let resend = s?.secondaryCta { Button(resend) { Task { await startPhone("sms") } } }
                if let call = s?.tertiaryCta { Button(call) { Task { await startPhone("call") } } }
            }
            .onChange(of: code) { _, new in if new.count == 6 && !busy { Task { await verifyPhone() } } }

        case 2:
            let s = model.step("onboarding.personalize")
            StepView(title: model.fill(s?.title), message: model.fill(s?.body)) {
                TextField(model.t("onboarding.nameLabel"), text: $name)
                    .textContentType(.givenName)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                ForEach(useCases, id: \.self) { option in
                    Button {
                        useCase = useCase == option ? nil : option
                    } label: {
                        HStack {
                            Text(model.t("onboarding.useCase.\(option)"))
                            Spacer()
                            if useCase == option { Image(systemName: "checkmark") }
                        }
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(useCase == option ? Color.brand.opacity(0.15) : Color(.secondarySystemGroupedBackground)))
                    }
                    .buttonStyle(.plain)
                }
            } actions: {
                PrimaryButton(title: s?.primaryCta ?? model.t("common.continue"), loading: busy) { Task { await savePersonalization() } }
                Button(s?.secondaryCta ?? model.t("common.later")) { step = 3 }
            }

        default:
            StepView(title: model.bundle.appName, message: model.t("ai.disclaimer")) {
                SecurityHint(text: model.t("security.neverShareBanner"))
            } actions: {
                PrimaryButton(title: model.t("common.continue")) { model.setupPending = false }
            }
        }
    }

    private func startPhone(_ channel: String) async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let challenge = try await model.nova.api.startPhone(phone: phone, channel: channel)
            challengeId = challenge.challengeId
            target = challenge.target ?? ""
            code = ""
            step = 1
        } catch {
            message = (error as NSError).kotlinMessage ?? model.t("error.network")
        }
    }

    private func verifyPhone() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let user = try await model.nova.api.verifyPhone(challengeId: challengeId, code: code)
            model.nova.auth.updateUser(user: user)
            step = 2
        } catch {
            message = (error as NSError).kotlinMessage ?? model.t("error.network")
        }
    }

    private func savePersonalization() async {
        busy = true
        defer { busy = false }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let user = try? await model.nova.api.updateMe(request: UpdateMeRequest(firstName: trimmed.isEmpty ? nil : trimmed, useCase: useCase, marketingConsent: nil)) {
            model.nova.auth.updateUser(user: user)
        }
        step = 3
    }
}
