import CryptoKit
import Foundation
import Observation
import SessionCore
import UIKit

/** Something picked to send to the computer: its name, and its bytes. */
struct Picked {
    let name: String
    let data: Data
    let image: Bool
}

/**
 * Files on their way to the computer, each for a pane. Once the gateway says
 * where it saved one, its path goes into that pane's draft, as a file
 * dropped on a terminal does: from then on the draft is the attachment,
 * kept and sent like the rest of it. An image leaves a small preview behind,
 * by its path, for the draft to show.
 */
@MainActor
@Observable
final class Uploads {
    struct Item: Identifiable, Equatable {
        let id: String
        let pane: String
        let name: String
        let size: Int
        var sent = 0
        var error: String?
        var thumbnail: UIImage?
    }

    private(set) var items: [Item] = []
    /** Where the gateway saves uploads, as it last said, so drafts can show theirs even offline. */
    var dir: String? = UserDefaults.standard.string(forKey: "uploads") {
        didSet { if let d = dir, d != oldValue { UserDefaults.standard.set(d, forKey: "uploads") } }
    }
    @ObservationIgnored private let cache = NSCache<NSString, UIImage>()
    @ObservationIgnored private let thumbs = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("thumbs")

    func add(_ session: Session, pane: String, _ picked: [Picked], drafts: Drafts) {
        for p in picked {
            let item = Item(id: String(UUID().uuidString.prefix(8)).lowercased(), pane: pane, name: p.name, size: p.data.count, thumbnail: p.image ? UIImage(data: p.data)?.preparingThumbnail(of: CGSize(width: 192, height: 192)) : nil)
            items.append(item)
            Task {
                do {
                    let path = try await session.upload(id: item.id, name: p.name, data: p.data) { [weak self] sent in
                        self?.change(item.id) { $0.sent = sent }
                    }
                    if let t = item.thumbnail { keep(t, path) }
                    drafts.insert(pane, path)
                    dismiss(item.id)
                } catch {
                    change(item.id) { $0.error = error.localizedDescription }
                }
            }
        }
    }

    func dismiss(_ id: String) { items.removeAll { $0.id == id } }

    private func change(_ id: String, _ f: (inout Item) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        f(&items[i])
    }

    /** The preview kept for an uploaded image, by its path on the computer, if there is one. */
    func thumbnail(_ path: String) -> UIImage? {
        if let image = cache.object(forKey: path as NSString) { return image }
        guard let image = UIImage(contentsOfFile: thumbs.appendingPathComponent(key(path)).path) else { return nil }
        cache.setObject(image, forKey: path as NSString)
        return image
    }

    private func keep(_ image: UIImage, _ path: String) {
        cache.setObject(image, forKey: path as NSString)
        try? FileManager.default.createDirectory(at: thumbs, withIntermediateDirectories: true)
        try? image.jpegData(compressionQuality: 0.85)?.write(to: thumbs.appendingPathComponent(key(path)))
    }

    private func key(_ path: String) -> String {
        Insecure.SHA1.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined() + ".jpg"
    }
}

/** Upload paths: under [dir], names of the characters the gateway keeps, which never end in . or -. */
func uploadPaths(_ dir: String) -> NSRegularExpression? {
    try? NSRegularExpression(pattern: NSRegularExpression.escapedPattern(for: dir) + "/[A-Za-z0-9._-]*[A-Za-z0-9_]")
}
