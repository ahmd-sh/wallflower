import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * The program's own list of its tasks, in a mobile view's place while it
 * shows it: its filters to pick from, its tasks in their groups to open
 * one, the one under its cursor lit, and a way back and to a new one. What's
 * out of sight above or below is a page away. Something sent while it's up
 * waits, as what's typed would go to it: it says so a moment ([nudged]). Each is keys to the program
 * ([onKeys]), which moves its own cursor; what it shows next says where it went.
 */
struct TasksPage: View {
    let tasks: Aside.Tasks
    let onKeys: ([String]) -> Void
    let nudged: Int
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var held = false
    @State private var holds = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(tasks.named)
                .words(.title)
                .foregroundColor(palette.fg)
                .padding(.leading, 20)
                .padding(.trailing, 120) // clear of the pane's controls
                .padding(.top, 14)
            if held {
                Text("go back first, then send")
                    .words(.caption)
                    .foregroundColor(palette.orange)
                    .padding(.leading, 20)
                    .padding(.top, 2)
                    .transition(.opacity)
            }
            Flow(spacing: 6) {
                ForEach(Array(tasks.filters.enumerated()), id: \.offset) { i, f in
                    Button { send(tasks.showing(i)) } label: {
                        Text("\(f.name.lowercased()) \(f.count)")
                            .words(.label)
                            .foregroundColor(f.current ? palette.fg : palette.grey)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(f.current ? palette.bg3 : palette.bg1))
                    }
                    .buttonStyle(Pressable())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    if tasks.above { more("more above") { send(tasks.pageUp) } }
                    if let says = tasks.says {
                        Text(says.lowercased())
                            .words(.body)
                            .foregroundColor(palette.grey)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 16)
                    }
                    ForEach(lines) { line in
                        switch line {
                        case .heading(let g, let text):
                            Text(text)
                                .words(.caption)
                                .foregroundColor(palette.dim)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .padding(.horizontal, 8)
                                .padding(.top, g == 0 ? 4 : 14)
                                .padding(.bottom, 4)
                        case .task(let i, let task):
                            row(task) { send(tasks.opening(i)) }
                        }
                    }
                    if tasks.below { more("more below") { send(tasks.pageDown) } }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
            }
            HStack(spacing: 8) {
                action("back") { send(tasks.leave) }
                action("new task") { send(tasks.new) }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.bg0)
        .onChange(of: nudged) { holds += 1 } // sent while it's up, not before it came
        .task(id: holds) {
            guard holds > 0 else { return }
            withAnimation(Motion.effect) { held = true }
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { withAnimation(Motion.effect) { held = false } }
        }
    }

    /** A group's heading, or a task, by where it is among them all: what the list shows, in order. */
    private enum Line: Identifiable {
        case heading(Int, String)
        case task(Int, Aside.Tasks.Task)

        var id: String {
            switch self {
            case .heading(let g, _): "group \(g)"
            case .task(let i, _): "task \(i)"
            }
        }
    }

    private var lines: [Line] {
        var out: [Line] = []
        var at = 0
        for (g, group) in tasks.groups.enumerated() {
            if !group.name.isEmpty { out.append(.heading(g, group.count.isEmpty ? group.name : "\(group.name) · \(group.count)")) }
            for task in group.tasks {
                out.append(.task(at, task))
                at += 1
            }
        }
        return out
    }

    private func send(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        haptics.tick()
        onKeys(keys)
    }

    /** A task: its mark, then its name; lit under the program's cursor. */
    private func row(_ task: Aside.Tasks.Task, onOpen: @escaping () -> Void) -> some View {
        Button(action: onOpen) {
            HStack(spacing: 10) {
                mark(task.mark)
                Text(task.name)
                    .words(.body)
                    .foregroundColor(palette.fg)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 11)
            .background(RoundedRectangle(cornerRadius: 12).fill(task.current ? palette.bg2 : palette.bg0))
            .contentShape(Rectangle())
        }
        .buttonStyle(Pressable())
    }

    /** What a task's mark says, in the app's colors: needing the user orange, working purple, something new blue; either of those last, the words' color; nothing, a ring. */
    @ViewBuilder
    private func mark(_ mark: Aside.Tasks.Mark) -> some View {
        switch mark {
        case .needsYou: Circle().fill(palette.orange).frame(width: 8, height: 8)
        case .working: Circle().fill(palette.purple).frame(width: 8, height: 8)
        case .updated: Circle().fill(palette.blue).frame(width: 8, height: 8)
        case .lit: Circle().fill(palette.fg).frame(width: 8, height: 8)
        case .idle: Circle().strokeBorder(palette.dim, lineWidth: 1).frame(width: 8, height: 8)
        }
    }

    private func more(_ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .words(.label)
                .foregroundColor(palette.blue)
                .padding(10)
        }
        .buttonStyle(Pressable())
    }

    private func action(_ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .words(.label)
                .foregroundColor(palette.fg)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg1))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(palette.border, lineWidth: 1))
        }
        .buttonStyle(Pressable())
    }
}
