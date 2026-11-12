import ReadModel
import SwiftUI
import WallflowerDesign

private let pictureHeight: CGFloat = 260

/** Something the user said: tapped, it opens if it's cut short; held, it offers to [onEdit] it and send it again. */
struct UserPrompt: View {
    let text: String
    let onEdit: (() -> Void)?
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @State private var open = false
    @State private var full: CGFloat = 0
    @State private var cap: CGFloat = 0
    @State private var copier = Copier()

    private static let shownLines = 12

    var body: some View {
        let size = look.size(mono: 14, sans: 15)
        let spacing = Leading.words(look, size, look.size(mono: 21, sans: 22))
        let cut = cap > 0 && full > cap + 0.5
        VStack(alignment: .trailing, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(text)
                    .font(look.words(size))
                    .lineSpacing(spacing)
                    .foregroundColor(palette.fg)
                    .fixedSize(horizontal: false, vertical: true)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { full = $0 }
                    .frame(height: open || !cut ? nil : cap, alignment: .top)
                    .clipped()
                // What's cut off is said on a line of its own, as something to open.
                if cut && !open {
                    Text("…")
                        .font(look.words(20, weight: .bold))
                        .foregroundColor(palette.blue)
                        .frame(height: 16)
                }
            }
            .background(alignment: .topLeading) {
                Text(Array(repeating: "|", count: Self.shownLines).joined(separator: "\n"))
                    .font(look.words(size))
                    .lineSpacing(spacing)
                    .fixedSize()
                    .hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cap = $0 }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.bg2)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 18))
            .onTapGesture { withAnimation(Motion.spatial) { open.toggle() } }
            .contextMenu {
                if let onEdit { Button("edit", action: onEdit) }
                Button("copy") { copier.copy(text, haptics) }
            }
            CopyIcon { text }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }
}

struct Reply: View {
    let text: String
    let copyable: Bool
    let beside: Footer?
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var copier = Copier()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Held, words select, to copy them.
            MarkdownText(text)
            if copyable {
                HStack(spacing: 0) {
                    Button { copier.copy(text, haptics) } label: {
                        ZStack(alignment: .leading) {
                            Text("copy").opacity(copier.copied ? 0 : 1)
                            Text("copied").opacity(copier.copied ? 1 : 0)
                        }
                        .words(.caption)
                        .foregroundColor(palette.dim)
                        .padding(.vertical, 4)
                        .padding(.horizontal, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(Pressable())
                    .animation(Motion.effect, value: copier.copied)
                    Spacer(minLength: 0)
                    if let beside { FooterView(footer: beside) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/** A small copy button, ticked a moment after; [text] is what it copies, made when tapped. */
struct CopyIcon: View {
    let text: () -> String
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var copier = Copier()

    var body: some View {
        Button { copier.copy(text(), haptics) } label: {
            ZStack {
                GlyphIcon(.copy, palette.dim, size: 13).opacity(copier.copied ? 0 : 1)
                GlyphIcon(.check, palette.dim, size: 13).opacity(copier.copied ? 1 : 0)
            }
            .padding(6)
            .contentShape(Circle())
        }
        .buttonStyle(Pressable())
        .animation(Motion.effect, value: copier.copied)
    }
}

/** A picture, shown at about the size it was taken; tapped, it opens in the viewer, to zoom and share. */
struct Picture: View {
    let e: Entry.Picture
    let fetch: (String) async -> Data?
    @Environment(\.displayScale) private var scale
    @Environment(\.viewing) private var viewing
    @Environment(\.haptics) private var haptics
    @State private var image: UIImage?
    @State private var room: CGFloat = 0

    init(e: Entry.Picture, fetch: @escaping (String) async -> Data?) {
        self.e = e
        self.fetch = fetch
        _image = State(initialValue: Pictures.kept(e.id))
    }

    private struct Load: Equatable {
        let id: String
        let ready: Bool
    }

    var body: some View {
        HStack(spacing: 0) {
            if e.by == .user { Spacer(minLength: 0) }
            if let image, room > 0 {
                // At its own size, or smaller to fit the width and the height it's allowed.
                let natural = CGSize(width: image.size.width / scale, height: image.size.height / scale)
                let k = min(1, room / max(natural.width, 1), pictureHeight / max(natural.height, 1))
                Image(uiImage: image)
                    .resizable()
                    .frame(width: natural.width * k, height: natural.height * k)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(RoundedRectangle(cornerRadius: 12))
                    .onTapGesture {
                        guard let open = viewing.picture else { return }
                        haptics.tick()
                        open(e.id, e.mime)
                    }
                    .transition(.opacity)
            }
            if e.by != .user { Spacer(minLength: 0) }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { room = $0 }
        .task(id: Load(id: e.id, ready: room > 0)) {
            guard image == nil, room > 0, let data = await fetch(e.id) else { return }
            let decoded = await Pictures.decode(e.id, data, width: Int(room * scale), height: Int(pictureHeight * scale))
            withAnimation(Motion.effect) { image = decoded }
        }
    }
}

/** Something the user sent that waits for the program to take it: theirs, faint, said to be waiting. */
struct Queued: View {
    let text: String
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    var body: some View {
        let size = look.size(mono: 14, sans: 15)
        VStack(alignment: .trailing, spacing: 4) {
            Text(text)
                .font(look.words(size))
                .lineSpacing(Leading.words(look, size, look.size(mono: 21, sans: 22)))
                .foregroundColor(palette.grey)
                .lineLimit(4)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(palette.bg3, lineWidth: 1))
            Text("queued")
                .words(.caption)
                .foregroundColor(palette.dim)
                .padding(.trailing, 6)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }
}
