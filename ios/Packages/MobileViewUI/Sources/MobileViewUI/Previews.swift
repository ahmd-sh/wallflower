import ReadModel
import ScreenReading
import SwiftUI
import WallflowerDesign

private enum Sample {
    static let reply = """
        ## What changed

        The pager now **settles** on the nearest pane, carrying the fling's _speed_, and `Swipe` reports in points.

        ```swift
        func end(_ dx: CGFloat, velocity: CGFloat) {
            let target = (offset + velocity * 0.2) / width
            settle(on: target.rounded())
        }
        ```

        | file | lines | note |
        | --- | --- | --- |
        | `PanePager.swift` | +42 −8 | the spring |
        | `Swipe.swift` | +12 | ~~velocity~~ points |

        1. Build it
        2. Try it on the phone
           - fling hard
           - fling soft

        > Panes wrap around, so the last swipes to the first.

        See [the notes](https://example.com/notes).
        """

    static let diff = """
        @@ -10,4 +10,5 @@
         func settle(on pane: Int) {
        -    offset = 0
        +    withAnimation(Motion.spatial) { offset = 0 }
        +    shown = pane
         }
        """

    static let done: [Entry] = [
        .said(.init(id: "1", by: .user, text: "make the pager settle with the fling, and tell me what changed")),
        .thought(.init(id: "2", text: "The pager needs the velocity at release, then a spring toward the nearest pane.")),
        .step(.init(id: "3", name: "Read", input: "ios/Wallflower/PanePager.swift", output: "120 lines", status: .done)),
        .step(.init(id: "4", name: "Edit", input: "ios/Wallflower/PanePager.swift", diff: diff, status: .done)),
        .step(.init(id: "5", name: "Bash", input: "xcodebuild -scheme Wallflower build", output: "error: no such scheme", status: .failed)),
        .said(.init(id: "6", by: .program, text: reply)),
        .note(.init(id: "7", text: "conversation compacted", detail: "The user asked for the pager to settle with the fling; it does now.")),
    ]

    static let running: [Entry] = done + [
        .said(.init(id: "8", by: .user, text: "now run the tests")),
        .step(.init(id: "9", name: "Bash", input: "swift test --package-path ios/Packages/ReadModel")),
    ]

    static let prompt = Prompt(
        lines: [
            .init(text: "Bash command", code: false),
            .init(text: "swift test --package-path ios/Packages/ReadModel", code: true),
            .init(text: "Do you want to proceed?", code: false),
        ],
        options: [
            .init(number: 1, label: "Yes", detail: "", checked: nil, current: true, muted: false),
            .init(number: 2, label: "Yes, and don't ask again for swift test", detail: "in this project", checked: nil, current: false, muted: false),
            .init(number: 3, label: "No, and tell Claude what to do differently", detail: "", checked: nil, current: false, muted: false),
        ],
        tabs: []
    )

    static let since = Int64(Date().timeIntervalSince1970 * 1_000) - 74_000
}

#Preview("reply, code and a table") {
    let _ = Fonts.register()
    MobileView(
        reading: Reading(entries: Sample.done, loaded: true, context: 48_200),
        activity: .idle,
        since: 0,
        picture: { _ in nil },
        onCompact: {},
        onEdit: { _, _ in },
        title: "pager settles with the fling"
    )
}

#Preview("a running step") {
    let _ = Fonts.register()
    MobileView(
        reading: Reading(entries: Sample.running, loaded: true, context: 51_000, queued: ["and commit it"]),
        activity: .working,
        since: Sample.since,
        picture: { _ in nil },
        doing: "Running tests"
    )
}

#Preview("a prompt card") {
    let _ = Fonts.register()
    MobileView(
        reading: Reading(entries: Sample.running, loaded: true),
        activity: .waiting,
        since: Sample.since,
        picture: { _ in nil },
        prompt: Sample.prompt
    )
}

#Preview("card alone") {
    let _ = Fonts.register()
    PromptCard(prompt: Sample.prompt, onKeys: { _ in }, nudged: 1)
        .padding(16)
        .frame(maxHeight: .infinity)
        .background(Themes.everforestDarkHard.bg0)
}

#Preview("empty") {
    let _ = Fonts.register()
    MobileView(reading: Reading(loaded: true), activity: nil, since: 0, picture: { _ in nil })
}
