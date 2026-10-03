import Shared
import SwiftUI

/// Semesterprogramm aus der Cloud: nach Monaten gruppiert, interne Termine markiert.
struct EventsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var connecting = false

    private var state: EventsState { model.events }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(model.t("events.title"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel(model.t("files.back"))
                    }
                }
                .overlay(alignment: .top) { if state.loading { ProgressView().padding(.top, 8) } }
                .refreshable { model.nova.events.refresh() }
        }
        .task { model.nova.events.refresh() }
    }

    @ViewBuilder private var content: some View {
        if state.needsCentralLogin {
            VStack(spacing: 16) {
                Image(systemName: "calendar.badge.exclamationmark").font(.system(size: 60)).foregroundStyle(Color.brand)
                Text(model.t("files.connectTitle")).font(.title2.bold()).multilineTextAlignment(.center)
                Text(model.t("events.connectBody")).foregroundStyle(.secondary).multilineTextAlignment(.center)
                PrimaryButton(title: model.t("files.connect"), loading: connecting) {
                    Task {
                        connecting = true
                        defer { connecting = false }
                        if await model.loginWithChattia() is LoginResult.Success { model.nova.events.refresh() }
                    }
                }
            }
            .padding(24)
            .frame(maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView(error, systemImage: "wifi.exclamationmark")
        } else if state.events.isEmpty && !state.loading {
            ContentUnavailableView(model.t("events.empty"), systemImage: "calendar")
        } else {
            List {
                ForEach(months, id: \.0) { month, events in
                    Section(month) {
                        ForEach(events, id: \.id) { event in
                            row(event)
                                .contextMenu { rsvpButtons(event) }
                                .swipeActions(edge: .leading) {
                                    Button(model.t("events.rsvp.yes")) { model.nova.events.rsvp(event: event, status: "yes", guests: 0) }.tint(.green)
                                }
                                .swipeActions(edge: .trailing) {
                                    Button(model.t("events.rsvp.no")) { model.nova.events.rsvp(event: event, status: "no", guests: 0) }.tint(.gray)
                                }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private var months: [(String, [EventDto])] {
        var result: [(String, [EventDto])] = []
        for event in state.events {
            let month = Self.month.string(from: date(event))
            if let last = result.last, last.0 == month {
                result[result.count - 1].1.append(event)
            } else {
                result.append((month, [event]))
            }
        }
        return result
    }

    private func row(_ event: EventDto) -> some View {
        let start = date(event)
        return HStack(spacing: 14) {
            VStack(spacing: 0) {
                Text(Self.weekday.string(from: start)).font(.caption)
                Text(Self.day.string(from: start)).font(.title2.bold())
            }
            .frame(width: 52, height: 52)
            .background(RoundedRectangle(cornerRadius: 12).fill(event.isInternal ? Color(.systemGray5) : Color.brand.opacity(0.12)))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(event.title).font(.headline)
                    if event.isInternal {
                        Text(model.t("events.internal")).font(.caption2).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Capsule().fill(Color(.systemGray5)))
                    }
                }
                Text([event.allDay ? model.t("events.allDay") : Self.time.string(from: start) + " Uhr", event.location]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary)
                if let line = rsvpLine(event) {
                    Text(line).font(.subheadline.weight(.semibold))
                        .foregroundStyle(event.myRsvp == "yes" ? Color.brand : .secondary)
                }
            }
        }
    }

    @ViewBuilder private func rsvpButtons(_ event: EventDto) -> some View {
        Button { model.nova.events.rsvp(event: event, status: "yes", guests: 0) } label: { Label(model.t("events.rsvp.yes"), systemImage: "checkmark.circle") }
        Button { model.nova.events.rsvp(event: event, status: "yes", guests: 1) } label: { Label(model.t("events.rsvp.guest"), systemImage: "person.2") }
        Button { model.nova.events.rsvp(event: event, status: "maybe", guests: 0) } label: { Label(model.t("events.rsvp.maybe"), systemImage: "questionmark.circle") }
        Button(role: .destructive) { model.nova.events.rsvp(event: event, status: "no", guests: 0) } label: { Label(model.t("events.rsvp.no"), systemImage: "xmark.circle") }
    }

    private func rsvpLine(_ event: EventDto) -> String? {
        let count = event.attending > 0 ? model.t("events.rsvp.attending", ["count": "\(event.attending)"]) : nil
        let status: String? = switch event.myRsvp {
        case "yes": "✓ " + model.t("events.rsvp.statusYes")
        case "maybe": "? " + model.t("events.rsvp.statusMaybe")
        case "no": "✕ " + model.t("events.rsvp.statusNo")
        default: nil
        }
        let parts = [status, count].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func date(_ event: EventDto) -> Date {
        ISO8601DateFormatter().date(from: event.start) ?? .now
    }

    private static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.timeZone = TimeZone(identifier: "Europe/Berlin")
        f.dateFormat = pattern
        return f
    }

    private static let month = formatter("LLLL yyyy")
    private static let weekday = formatter("EE")
    private static let day = formatter("d")
    private static let time = formatter("HH:mm")
}
