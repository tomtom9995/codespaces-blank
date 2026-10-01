import Shared
import SwiftUI

struct ChatView: View {
    let user: UserDto
    @EnvironmentObject var model: AppModel
    @State private var input = ""
    @State private var showHistory = false
    @State private var showSettings = false

    private var state: ChatState { model.chat }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if state.messages.isEmpty && !state.loading {
                    emptyState
                } else {
                    messageList
                }
                inputBar
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .sheet(isPresented: $showHistory) { historySheet }
            .sheet(isPresented: $showSettings) { SettingsView(user: user) }
            .alert(state.error ?? "", isPresented: errorShown) {
                Button("OK", role: .cancel) {}
            }
            .task { model.nova.chat.refreshConversations() }
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { showHistory = true } label: { Image(systemName: "line.3.horizontal") }
                .accessibilityLabel(model.t("chat.menu"))
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.nova.chat.startNewChat() } label: { Image(systemName: "square.and.pencil") }
                .accessibilityLabel(model.t("chat.newChat"))
        }
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { model.chat.error != nil }, set: { if !$0 { model.nova.chat.dismissError() } })
    }

    private var title: String {
        state.title.isEmpty ? model.bundle.appName : state.title
    }

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "sparkles").font(.system(size: 52)).foregroundStyle(Color.brand).padding(.top, 60)
                Text((user.firstName ?? "").isEmpty ? model.t("chat.welcomeNoName") : model.t("chat.welcome", ["firstName": user.firstName ?? ""]))
                    .font(.title.bold())
                    .multilineTextAlignment(.center)
                ForEach(1...4, id: \.self) { i in
                    let suggestion = model.t("chat.suggestion.\(i)")
                    Button { model.nova.chat.send(text: suggestion) } label: {
                        Text(suggestion).frame(maxWidth: .infinity, alignment: .leading).padding(16)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(24)
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(state.messages, id: \.id) { message in
                        MessageRow(message: message).id(message.id)
                    }
                }
                .padding(16)
            }
            .onChange(of: state.messages.last?.text) { _, _ in
                if let last = state.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
        }
    }

    private var inputBar: some View {
        VStack(spacing: 6) {
            HStack(alignment: .bottom, spacing: 8) {
                TextField(model.t("chat.inputPlaceholder"), text: $input, axis: .vertical)
                    .lineLimit(1...6)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 22).fill(Color(.secondarySystemGroupedBackground)))
                if state.streaming {
                    Button { model.nova.chat.stop() } label: { Image(systemName: "stop.circle.fill").font(.system(size: 40)) }
                        .accessibilityLabel(model.t("chat.stop"))
                } else {
                    Button {
                        model.nova.chat.send(text: input)
                        input = ""
                    } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 40)) }
                        .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityLabel(model.t("chat.send"))
                }
            }
            Text(model.t("ai.disclaimer")).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private var historySheet: some View {
        NavigationStack {
            List {
                Button { model.nova.chat.startNewChat(); showHistory = false } label: { Label(model.t("chat.newChat"), systemImage: "plus") }
                Section(model.t("chat.conversations")) {
                    if model.conversations.isEmpty { Text(model.t("chat.empty")).foregroundStyle(.secondary) }
                    ForEach(model.conversations, id: \.id) { c in
                        Button { model.nova.chat.open(conversationId: c.id); showHistory = false } label: { Text(c.title).lineLimit(1) }
                            .swipeActions { Button(model.t("chat.delete"), role: .destructive) { model.nova.chat.delete(conversationId: c.id) } }
                    }
                }
                Section {
                    Button { showHistory = false; showSettings = true } label: { Label(model.t("settings.title"), systemImage: "gearshape") }
                }
            }
            .navigationTitle(model.t("chat.conversations"))
        }
        .presentationDetents([.large])
    }
}

private struct MessageRow: View {
    let message: ChatMessage
    @EnvironmentObject var model: AppModel

    var body: some View {
        if message.isUser {
            HStack {
                Spacer(minLength: 48)
                Text(message.text).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 20).fill(Color.brand))
            }
        } else if message.text.isEmpty && message.streaming {
            HStack { ProgressView(); Text(model.t("chat.thinking")).foregroundStyle(.secondary); Spacer() }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text(markdown(message.text)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                if !message.streaming {
                    Button { UIPasteboard.general.string = message.text } label: { Image(systemName: "doc.on.doc") }
                        .font(.footnote).foregroundStyle(.secondary).accessibilityLabel(model.t("chat.copy"))
                }
            }
        }
    }

    private func markdown(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)
    }
}
