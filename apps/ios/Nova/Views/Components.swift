import SwiftUI

/// Gerüst für Onboarding-Schritte: Titel, Text, Inhalt, Aktionen unten.
struct StepView<Content: View, Actions: View>: View {
    let title: String
    let message: String?
    @ViewBuilder var content: Content
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(title).font(.largeTitle.bold())
                    if let message, !message.isEmpty {
                        Text(message).font(.body).foregroundStyle(.secondary)
                    }
                    content
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 48)
            }
            VStack(spacing: 10) { actions }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
        }
        .background(Color(.systemGroupedBackground))
    }
}

struct PrimaryButton: View {
    let title: String
    var loading = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if loading { ProgressView().tint(.white) } else { Text(title).font(.headline) }
            }
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .disabled(!enabled || loading)
    }
}

struct SecondaryButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(.headline).frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .disabled(!enabled)
    }
}

/// 6-stelliger Code mit Autofill aus SMS/E-Mail und Warnhinweis „Nie weitergeben“.
struct CodeField: View {
    @EnvironmentObject var model: AppModel
    let label: String
    @Binding var code: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField(label, text: $code)
                .textContentType(.oneTimeCode)
                .keyboardType(.numberPad)
                .font(.system(size: 28, weight: .medium, design: .monospaced))
                .multilineTextAlignment(.center)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                .onChange(of: code) { _, new in code = String(new.filter(\.isNumber).prefix(6)) }
            SecurityHint(text: model.t("code.neverShare"))
        }
    }
}

struct SecurityHint: View {
    let text: String

    var body: some View {
        Label { Text(text).font(.subheadline) } icon: { Image(systemName: "checkmark.shield") }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.brand.opacity(0.12)))
    }
}

struct ErrorText: View {
    let text: String?
    var body: some View {
        if let text, !text.isEmpty { Text(text).font(.subheadline).foregroundStyle(.red) }
    }
}
