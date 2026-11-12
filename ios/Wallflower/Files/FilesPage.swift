import SessionCore
import SettingsUI
import SwiftUI
import WallflowerDesign
import Wire

/** Where the files page opens: a directory of the computer's (~ for the home), and the pane it's for, whose draft takes paths. */
struct FilesAt: Identifiable {
    let id = UUID()
    let start: String
    let pane: String?
}

/** A step into the computer's files: a folder, or a file to view. */
enum FilesRoute: Hashable {
    case folder(String)
    case file(String, FileEntry)
}

/** The computer's directories as they're listed, kept while the page is open, and asked again when wanted. */
@MainActor
@Observable
final class FilesModel {
    let session: Session
    private(set) var listings: [String: Listing] = [:]
    private(set) var errors: [String: String] = [:]

    init(session: Session) {
        self.session = session
    }

    func load(_ path: String, again: Bool = false) async {
        guard again || listings[path] == nil else { return }
        do {
            let l = try await session.list(path)
            listings[path] = l
            errors[path] = nil
        } catch is CancellationError {
        } catch {
            errors[path] = error.localizedDescription
        }
    }

    /** Where [start] is, absolute: a listing of it says, for ~. */
    func resolve(_ start: String) async -> String {
        if start.hasPrefix("/") { return start }
        do {
            let l = try await session.list(start)
            listings[l.path] = l
            return l.path
        } catch {
            return "/"
        }
    }
}

/**
 * The computer's files, read-only, over everything: a folder at a time, as iOS
 * navigates, each pushed onto the one above it, so back (or a swipe from the
 * edge) goes up, and the title's menu goes straight to any folder above.
 * Folders first, hidden entries only when asked for. A file opens in the
 * viewer; held, a row offers to share it, put its path in the message being
 * written ([onAdd]), or copy it.
 */
struct FilesPage: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let at: FilesAt
    let session: Session
    let settings: Preferences
    let onAdd: ((String) -> Void)?
    let onClose: () -> Void

    @State private var model: FilesModel
    @State private var stack: [FilesRoute] = []
    @State private var ready = false
    @State private var sharer = Sharer()

    init(at: FilesAt, session: Session, settings: Preferences, onAdd: ((String) -> Void)?, onClose: @escaping () -> Void) {
        self.at = at
        self.session = session
        self.settings = settings
        self.onAdd = onAdd
        self.onClose = onClose
        _model = State(initialValue: FilesModel(session: session))
    }

    var body: some View {
        Group {
            if ready {
                NavigationStack(path: $stack) {
                    folder("/")
                        .navigationDestination(for: FilesRoute.self) { route in
                            switch route {
                            case .folder(let path): folder(path)
                            case .file(let path, let e):
                                FileViewer(viewed: .file(path: path, size: e.size, mtime: e.mtime), session: session, onAdd: onAdd)
                                    .toolbarBackground(palette.bg0, for: .navigationBar)
                            }
                        }
                }
                .tint(palette.orange)
            } else {
                Text("reading…").words(.label).foregroundColor(palette.dim).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(palette.bg0.ignoresSafeArea())
        .sharing(sharer)
        .onChange(of: stack.count) { _, _ in if ready { haptics.tick() } }
        .task {
            let start = await model.resolve(at.start)
            stack = ancestors(start).map { .folder($0) }
            ready = true
        }
    }

    private func folder(_ path: String) -> some View {
        Folder(
            path: path, model: model, showHidden: Binding(get: { settings.hiddenFiles }, set: { settings.hiddenFiles = $0 }), depth: depth(of: path),
            onJump: { jump(to: $0) }, onClose: onClose, onAdd: onAdd,
            onShare: { p, e in sharer.share(p, size: e.size, mtime: e.mtime, session: session, haptics: haptics) }
        )
    }

    /** The folders from the top down to [path], the root left out, as the stack holds them. */
    private func ancestors(_ path: String) -> [String] {
        var out: [String] = []
        var at = ""
        for part in path.split(separator: "/") {
            at += "/" + part
            out.append(at)
        }
        return out
    }

    private func depth(of path: String) -> Int { path == "/" ? 0 : path.split(separator: "/").count }

    /** Back up to a folder above, without passing through those between. */
    private func jump(to path: String) {
        stack = Array(stack.prefix(depth(of: path)))
    }
}

/** A folder's entries, folders first; a row for each, in mono, with its size and age. */
private struct Folder: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let path: String
    let model: FilesModel
    @Binding var showHidden: Bool
    let depth: Int
    let onJump: (String) -> Void
    let onClose: () -> Void
    let onAdd: ((String) -> Void)?
    let onShare: (String, FileEntry) -> Void

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(palette.bg0.ignoresSafeArea())
            .navigationTitle(folderName(path))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(palette.bg0, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) { title }
                if depth == 0 {
                    ToolbarItem(placement: .topBarLeading) { close }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Toggle("show hidden", isOn: $showHidden)
                        if let onAdd {
                            Button("add to message") {
                                haptics.confirm()
                                onAdd(path)
                            }
                        }
                        Button("copy path") {
                            UIPasteboard.general.string = path
                            haptics.confirm()
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    if depth > 0 { close }
                }
            }
            .task { await model.load(path) }
    }

    private var close: some View {
        Button {
            haptics.tick()
            onClose()
        } label: {
            Image(systemName: "xmark")
        }
    }

    /** The folder's name, and a menu of those above it to go straight to. */
    private var title: some View {
        Menu {
            ForEach(above, id: \.self) { p in
                Button(p == "/" ? "/" : folderName(p) + (isHome(p) ? "" : "  ·  " + ((p as NSString).deletingLastPathComponent))) { onJump(p) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(folderName(path)).font(look.font(.body, weight: .semibold)).foregroundColor(palette.fg).lineLimit(1)
                if !above.isEmpty { Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold)).foregroundColor(palette.grey) }
            }
        }
        .disabled(above.isEmpty)
    }

    /** The folders above this one, nearest first. */
    private var above: [String] {
        var out: [String] = []
        var p = path
        while p != "/" && !p.isEmpty {
            p = (p as NSString).deletingLastPathComponent
            out.append(p.isEmpty ? "/" : p)
        }
        return out
    }

    @ViewBuilder
    private var content: some View {
        if let l = model.listings[path] {
            let all = sorted(l.entries)
            let shown = showHidden ? all : all.filter { !$0.hidden }
            if shown.isEmpty {
                Hint(all.isEmpty ? "nothing here" : "only hidden files here (\(all.count))")
            } else {
                List {
                    ForEach(shown, id: \.name) { e in row(e) }
                    if l.more {
                        Text("the first \(l.entries.count) of more").words(.caption).foregroundColor(palette.dim).listRowBackground(palette.bg0).listRowSeparator(.hidden)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .refreshable { await model.load(path, again: true) }
            }
        } else if let why = model.errors[path] {
            VStack(spacing: 12) {
                Text(why).words(.label).foregroundColor(palette.grey).multilineTextAlignment(.center).padding(.horizontal, 32)
                WordButton("try again") { Task { await model.load(path, again: true) } }
            }
        } else {
            Hint("reading…")
        }
    }

    @ViewBuilder
    private func row(_ e: FileEntry) -> some View {
        let full = path == "/" ? "/" + e.name : path + "/" + e.name
        let label = Row(entry: e)
        Group {
            switch e.kind {
            case FileEntry.DIR: NavigationLink(value: FilesRoute.folder(full)) { label }
            case FileEntry.FILE: NavigationLink(value: FilesRoute.file(full, e)) { label }
            default: label
            }
        }
        .listRowBackground(palette.bg0)
        .listRowSeparatorTint(palette.bg2)
        .contextMenu {
            if e.kind == FileEntry.FILE { Button("share") { onShare(full, e) } }
            if let onAdd {
                Button("add to message") {
                    haptics.confirm()
                    onAdd(full)
                }
            }
            Button("copy path") {
                UIPasteboard.general.string = full
                haptics.confirm()
            }
        }
    }

    private func sorted(_ entries: [FileEntry]) -> [FileEntry] {
        entries.sorted { a, b in
            if a.isDir != b.isDir { return a.isDir }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }
}

/** An entry: its icon by kind, its name, and its size and age; a link says so, a hidden one is dim. */
private struct Row: View {
    @Environment(\.palette) private var palette
    let entry: FileEntry

    var body: some View {
        let kind = FileKind(name: entry.name, folder: entry.isDir)
        HStack(spacing: 12) {
            Text(entry.kind == FileEntry.OTHER ? "\u{f128}" : kind.icon)
                .font(Fonts.mono(18))
                .foregroundColor(entry.kind == FileEntry.OTHER ? palette.dim : kind.color(palette))
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(entry.name).font(Fonts.mono(14)).foregroundColor(palette.fg).lineLimit(1).truncationMode(.middle)
                    if entry.link { Text("\u{f0c1}").font(Fonts.mono(11)).foregroundColor(palette.grey) }
                }
                if !detail.isEmpty { Text(detail).words(.caption).foregroundColor(palette.dim).lineLimit(1) }
            }
        }
        .padding(.vertical, 2)
        .opacity(entry.hidden ? 0.55 : 1)
    }

    private var detail: String {
        var parts: [String] = []
        if entry.kind == FileEntry.FILE { parts.append(sizeText(entry.size)) }
        if entry.mtime > 0 { parts.append(agoText(entry.mtime)) }
        if entry.kind == FileEntry.OTHER { parts.append(entry.link ? "a link to nothing" : "not a file") }
        return parts.joined(separator: " · ")
    }
}

private struct Hint: View {
    @Environment(\.palette) private var palette
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).words(.label).foregroundColor(palette.dim).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
