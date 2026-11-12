import MarkdownKit
import ReadModel
import SwiftUI
import TerminalCore
import WallflowerDesign
import Wire

extension Pane: @retroactive HeaderPane {}

/**
 * Every pane of a window as a card with its live contents, in the session's
 * order, then one to open a new pane. A card is headed by an icon for what
 * the pane runs and the directory it's in. Tap a card to show it, × to
 * close it; held, it offers the files where the pane is. Cards fill as many
 * columns as the screen is wide.
 */
struct PaneOverview: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let window: MuxWindow
    let shown: String?
    let terminals: [String: Terminal]
    let onPick: (Pane) -> Void
    let onNew: () -> Void
    let onClose: (Pane) -> Void
    var onFiles: ((Pane) -> Void)? = nil

    var body: some View {
        let panes = window.panes.sorted { $0.index < $1.index }
        let names = headerNames(panes)
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 10)], spacing: 10) {
                ForEach(panes, id: \.id) { p in card(p, name: names[p.id] ?? p.command) }
                newCard
            }
            .padding(12)
        }
        .background(palette.bg0)
    }

    private func card(_ p: Pane, name: String) -> some View {
        let on = p.id == shown
        let own = name.split(separator: "/").last.map(String.init) ?? name
        let above = name.contains("/") && name.count > 1 ? String(name[..<(name.lastIndex(of: "/") ?? name.startIndex)]) : ""
        return VStack(spacing: 0) {
            HStack(spacing: 6) {
                GlyphIcon(glyphOf(program: p.program, command: p.command), on ? palette.orange : palette.grey, size: 14)
                Text(own).font(look.font(.label)).foregroundColor(palette.fg).lineLimit(1)
                if !above.isEmpty { Text(above.isEmpty ? "/" : above).font(look.font(.label)).foregroundColor(palette.dim).lineLimit(1).truncationMode(.head) }
                Spacer(minLength: 0)
                if let a = activityOf(p), a != .idle { AgentDot(activity: a) }
                Button {
                    onClose(p)
                } label: {
                    GlyphIcon(.close, palette.grey, size: 14).padding(10).contentShape(Circle())
                }
                .buttonStyle(Pressable())
            }
            .padding(.leading, 12)
            // The pane as its own terminal draws it, shrunk to the card's width.
            TerminalHost(terminal: terminals[p.id], theme: palette.terminal, paused: false, current: false, typing: false, handle: TerminalHandle(), swipe: nil, onGridSize: { _, _ in }, onAppScroll: { _, _ in }, onTyped: { _ in }, onTypingChanged: { _ in }, touchable: false)
                .padding(4)
                .aspectRatio(0.75, contentMode: .fit)
                .background(palette.bg0)
        }
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg1))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(on ? palette.orange : palette.bg1, lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture {
            haptics.tick()
            onPick(p)
        }
        .contextMenu {
            if let onFiles { Button("files here") { onFiles(p) } }
            Button("close", role: .destructive) { onClose(p) }
        }
    }

    private var newCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("new pane").font(look.font(.label)).foregroundColor(palette.grey).padding(.horizontal, 12).padding(.vertical, 10)
            Color.clear
                .aspectRatio(0.75, contentMode: .fit)
                .overlay { GlyphIcon(.plus, palette.grey, size: 28) }
        }
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg1))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture {
            haptics.tick()
            onNew()
        }
    }
}

/**
 * The icon for what a pane runs: by its [program], if that's one wallflower
 * ships or its id names a program below, else by its [command]'s name; a
 * shell's if neither says.
 */
func glyphOf(program: String?, command: String) -> Glyph {
    if let p = program?.lowercased() { if let g = programGlyphs[p] ?? glyphNamed(p) { return g } }
    return glyphNamed(command) ?? .terminal
}

/** The icon for a command by its name, without a path or a version on the end (python3.12); nil if it isn't one below. */
private func glyphNamed(_ command: String) -> Glyph? {
    let name = (command.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? "").split(separator: "/").last.map { $0.lowercased() } ?? ""
    return commandGlyphs[name] ?? commandGlyphs[String(name.reversed().drop { $0.isNumber || $0 == "." }.reversed())]
}

private let programGlyphs: [String: Glyph] = ["claude": .spark, "zsh": .terminal, "shell": .terminal]

/** Programs' icons, by the names their commands go by. */
private let commandGlyphs: [String: Glyph] = {
    let groups: [(Glyph, String)] = [
        (.spark, "claude codex opencode aider gemini goose crush amp copilot cursor-agent"),
        (.edit, "nvim vim vi view vimdiff hx helix emacs emacsclient nano pico micro kak"),
        (.hexagon, "node npm npx pnpm pnpx yarn bun bunx deno vite next next-server nodemon tsx ts-node tsc turbo nx expo vitest jest"),
        (.snake, "python ipython bpython ptpython uv uvx pip pipx poetry pdm hatch conda mamba jupyter pytest"),
        (.branch, "git lazygit tig gitui gitu gh jj jjui lazyjj"),
        (.pulse, "htop btop top atop bpytop gotop btm glances nvtop"),
        (.globe, "ssh mosh mosh-client et autossh telnet"),
        (.boxes, "docker docker-compose podman lazydocker kubectl k9s helm ctop"),
        (.folder, "yazi ranger lf nnn vifm mc broot xplr"),
        (.database, "psql mysql sqlite3 redis-cli mongosh mongo pgcli mycli litecli duckdb usql"),
        (.book, "less more most man bat glow tldr info"),
        (.hammer, "make gmake cmake ninja cargo rustc go gradle mvn sbt mill bazel xcodebuild swift zig gcc clang just java"),
    ]
    var out: [String: Glyph] = [:]
    for (g, names) in groups { for n in names.split(separator: " ") { out[String(n)] = g } }
    return out
}()
