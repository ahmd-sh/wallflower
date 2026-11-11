import ReadModel
import SessionCore
import SettingsUI
import SwiftUI
import WallflowerDesign
import Wire

/**
 * The tabs (the session's windows), with new tab first and the connection
 * last. It's local: it scrolls natively and a tap switches immediately, the
 * Mac learns afterwards. The highlight glides to the tab chosen. Holding a
 * tab offers rename and close. The connection's dot opens its menu, which
 * says which Mac and session this is, and goes to the others.
 */
struct TabBar: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @Namespace private var glide
    let windows: [MuxWindow]
    let selected: String?
    let status: Status
    let machine: String
    let address: String
    let session: String?
    let onSelect: (MuxWindow) -> Void
    let onNew: () -> Void
    let onRename: (MuxWindow) -> Void
    let onClose: (MuxWindow) -> Void
    let onRetry: () -> Void
    let onSessions: () -> Void
    let onSettings: () -> Void
    let onExit: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button {
                haptics.tick()
                onNew()
            } label: {
                GlyphIcon(.plus, palette.fg, size: 14).padding(.horizontal, 9).padding(.vertical, 7).contentShape(Rectangle())
            }
            .buttonStyle(Pressable())
            .padding(.leading, 6)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(windows, id: \.id) { w in tab(w) }
                    }
                    .padding(.horizontal, 4)
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .onChange(of: selected) { _, id in
                    guard let id else { return }
                    withAnimation(Motion.spatial) { proxy.scrollTo(id, anchor: .center) }
                }
            }
            ConnectionButton(status: status, machine: machine, address: address, session: session, onRetry: onRetry, onSessions: onSessions, onSettings: onSettings, onExit: onExit)
        }
        .padding(.vertical, 9)
        .overlay(alignment: .bottom) { Rectangle().fill(palette.border).frame(height: 1) }
    }

    private func tab(_ w: MuxWindow) -> some View {
        let on = w.id == selected
        let agent = activity(of: w)
        return HStack(spacing: 0) {
            Text("\(w.index)").font(look.font(.label, weight: .bold)).foregroundColor(on ? palette.orange : palette.dim)
            Text(" \(w.label())").font(look.font(.label)).foregroundColor(on ? palette.fg : palette.grey).lineLimit(1)
            if let agent { AgentDot(activity: agent).padding(.leading, 6) }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(palette.bg1)
                if on { RoundedRectangle(cornerRadius: 8).fill(palette.bg3).matchedGeometryEffect(id: "highlight", in: glide) }
            }
        }
        .animation(Motion.effect, value: on)
        .contentShape(RoundedRectangle(cornerRadius: 8))
        .onTapGesture {
            if !on { haptics.tick() }
            withAnimation(Motion.spatial) { onSelect(w) }
        }
        .contextMenu {
            Button("rename") { onRename(w) }
            Button("close", role: .destructive) { onClose(w) }
        }
        .id(w.id)
    }

    /** What a window's programs are doing that needs an eye: one waiting for the user before any working. */
    private func activity(of w: MuxWindow) -> Activity? {
        let doing = w.panes.compactMap { activityOf($0) }
        return doing.first { $0 == .waiting } ?? doing.first { $0 == .working }
    }
}

/** What a program is doing, as a coloured dot, which breathes while it works. */
struct AgentDot: View {
    @Environment(\.palette) private var palette
    let activity: Activity
    @State private var breath = false

    var body: some View {
        Circle()
            .fill(activity == .waiting ? palette.orange : palette.purple)
            .frame(width: 6, height: 6)
            .opacity(activity == .working && breath ? 0.35 : 1)
            .animation(activity == .working ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : Motion.effect, value: breath)
            .onAppear { breath = activity == .working }
            .onChange(of: activity) { _, a in breath = a == .working }
    }
}
