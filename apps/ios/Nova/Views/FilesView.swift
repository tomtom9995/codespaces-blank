import QuickLook
import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Dateien aus der Chattia-Cloud (Nextcloud): blättern, Vorschau, hochladen, Ordner anlegen, löschen.
struct FilesView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var newFolderShown = false
    @State private var newFolderName = ""
    @State private var importerShown = false
    @State private var deleteCandidate: FileEntryDto?
    @State private var previewURL: URL?
    @State private var opening = false
    @State private var connecting = false

    private var state: FilesState { model.files }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(state.title.isEmpty ? model.t("files.title") : state.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .overlay(alignment: .top) { if state.loading || opening { ProgressView().padding(.top, 8) } }
                .safeAreaInset(edge: .bottom) { noticeBanner }
                .refreshable { model.nova.files.refresh() }
        }
        .task { model.nova.files.open(path: state.path) }
        .quickLookPreview($previewURL)
        .fileImporter(isPresented: $importerShown, allowedContentTypes: [.item]) { result in
            if case .success(let url) = result { upload(url) }
        }
        .alert(model.t("files.newFolder"), isPresented: $newFolderShown) {
            TextField(model.t("files.folderName"), text: $newFolderName)
            Button(model.t("files.create")) {
                let name = newFolderName.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty { model.nova.files.createFolder(name: name) }
                newFolderName = ""
            }
            Button(model.t("files.cancel"), role: .cancel) { newFolderName = "" }
        }
        .confirmationDialog(
            model.t("files.deleteConfirm", ["name": deleteCandidate?.name ?? ""]),
            isPresented: Binding(get: { deleteCandidate != nil }, set: { if !$0 { deleteCandidate = nil } }),
            titleVisibility: .visible
        ) {
            Button(model.t("files.delete"), role: .destructive) {
                if let entry = deleteCandidate { model.nova.files.delete(entry: entry) }
                deleteCandidate = nil
            }
        }
    }

    @ViewBuilder private var content: some View {
        if state.needsCentralLogin {
            VStack(spacing: 16) {
                Image(systemName: "icloud.slash").font(.system(size: 64)).foregroundStyle(Color.brand)
                Text(model.t("files.connectTitle")).font(.title2.bold()).multilineTextAlignment(.center)
                Text(model.t("files.connectBody")).foregroundStyle(.secondary).multilineTextAlignment(.center)
                PrimaryButton(title: model.t("files.connect"), loading: connecting) {
                    Task {
                        connecting = true
                        defer { connecting = false }
                        if await model.loginWithChattia() is LoginResult.Success { model.nova.files.open(path: "/") }
                    }
                }
            }
            .padding(24)
            .frame(maxHeight: .infinity)
        } else if state.entries.isEmpty && !state.loading {
            ContentUnavailableView(model.t("files.empty"), systemImage: "folder")
        } else {
            List(state.entries, id: \.path) { entry in
                Button { open(entry) } label: { row(entry) }
                    .foregroundStyle(.primary)
                    .swipeActions {
                        if entry.canDelete {
                            Button(model.t("files.delete"), role: .destructive) { deleteCandidate = entry }
                        }
                    }
            }
            .listStyle(.plain)
        }
    }

    private func row(_ entry: FileEntryDto) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon(entry))
                .font(.title2)
                .foregroundStyle(entry.isFolder ? Color.brand : .secondary)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).lineLimit(1)
                Text(details(entry)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if entry.isFolder { Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary) }
        }
        .contentShape(Rectangle())
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button {
                if !model.nova.files.up() { dismiss() }
            } label: { Image(systemName: state.isRoot ? "xmark" : "chevron.backward") }
                .accessibilityLabel(model.t("files.back"))
        }
        if !state.needsCentralLogin {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { newFolderShown = true } label: { Label(model.t("files.newFolder"), systemImage: "folder.badge.plus") }
                    Button { importerShown = true } label: { Label(model.t("files.upload"), systemImage: "square.and.arrow.up") }
                } label: { Image(systemName: "plus") }
                    .accessibilityLabel(model.t("files.newFolder"))
            }
        }
    }

    @ViewBuilder private var noticeBanner: some View {
        if let text = state.notice ?? state.error {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.white)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.darkGray)))
                .padding()
                .onTapGesture { model.nova.files.dismissNotice() }
                .task {
                    try? await Task.sleep(for: .seconds(4))
                    model.nova.files.dismissNotice()
                }
        }
    }

    // MARK: - Aktionen

    private func open(_ entry: FileEntryDto) {
        if entry.isFolder {
            model.nova.files.open(path: entry.path)
            return
        }
        Task {
            opening = true
            defer { opening = false }
            do {
                let bytes = try await model.nova.files.download(entry: entry)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cloud", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let file = dir.appendingPathComponent(entry.name)
                try BytesKt.byteArrayToNSData(bytes: bytes).write(to: file, options: [.atomic, .completeFileProtection])
                previewURL = file
            } catch {
                model.nova.files.dismissNotice()
            }
        }
    }

    private func upload(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return }
        let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
        model.nova.files.upload(name: url.lastPathComponent, bytes: BytesKt.nsDataToByteArray(data: data), contentType: type)
    }

    private func icon(_ entry: FileEntryDto) -> String {
        if entry.isFolder { return "folder.fill" }
        let type = entry.contentType ?? ""
        if type.hasPrefix("image/") { return "photo" }
        if type == "application/pdf" { return "doc.richtext" }
        return "doc"
    }

    private func details(_ entry: FileEntryDto) -> String {
        var parts: [String] = []
        if let size = entry.size { parts.append(ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file)) }
        if let modified = entry.modified, let date = ISO8601DateFormatter().date(from: modified) {
            parts.append(date.formatted(date: .abbreviated, time: .omitted))
        }
        return parts.joined(separator: " · ")
    }
}
