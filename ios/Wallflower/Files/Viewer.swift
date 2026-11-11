import MobileViewUI
import QuickLook
import SessionCore
import SwiftUI
import WallflowerDesign

/** What the viewer shows: a file of the computer's (its size and age, if a listing said), or a picture of a pane's mobile view. */
enum Viewed: Hashable {
    case file(path: String, size: Int64? = nil, mtime: Int64? = nil)
    case picture(pane: String, id: String, mime: String)

    var name: String {
        switch self {
        case .file(let path, _, _): return (path as NSString).lastPathComponent
        case .picture(_, let id, let mime): return "picture-\(id.replacingOccurrences(of: ".", with: "-")).\(mime.split(separator: "/").last.map(String.init) ?? "png")"
        }
    }

    var path: String? {
        if case .file(let path, _, _) = self { return path }
        return nil
    }
}

/** The viewer, opened over everything: what it shows, and the pane whose draft takes its path, if any. */
struct Showing: Identifiable {
    let id = UUID()
    let viewed: Viewed
    let pane: String?
}

/**
 * One viewer for pictures and the computer's files: it fetches what it shows,
 * saying how far along, then reads text and markdown itself (the start of a
 * big file), and has QuickLook show the rest (pictures to zoom, PDFs,
 * films). What it can't show it offers to share. Its path can go into the
 * message being written ([onAdd]).
 */
struct FileViewer: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let viewed: Viewed
    let session: Session
    let onAdd: ((String) -> Void)?

    private enum Phase: Equatable {
        case fetching(got: Int64, of: Int64)
        case shown(URL, FileKind, cut: Bool)
        case unshown(URL?)
        case failed(String)
    }

    @State private var phase: Phase = .fetching(got: 0, of: 0)
    @State private var text: String?
    @State private var sharer = Sharer()
    @State private var tries = 0

    private var kind: FileKind { FileKind(name: viewed.name) }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(palette.bg0.ignoresSafeArea())
            .navigationTitle(viewed.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text(viewed.name).font(look.font(.body, weight: .semibold)).foregroundColor(palette.fg).lineLimit(1).truncationMode(.middle)
                        if let size = size { Text(sizeText(size)).words(.caption).foregroundColor(palette.dim) }
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        haptics.tick()
                        share()
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .disabled(sharer.fetching != nil || isFetching)
                    if let path = viewed.path {
                        Menu {
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
                    }
                }
            }
            .sharing(sharer)
            .task(id: tries) { await load() }
            .animation(Motion.effect, value: phase)
    }

    private var isFetching: Bool { if case .fetching = phase { return true } else { return false } }

    private var size: Int64? {
        if case .file(_, let size, _) = viewed { return size }
        return nil
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .fetching(let got, let of):
            Card(icon: kind.icon, color: kind.color(palette), name: viewed.name) {
                ProgressView(value: of > 0 ? Double(got) / Double(of) : 0).tint(palette.orange).frame(width: 180)
                Text(of > 0 ? "\(sizeText(got)) of \(sizeText(of))" : "fetching…").words(.caption).foregroundColor(palette.grey).monospacedDigit()
            }
        case .shown(let url, let kind, let cut):
            if kind.previewed {
                QuickLookView(url: url).ignoresSafeArea(edges: .bottom)
            } else if let text {
                Reader(text: text, markdown: kind == .markdown, cut: cut ? "the first \(sizeText(readLimit))" + (size.map { " of \(sizeText($0))" } ?? "") + " · share sends it whole" : nil)
            }
        case .unshown:
            Card(icon: kind.icon, color: kind.color(palette), name: viewed.name) {
                if let size { Text(sizeText(size)).words(.caption).foregroundColor(palette.grey) }
                WordButton("share", primary: true) { share() }.padding(.top, 6)
            }
        case .failed(let why):
            Card(icon: kind.icon, color: palette.red, name: viewed.name) {
                Text(why).words(.label).foregroundColor(palette.grey).multilineTextAlignment(.center).padding(.horizontal, 24)
                WordButton("try again") { tries += 1 }.padding(.top, 6)
            }
        }
    }

    private func load() async {
        switch viewed {
        case .picture(let pane, let id, _):
            guard let data = await session.picture(pane: pane, id: id) else { return fail("the picture's gone from the computer") }
            let url = Fetched.place(viewed.name)
            do {
                try data.write(to: url)
                phase = .shown(url, .picture, cut: false)
            } catch {
                fail("there's no room for it on the phone")
            }
        case .file(let path, let size, let mtime):
            let kind = self.kind
            guard kind.read || kind.previewed else {
                phase = .unshown(nil)
                return
            }
            let limit: Int64? = kind.read && (size ?? Int64.max) > readLimit ? readLimit : nil
            if let url = Fetched.kept(path, size: size, mtime: mtime) { return await show(url, kind, cut: false) }
            if let limit, let url = Fetched.kept(path, size: size, mtime: mtime, limit: limit) { return await show(url, kind, cut: true) }
            phase = .fetching(got: 0, of: limit ?? size ?? 0)
            let url = Fetched.place(viewed.name)
            do {
                let r = try await session.fetch(path, limit: limit, to: url) { got, of in
                    if case .fetching = phase { phase = .fetching(got: got, of: of) }
                }
                Fetched.keep(url, path, size: size, mtime: mtime, limit: limit)
                await show(url, kind, cut: limit != nil && r.size >= readLimit && (size ?? Int64.max) > r.size)
            } catch is CancellationError {
            } catch {
                fail(error.localizedDescription)
            }
        }
    }

    private func show(_ url: URL, _ kind: FileKind, cut: Bool) async {
        if kind.read {
            text = await Task.detached { String(decoding: (try? Data(contentsOf: url)) ?? Data(), as: UTF8.self) }.value
        } else if !QLPreviewController.canPreview(url as NSURL) {
            phase = .unshown(url)
            return
        }
        phase = .shown(url, kind, cut: cut)
    }

    private func fail(_ why: String) {
        haptics.reject()
        phase = .failed(why)
    }

    /** Shares what's shown, as it is if it's all here, else fetched whole. */
    private func share() {
        switch (phase, viewed) {
        case (.shown(let url, _, false), _), (.unshown(.some(let url)), _):
            sharer.share(url)
        case (_, .file(let path, let size, let mtime)):
            sharer.share(path, size: size, mtime: mtime, session: session, haptics: haptics)
        default:
            break
        }
    }
}

/** A file as its icon and name, with what's to say of it beneath. */
private struct Card<Below: View>: View {
    @Environment(\.palette) private var palette
    let icon: String
    let color: Color
    let name: String
    @ViewBuilder let below: () -> Below

    var body: some View {
        VStack(spacing: 10) {
            Text(icon).font(Fonts.mono(54)).foregroundColor(color)
            Text(name).font(Fonts.mono(14)).foregroundColor(palette.fg).multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, 24)
            below()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .transition(.opacity)
    }
}

/** Text to read: markdown rendered, anything else in mono, a few hundred lines at a time so a long file scrolls smoothly; what's [cut] said at the end. */
private struct Reader: View {
    @Environment(\.palette) private var palette
    let text: String
    let markdown: Bool
    let cut: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if markdown {
                    MarkdownText(text, breaks: false) // a document's lines wrap
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(chunks.enumerated()), id: \.offset) { _, chunk in
                            Text(chunk)
                                .font(Fonts.mono(12))
                                .lineSpacing(3)
                                .foregroundColor(palette.fg)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                if let cut {
                    Text(cut).words(.caption).foregroundColor(palette.dim).frame(maxWidth: .infinity).padding(.top, 8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .transition(.opacity)
    }

    private var chunks: [String] {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        return stride(from: 0, to: lines.count, by: 200).map { lines[$0 ..< min($0 + 200, lines.count)].joined(separator: "\n") }
    }
}

/** QuickLook's preview of a file on the phone: pictures to pinch, PDFs to page through, films to play. */
struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let c = QLPreviewController()
        c.dataSource = context.coordinator
        return c
    }

    func updateUIViewController(_ c: QLPreviewController, context: Context) {
        guard context.coordinator.url != url else { return }
        context.coordinator.url = url
        c.reloadData()
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL

        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

/** The viewer over everything, for a picture or a file the terminal screen opened, with a way to close it. */
struct ViewerCover: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let showing: Showing
    let session: Session
    let onAdd: ((String, String) -> Void)?
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            FileViewer(viewed: showing.viewed, session: session, onAdd: showing.pane.flatMap { pane in onAdd.map { add in { add(pane, $0) } } })
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            haptics.tick()
                            onClose()
                        } label: {
                            Image(systemName: "xmark")
                        }
                    }
                }
                .toolbarBackground(palette.bg0, for: .navigationBar)
        }
    }
}
