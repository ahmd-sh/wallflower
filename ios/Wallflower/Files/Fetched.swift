import Foundation
import Observation
import SessionCore
import SwiftUI
import UniformTypeIdentifiers
import WallflowerDesign

/** What a file is, by its name, for how it's shown: its icon, and whether it's read, previewed or only shared. */
enum FileKind {
    case folder, picture, pdf, markdown, text, video, audio, document, archive, other

    init(name: String, folder: Bool = false) {
        if folder {
            self = .folder
            return
        }
        let ext = (name as NSString).pathExtension.lowercased()
        if ["md", "markdown", "mdx"].contains(ext) {
            self = .markdown
            return
        }
        if ext.isEmpty || Self.texts.contains(ext) {
            self = .text
            return
        }
        guard let type = UTType(filenameExtension: ext) else {
            self = .other
            return
        }
        switch true {
        case type.conforms(to: .image): self = .picture
        case type.conforms(to: .pdf): self = .pdf
        case type.conforms(to: .audiovisualContent) && !type.conforms(to: .audio): self = .video
        case type.conforms(to: .audio): self = .audio
        case type.conforms(to: .archive): self = .archive
        case type.conforms(to: .sourceCode), type.conforms(to: .plainText), type.conforms(to: .json), type.conforms(to: .xml), type.conforms(to: .propertyList):
            self = .text
        case type.conforms(to: .presentation), type.conforms(to: .spreadsheet), type.conforms(to: .rtf), type.conforms(to: .html),
             type.conforms(to: .threeDContent), type.identifier.contains("wordprocessingml"), type.identifier == "com.microsoft.word.doc":
            self = .document
        default: self = .other
        }
    }

    /** Read in the app as words, the start of a big one only: what's code or plain text, and a name with no extension (Makefile, LICENSE). */
    var read: Bool { self == .text || self == .markdown }

    /** Shown by QuickLook, whole. */
    var previewed: Bool { [.picture, .pdf, .video, .audio, .document].contains(self) }

    /** Its icon, in the mono font's symbols. */
    var icon: String {
        switch self {
        case .folder: return "\u{f07b}"
        case .picture: return "\u{f1c5}"
        case .pdf: return "\u{f1c1}"
        case .markdown: return "\u{f48a}"
        case .text: return "\u{f15c}"
        case .video: return "\u{f1c8}"
        case .audio: return "\u{f1c7}"
        case .document: return "\u{f15b}"
        case .archive: return "\u{f1c6}"
        case .other: return "\u{f15b}"
        }
    }

    func color(_ p: Colors) -> Color {
        switch self {
        case .folder: return p.blue
        case .picture: return p.purple
        case .pdf: return p.red
        case .markdown, .text: return p.aqua
        case .video, .audio: return p.yellow
        case .archive: return p.orange
        case .document, .other: return p.grey
        }
    }

    private static let texts: Set<String> = [
        "txt", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "lock", "csv", "tsv", "xml", "plist",
        "swift", "kt", "kts", "java", "go", "mod", "sum", "py", "rb", "rs", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp", "m", "mm",
        "cs", "php", "lua", "sh", "bash", "zsh", "fish", "sql", "css", "scss", "less", "vue", "svelte", "gradle", "properties", "proto",
        "graphql", "tf", "nix", "el", "vim", "diff", "patch", "gitignore", "gitattributes", "editorconfig", "dockerfile", "makefile", "cmake", "zig", "ex", "exs", "erl", "hs", "ml", "r", "jl", "dart", "scala", "clj",
    ]
}

/**
 * Where the computer's files land on the phone: a folder in the app's caches for
 * each fetch, the file named as it is, so it's shared by its name. One had
 * already this run, the same size and age, is had again rather than fetched.
 */
@MainActor
enum Fetched {
    static let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("fetched")
    private static var kept: [String: URL] = [:]

    /** What earlier runs fetched goes, at launch. */
    static func clear() {
        let old = root
        Task.detached(priority: .background) { try? FileManager.default.removeItem(at: old) }
    }

    /** A new place for a file named [name]. */
    static func place(_ name: String) -> URL {
        let dir = root.appendingPathComponent(String(UUID().uuidString.prefix(8)))
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name.isEmpty ? "file" : name)
    }

    /** The file at [path] on the computer as fetched this run, all of it or its start ([limit]), if its size and age say it's the same. */
    static func kept(_ path: String, size: Int64?, mtime: Int64?, limit: Int64? = nil) -> URL? {
        guard size != nil || mtime != nil, let url = kept[key(path, size, mtime, limit)], FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    static func keep(_ url: URL, _ path: String, size: Int64?, mtime: Int64?, limit: Int64? = nil) {
        guard size != nil || mtime != nil else { return }
        kept[key(path, size, mtime, limit)] = url
    }

    private static func key(_ path: String, _ size: Int64?, _ mtime: Int64?, _ limit: Int64?) -> String {
        "\(path)|\(size ?? -1)|\(mtime ?? -1)|\(limit ?? 0)"
    }
}

/** How much of a text file is fetched to read: the start of a big one. */
let readLimit: Int64 = 256 << 10

/**
 * Shares the computer's files through the share sheet: fetched whole first if
 * they aren't yet, saying how far along ([fetching]), then offered to other
 * apps ([shared]).
 */
@MainActor
@Observable
final class Sharer {
    struct Fetching: Equatable {
        let name: String
        var got: Int64 = 0
        var of: Int64 = 0
    }

    struct Shared: Identifiable {
        let id = UUID()
        let url: URL
    }

    var fetching: Fetching?
    var shared: Shared?
    var failed: String?
    @ObservationIgnored private var task: Task<Void, Never>?
    /** Which share the state is the latest's: one replaced finishes after, and mustn't clear the new one's. */
    @ObservationIgnored private var latest = UUID()

    /** Shares a file already on the phone. */
    func share(_ url: URL) {
        shared = Shared(url: url)
    }

    /** Shares the file at [path] on the computer, fetching it whole if this run hasn't. */
    func share(_ path: String, size: Int64?, mtime: Int64?, session: Session, haptics: Haptics) {
        if let url = Fetched.kept(path, size: size, mtime: mtime) { return share(url) }
        let name = (path as NSString).lastPathComponent
        task?.cancel()
        failed = nil
        fetching = Fetching(name: name, of: size ?? 0)
        let mine = UUID()
        latest = mine
        task = Task { [weak self] in
            let url = Fetched.place(name)
            do {
                _ = try await session.fetch(path, to: url) { got, of in
                    guard self?.latest == mine else { return }
                    self?.fetching?.got = got
                    self?.fetching?.of = of
                }
                Fetched.keep(url, path, size: size, mtime: mtime)
                guard self?.latest == mine else { return }
                self?.fetching = nil
                self?.share(url)
            } catch is CancellationError {
                if self?.latest == mine { self?.fetching = nil }
            } catch {
                guard self?.latest == mine else { return }
                self?.fetching = nil
                self?.failed = error.localizedDescription
                haptics.reject()
            }
        }
    }

    func cancel() {
        task?.cancel()
        fetching = nil
    }
}

/** The system's share sheet, for a file. */
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ c: UIActivityViewController, context: Context) {}
}

extension View {
    /** Offers what [sharer] has to share in the share sheet, and how far along a fetch for it is. */
    func sharing(_ sharer: Sharer) -> some View {
        modifier(Sharing(sharer: sharer))
    }
}

private struct Sharing: ViewModifier {
    @Environment(\.palette) private var palette
    @Bindable var sharer: Sharer

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let f = sharer.fetching {
                    HStack(spacing: 10) {
                        ProgressView(value: f.of > 0 ? Double(f.got) / Double(f.of) : 0).progressViewStyle(.circular).tint(palette.fg)
                        Text("fetching \(f.name)…").words(.label).foregroundColor(palette.fg).lineLimit(1).truncationMode(.middle)
                        Button("cancel") { sharer.cancel() }.words(.label).foregroundColor(palette.orange)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .chrome()
                    .padding(.bottom, 16)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                } else if let e = sharer.failed {
                    Text(e).words(.label).foregroundColor(palette.red).padding(.horizontal, 14).padding(.vertical, 10).chrome().padding(.bottom, 16)
                        .onTapGesture { sharer.failed = nil }
                        .task {
                            try? await Task.sleep(for: .seconds(4))
                            sharer.failed = nil
                        }
                }
            }
            .animation(Motion.spatial, value: sharer.fetching)
            .sheet(item: $sharer.shared) { s in
                ShareSheet(url: s.url).presentationDetents([.medium, .large]).ignoresSafeArea()
            }
    }
}

/** A path as it goes into a message: as it is, or in single quotes if a shell or an agent would take it apart. */
func messagePath(_ path: String) -> String {
    let plain = path.unicodeScalars.allSatisfy { c in
        CharacterSet.alphanumerics.contains(c) && c.isASCII || "._/~+@%:,=-".unicodeScalars.contains(c)
    }
    return plain ? path : "'" + path.replacingOccurrences(of: "'", with: #"'\''"#) + "'"
}

/** A size, as the Files app says one: 12 KB, 3.4 MB. */
func sizeText(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

/** When something last changed, in Unix milliseconds, as how long ago. */
func agoText(_ mtime: Int64) -> String {
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .short
    return f.localizedString(for: Date(timeIntervalSince1970: Double(mtime) / 1000), relativeTo: Date())
}

/** A path's last part, as a folder is called: / for the root, ~ for a home. */
func folderName(_ path: String) -> String {
    if path == "/" || path.isEmpty { return "/" }
    if isHome(path) { return "~" }
    return (path as NSString).lastPathComponent
}

/** Whether [path] is a user's home on the computer (/Users/me), as near as the phone can tell. */
func isHome(_ path: String) -> Bool {
    let parts = path.split(separator: "/")
    return parts.count == 2 && (parts[0] == "Users" || parts[0] == "home") && path.hasPrefix("/")
}
