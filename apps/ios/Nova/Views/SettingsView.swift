import Shared
import SwiftUI

struct SettingsView: View {
    let user: UserDto
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var devices: [DeviceDto] = []
    @State private var events: [SecurityEventDto] = []
    @State private var phrase = ""
    @State private var message: String?
    @State private var confirmLogout = false

    private var current: UserDto { (model.session as? SessionState.LoggedIn)?.user ?? user }

    var body: some View {
        NavigationStack {
            Form {
                Section(model.t("settings.profile")) {
                    LabeledContent(model.t("settings.name"), value: current.firstName ?? "–")
                    LabeledContent(model.t("auth.emailLabel"), value: current.email)
                    LabeledContent(model.t("settings.workspace"), value: current.workspace.name)
                    LabeledContent(model.t("settings.role"), value: current.workspace.roleName)
                    Toggle(model.t("settings.marketing"), isOn: Binding(get: { current.marketingConsent }, set: { value in
                        Task {
                            if let u = try? await model.nova.api.updateMe(request: UpdateMeRequest(firstName: nil, useCase: nil, marketingConsent: KotlinBoolean(bool: value))) {
                                model.nova.auth.updateUser(user: u)
                            }
                        }
                    }))
                }

                Section {
                    Text(model.t("security.check.intro")).foregroundStyle(.secondary)
                    check(model.t("settings.phone"), current.phoneMasked ?? model.t("settings.phoneMissing"), done: current.phoneMasked != nil)
                    check(model.t("security.antiPhishing.title"), current.hasAntiPhishingPhrase ? model.t("settings.antiPhishingSet") : model.t("settings.antiPhishingMissing"), done: current.hasAntiPhishingPhrase)
                    Text(model.t("security.antiPhishing.body")).font(.footnote).foregroundStyle(.secondary)
                    TextField(model.t("security.antiPhishing.label"), text: $phrase)
                    Button(model.t("common.save")) { Task { await savePhrase() } }
                        .disabled(phrase.trimmingCharacters(in: .whitespaces).count < 3)
                    ErrorText(text: message)
                    SecurityHint(text: model.t("security.neverShareBanner"))
                } header: { Text(model.t("security.check.title")) }

                Section(model.t("security.devices.title")) {
                    ForEach(devices, id: \.id) { d in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(d.name + (d.current ? " · " + model.t("security.devices.thisDevice") : ""))
                                Text([d.location, model.t("devices.lastSeen", ["date": format(d.lastSeenAt)])].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if !d.current {
                                Button(model.t("security.devices.signOut")) { Task { try? await model.nova.api.revokeDevice(id: d.id); await reload() } }
                            }
                        }
                    }
                    if devices.count > 1 {
                        Button(model.t("security.devices.signOutOthers")) { Task { try? await model.nova.api.revokeOtherDevices(); await reload() } }
                    }
                }

                if !events.isEmpty {
                    Section(model.t("settings.recentActivity")) {
                        ForEach(Array(events.prefix(8).enumerated()), id: \.offset) { _, e in
                            VStack(alignment: .leading) {
                                let label = model.t("security.event.\(e.type)")
                                Text(label.hasPrefix("security.event.") ? e.type : label)
                                Text([format(e.at), e.device, e.location].compactMap { $0 }.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    Text(model.t("support.contactInfo")).font(.footnote).foregroundStyle(.secondary)
                    Button(model.t("settings.logout"), role: .destructive) { confirmLogout = true }
                }
            }
            .navigationTitle(model.t("settings.title"))
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(model.t("common.done")) { dismiss() } } }
            .task { await reload() }
            .confirmationDialog(model.t("settings.logoutConfirm"), isPresented: $confirmLogout, titleVisibility: .visible) {
                Button(model.t("settings.logout"), role: .destructive) {
                    Task { try? await model.nova.auth.logout(); dismiss() }
                }
            }
        }
    }

    private func check(_ title: String, _ value: String, done: Bool) -> some View {
        HStack {
            Image(systemName: done ? "checkmark.circle.fill" : "circle").foregroundStyle(done ? Color.brand : .secondary)
            VStack(alignment: .leading) {
                Text(title)
                Text(value).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func reload() async {
        if let d = try? await model.nova.api.devices() { devices = d }
        if let s = try? await model.nova.api.securityStatus() { events = s.recentEvents }
    }

    private func savePhrase() async {
        do {
            let u = try await model.nova.api.setAntiPhishingPhrase(phrase: phrase)
            model.nova.auth.updateUser(user: u)
            phrase = ""
            message = nil
            await reload()
        } catch {
            message = (error as NSError).kotlinMessage ?? model.t("error.network")
        }
    }

    private func format(_ iso: String) -> String {
        guard let date = ISO8601DateFormatter().date(from: iso) ?? { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f.date(from: iso) }() else { return iso }
        let f = DateFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.dateStyle = .long
        f.timeStyle = .short
        return f.string(from: date)
    }
}
