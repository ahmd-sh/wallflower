import MobileViewUI
import PhotosUI
import ScreenReading
import SwiftUI
import UniformTypeIdentifiers
import WallflowerDesign

/**
 * A plain text field, so the keyboard's autocorrect, swipe and dictation
 * all work, with the key bar and send button beneath it. Return is a
 * newline. A sticky key on the bar (pfx, ctrl) takes the next key, from the
 * bar or the keyboard.
 *
 * The box is the phone's own: nothing the program does writes into it, so a
 * draft stays put until sent. The program keeps its own input line ([line],
 * when wallflower can read it). While the box is empty it shows that line
 * dimmed, with × to clear it in the program ([onClear]), or else what the
 * program suggests typing; a swipe right brings either into the box to edit.
 * What's in the box replaces the line when sent, so the box is exactly what
 * runs. The button sends (held: types it in without Enter), or queues it
 * while the program at work [queues] what it's sent, or [holds] it while an
 * answer comes first; enters the program's line when only that has text;
 * or stops: while the program's at work ([stoppable]), or just after
 * sending.
 *
 * + sends photos or files to the computer; each shows above the box on its way,
 * then its path goes in, shown as its name. + also offers the program's
 * [commands], which start the draft.
 *
 * The shortcuts key offers the pane's [shortcuts]: one picked goes to
 * [onShortcut], but text alone, with no Enter to send it, starts the draft
 * as a command does (or goes at its end, if it isn't one). The arrows
 * key brings up their [pad], which stays put for as long as it's up, here
 * and for the next pane, while it's been moved or the bar has the key.
 *
 * With [typing], the keyboard types straight into the pane instead, as a
 * terminal's does: no box, no +, just the key bar and the button ([onEnter],
 * pressing [sendKeys], which it shows if they aren't Enter; or stopping the
 * pane while it's [working] and nothing's been typed for it since it last
 * took a line).
 */
struct Composer: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @Binding var text: String
    let program: String
    let keys: [BarKey]
    let connected: Bool
    let line: InputLine?
    /** A sent message asked back to edit: once the program puts it back in its line, it comes into the box. */
    let recalled: Seeker.Recalled?
    let uploads: [Uploads.Item]
    let uploadDir: String?
    let thumbnail: (String) -> UIImage?
    let onAttach: ([Picked]) -> Void
    let onDismissUpload: (String) -> Void
    let onClear: (() -> Void)?
    let onKey: (BarKey) -> Void
    let typing: Bool
    /** The keyboard's up, typing straight into the pane. */
    let typingActive: Bool
    let onHideKeyboard: () -> Void
    let sticky: StickyKeys
    let sendKeys: [String]
    let commands: [Command]
    let onAcceptSuggestion: (() -> Void)?
    let stoppable: Bool
    let working: Bool
    let queues: Bool
    let holds: Bool
    /** Typed straight into the pane since it last took a line. */
    let typed: Bool
    let onSend: (String, Bool) -> Bool
    let onEnter: () -> Void
    let onStop: () -> Void
    let onEditKeys: () -> Void
    let pad: ArrowPad
    var shortcuts: [BarKey] = []
    var onShortcut: (BarKey) -> Void = { _ in }
    var onAddShortcut: () -> Void = {}

    @FocusState private var focused: Bool
    @State private var shortcutsMenu = ShortcutsMenu()
    @State private var barHeight: CGFloat = 0
    @State private var sent = false
    @State private var accepting: String?
    @State private var recalling: String?

    private var pending: String { line?.text ?? "" }
    private var ghost: String { pending.isEmpty ? (line?.suggestion ?? "") : pending }

    var body: some View {
        VStack(spacing: 0) {
            if !typing {
                AttachmentsRow(text: $text, uploads: uploads, dir: uploadDir, thumbnail: thumbnail, onDismiss: onDismissUpload)
                box.padding(.horizontal, 18)
            }
            HStack(spacing: 0) {
                if !typing {
                    PlusButton(enabled: connected, commands: commands, onAttach: onAttach) { c in start(c.text) }
                        .padding(.trailing, 6)
                } else if typingActive {
                    Button(action: onHideKeyboard) {
                        GlyphIcon(.down, palette.grey, size: 14)
                            .frame(width: 36, height: 36)
                            .background(Circle().fill(palette.bg2))
                    }
                    .buttonStyle(Pressable())
                    .padding(.trailing, 6)
                }
                KeyRow(keys: keys, armed: sticky.armed, menu: shortcutsMenu, pad: pad, onKey: onKey, onEdit: onEditKeys)
                SendButton(action: action, connected: connected, label: action == .run && sendKeys != ["Enter"] ? sendKeys : nil, onSend: { enter in
                    if onSend(text, enter) {
                        text = ""
                        sent = enter
                    }
                }, onEnter: onEnter, onStop: onStop)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { barHeight = $0 }
            .overlay(alignment: .bottom) {
                if shortcutsMenu.open {
                    ShortcutsUp(menu: shortcutsMenu, shortcuts: shortcuts, onShortcut: shortcut, onAdd: onAddShortcut)
                        .padding(.bottom, barHeight + 16) // above the bar, clear of the composer's edge
                        .transition(.scale(scale: 0.96, anchor: .bottom).combined(with: .opacity))
                }
            }
            .padding(.top, typing ? 0 : 10)
            .padding(.horizontal, 8)
            .zIndex(1)
        }
        .padding(.top, typing ? 8 : 14)
        .padding(.bottom, 8)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(palette.bg1))
        .padding(.horizontal, 8)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .animation(Motion.spatial, value: typing)
        .onChange(of: keys.contains { $0.cluster }, initial: true) { _, clustered in
            // Above a key that's gone, it goes too; moved, it stays.
            if !clustered && pad.at == nil { pad.close() }
        }
        .task(id: sent) {
            guard sent else { return }
            try? await Task.sleep(for: .seconds(3)) // just sent, it can be stopped before the program is heard to be working
            sent = false
        }
        .onChange(of: pending) { _, now in
            // A suggestion asked for whole: the program's line has it once it's taken it in.
            if accepting != nil && !now.isEmpty {
                accepting = nil
                take(now)
            }
            // That message, not what's typed on the way (/rewind) or was there before.
            if let want = recalling, now.filter({ !$0.isWhitespace }) == want.filter({ !$0.isWhitespace }) {
                recalling = nil
                take(now)
            }
        }
        .task(id: recalled) {
            guard let r = recalled else { return }
            recalling = r.text
            try? await Task.sleep(for: .seconds(60)) // long enough to pick it from a list
            if recalling == r.text { recalling = nil }
        }
        .task(id: accepting) {
            guard let cut = accepting else { return }
            try? await Task.sleep(for: .seconds(1.5))
            if accepting == cut { // it didn't: what was seen of it, to finish
                accepting = nil
                haptics.reject()
                take(String(cut.dropLast(cut.hasSuffix("…") ? 1 : 0)))
            }
        }
    }

    private var action: SendAction {
        if typing { return working && !typed && pending.isEmpty ? .stop : .run }
        if !text.isEmpty && holds { return .hold }
        if !text.isEmpty && queues { return .queue }
        if !text.isEmpty { return .send }
        if !pending.isEmpty { return .enter }
        if stoppable || sent { return .stop }
        return .idle
    }

    private var box: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty && (sticky.armed != nil || ghost.isEmpty) {
                Text(sticky.armed.map { "\($0.label) + key" } ?? "send to \(program.isEmpty ? "pane" : program)")
                    .font(Fonts.mono(15))
                    .foregroundColor(sticky.armed != nil ? palette.orange : palette.dim)
                    .allowsHitTesting(false)
            }
            TextField("", text: $text, axis: .vertical)
                .lineLimit(1...6)
                .font(Fonts.mono(15))
                .foregroundColor(palette.fg)
                .tint(palette.orange)
                .textInputAutocapitalization(.never)
                .focused($focused)
                .opacity(text.isEmpty && !ghost.isEmpty && sticky.armed == nil ? 0.02 : 1)
                .onChange(of: text) { old, new in
                    // With a sticky key armed, the next character typed goes with it instead.
                    guard sticky.armed != nil, new.count == old.count + 1 else { return }
                    let added = new.enumerated().first { i, c in i >= old.count || old[old.index(old.startIndex, offsetBy: i)] != c }?.element
                    text = old
                    if let c = added { sticky.intercept(c) }
                }
            // Over the field (which is see-through beneath it), so its × and swipe are its own; a tap on it is the field's.
            if text.isEmpty && sticky.armed == nil && !ghost.isEmpty {
                GhostLine(text: ghost, suggested: pending.isEmpty, onClear: onClear, onTap: { focused = true }) {
                    if pending.isEmpty && ghost.hasSuffix("…"), let accept = onAcceptSuggestion {
                        accepting = ghost
                        accept()
                    } else {
                        take(ghost)
                    }
                }
            }
        }
        .frame(minHeight: 21)
    }

    private func take(_ s: String) {
        text = s
        focused = true
    }

    /** A shortcut picked: text alone, with no Enter to send it, starts the draft as a command does; anything else goes. */
    private func shortcut(_ key: BarKey) {
        sticky.drop()
        if !typing, case .text(let s, enter: false) = KeyBar.steps(key.keys).first, key.keys.count == 1 {
            start(s)
        } else {
            onShortcut(key)
        }
    }

    /** A command starts the draft, in place of one it started with; other text goes at its end. */
    private func start(_ s: String) {
        guard s.hasPrefix("/") else { return take(text + s) }
        let rest = text.replacingOccurrences(of: #"^/[^\s]+\s*"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        take(rest.isEmpty ? s + " " : s + " " + rest)
    }
}

/**
 * The program's line, or what it suggests typing ([suggested]), over the
 * empty box. A tap is the box's ([onTap]), to type afresh; swiped right it
 * fills with colour as it goes, and once full (a tick) it comes in to edit
 * ([onTake]). The program's own line has × to clear it there ([onClear]).
 */
private struct GhostLine: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let text: String
    let suggested: Bool
    let onClear: (() -> Void)?
    let onTap: () -> Void
    let onTake: () -> Void
    @State private var pulled: CGFloat = 0
    @State private var past = false

    private static let reach: CGFloat = 72

    var body: some View {
        let clearable = !suggested && onClear != nil
        let base = suggested ? palette.dim : palette.grey
        let fill = min(max(pulled / Self.reach, 0), 1)
        HStack(alignment: .top, spacing: 8) {
            if suggested { GlyphIcon(.spark, fill > 0 ? palette.fg : base, size: 11).padding(.top, 5) }
            Text(text)
                .font(Fonts.mono(15))
                .lineLimit(3)
                .foregroundStyle(
                    LinearGradient(stops: [
                        .init(color: palette.fg, location: 0),
                        .init(color: palette.fg, location: max(fill - 0.04, 0)),
                        .init(color: base, location: min(fill + 0.04, 1)),
                        .init(color: base, location: 1),
                    ], startPoint: .leading, endPoint: .trailing)
                )
            Spacer(minLength: clearable ? 30 : 0)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .gesture(
            DragGesture(minimumDistance: 10)
                .onChanged { v in
                    pulled = min(max(v.translation.width, 0), Self.reach * 1.5)
                    if (pulled >= Self.reach) != past {
                        past.toggle()
                        haptics.threshold(past)
                    }
                }
                .onEnded { _ in
                    if past { onTake() }
                    past = false
                    withAnimation(Motion.spatial) { pulled = 0 }
                }
        )
        .overlay(alignment: .topTrailing) {
            if clearable, let onClear {
                Button {
                    haptics.confirm()
                    onClear()
                } label: {
                    GlyphIcon(.close, palette.grey, size: 13).padding(4).contentShape(Circle())
                }
                .buttonStyle(Pressable())
            }
        }
    }
}

/** What the draft holds, each with × to take it out, and tapped, opening in the viewer; then what's on its way; why one failed beneath. */
private struct AttachmentsRow: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @Environment(\.viewing) private var viewing
    @Binding var text: String
    let uploads: [Uploads.Item]
    let dir: String?
    let thumbnail: (String) -> UIImage?
    let onDismiss: (String) -> Void

    var body: some View {
        let attached = attachedPaths()
        if !attached.isEmpty || !uploads.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(attached, id: \.self) { path in
                            Tile(image: thumbnail(path), name: (path as NSString).lastPathComponent, failed: false, progress: nil) {
                                haptics.tick()
                                text = text.replacingOccurrences(of: path, with: "").replacingOccurrences(of: "  ", with: " ")
                            }
                            .onTapGesture {
                                guard let open = viewing.path else { return }
                                haptics.tick()
                                open(path)
                            }
                        }
                        ForEach(uploads) { u in
                            Tile(image: u.thumbnail, name: u.name, failed: u.error != nil, progress: u.error == nil ? Double(u.sent) / Double(max(u.size, 1)) : nil) {
                                onDismiss(u.id)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                }
                if let u = uploads.first(where: { $0.error != nil }) {
                    Text("\(u.name): \(u.error!)").words(.caption).foregroundColor(palette.red).lineLimit(1).padding(.horizontal, 14).padding(.top, 6)
                }
            }
            .padding(.bottom, 10)
        }
    }

    private func attachedPaths() -> [String] {
        guard let dir, text.contains(dir), let re = uploadPaths(dir) else { return [] }
        let ns = text as NSString
        var seen: [String] = []
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let p = ns.substring(with: m.range)
            if !seen.contains(p) { seen.append(p) }
        }
        return seen
    }
}

/** A file: a photo as itself, anything else by its name; × takes it out. On its way, how far along. */
private struct Tile: View {
    @Environment(\.palette) private var palette
    let image: UIImage?
    let name: String
    let failed: Bool
    let progress: Double?
    let onRemove: () -> Void

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Text(name).font(Fonts.mono(10)).foregroundColor(palette.fg).lineLimit(3).padding(6)
            }
            if let progress {
                palette.bg0.opacity(0.55)
                ProgressView(value: progress).progressViewStyle(.circular).tint(palette.fg)
            } else if failed {
                palette.red.opacity(0.3)
            }
        }
        .frame(width: 56, height: 56)
        .background(palette.bg2)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(alignment: .topTrailing) {
            if progress == nil {
                Button(action: onRemove) {
                    GlyphIcon(.close, palette.fg, size: 9).padding(5).background(Circle().fill(palette.bg0.opacity(0.7)))
                }
                .buttonStyle(Pressable())
                .padding(3)
            }
        }
    }
}

/**
 * + : photos, through the system's picker, or any file; and the program's
 * commands. Open, it turns to ×; a press can slide straight onto a choice,
 * as iOS menus do.
 */
private struct PlusButton: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let enabled: Bool
    let commands: [Command]
    let onAttach: ([Picked]) -> Void
    let onCommand: (Command) -> Void
    @State private var photos = false
    @State private var files = false
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        PlusMenu(enabled: enabled, color: UIColor(enabled ? palette.fg : palette.dim), background: UIColor(palette.bg2), border: UIColor(palette.border), commands: commands, haptics: haptics) { choice in
            switch choice {
            case .photos: photos = true
            case .files: files = true
            case .command(let c): onCommand(c)
            }
        }
        .frame(width: 36, height: 36)
        .photosPicker(isPresented: $photos, selection: $picked, maxSelectionCount: 10, matching: .images)
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            picked = []
            Task {
                var out: [Picked] = []
                for (i, item) in items.enumerated() {
                    guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
                    let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                    let name = "photo-\(Int(Date().timeIntervalSince1970))\(items.count > 1 ? "-\(i + 1)" : "").\(ext)"
                    out.append(Picked(name: name, data: data, image: true))
                }
                if !out.isEmpty { onAttach(out) }
            }
        }
        .fileImporter(isPresented: $files, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            // Read off the main thread: a big file would hold the screen still.
            Task.detached {
                let out = urls.compactMap { url -> Picked? in
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    guard let data = try? Data(contentsOf: url) else { return nil }
                    return Picked(name: url.lastPathComponent, data: data, image: UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true)
                }
                if !out.isEmpty { await MainActor.run { onAttach(out) } }
            }
        }
    }
}

private enum PlusChoice {
    case photos, files
    case command(Command)
}

/** The + as a native menu button, whose cross turns as the menu opens and shuts. */
private struct PlusMenu: UIViewRepresentable {
    let enabled: Bool
    let color: UIColor
    let background: UIColor
    let border: UIColor
    let commands: [Command]
    let haptics: Haptics
    let onPick: (PlusChoice) -> Void

    func makeUIView(context: Context) -> TurningButton {
        let b = TurningButton(type: .custom)
        b.showsMenuAsPrimaryAction = true
        b.layer.cornerRadius = 18
        b.layer.borderWidth = 1
        b.haptics = haptics
        return b
    }

    func updateUIView(_ b: TurningButton, context: Context) {
        b.isEnabled = enabled
        b.backgroundColor = background
        b.layer.borderColor = border.cgColor
        b.setImage(TurningButton.plus(color), for: .normal)
        var items: [UIMenuElement] = [
            UIAction(title: "photos", image: UIImage(systemName: "photo")) { _ in onPick(.photos) },
            UIAction(title: "files", image: UIImage(systemName: "doc")) { _ in onPick(.files) },
        ]
        if !commands.isEmpty {
            items.append(UIMenu(title: "", options: .displayInline, children: commands.map { c in
                let a = UIAction(title: c.text) { _ in onPick(.command(c)) }
                a.subtitle = c.about.isEmpty ? nil : c.about
                return a
            }))
        }
        b.menu = UIMenu(children: items)
    }
}

final class TurningButton: UIButton {
    var haptics: Haptics?

    static func plus(_ color: UIColor) -> UIImage {
        let size = CGSize(width: 14, height: 14)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let c = ctx.cgContext
            c.setStrokeColor(color.cgColor)
            c.setLineWidth(1.8)
            c.setLineCap(.round)
            let r = size.width / 2 - 1.8
            c.move(to: CGPoint(x: size.width / 2 - r, y: size.height / 2))
            c.addLine(to: CGPoint(x: size.width / 2 + r, y: size.height / 2))
            c.move(to: CGPoint(x: size.width / 2, y: size.height / 2 - r))
            c.addLine(to: CGPoint(x: size.width / 2, y: size.height / 2 + r))
            c.strokePath()
        }
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willDisplayMenuFor configuration: UIContextMenuConfiguration, animator: (any UIContextMenuInteractionAnimating)?) {
        super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
        haptics?.slide(0.12)
        UIView.animate(withDuration: 0.22, delay: 0, usingSpringWithDamping: 0.8, initialSpringVelocity: 0) { self.imageView?.transform = CGAffineTransform(rotationAngle: -.pi / 4) }
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willEndFor configuration: UIContextMenuConfiguration, animator: (any UIContextMenuInteractionAnimating)?) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        haptics?.slide(0.08)
        UIView.animate(withDuration: 0.22, delay: 0, usingSpringWithDamping: 0.8, initialSpringVelocity: 0) { self.imageView?.transform = .identity }
    }
}
