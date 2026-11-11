import Foundation
import Observation

/**
 * In development, steps given on the command line (`-wfScript
 * "tab:1;type:ls\r;settings"`) played one after another, so the app can be
 * walked through without touching it: screens look for the step that's
 * theirs.
 */
@MainActor
@Observable
final class Rehearsal {
    private(set) var step: String?
    private(set) var count = 0

    init() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-wfScript"), i + 1 < args.count else { return }
        let steps = args[i + 1].split(separator: ";").map(String.init)
        Task {
            try? await Task.sleep(for: .seconds(3))
            for s in steps {
                if s.hasPrefix("wait:"), let t = Double(s.dropFirst(5)) {
                    try? await Task.sleep(for: .seconds(t))
                    continue
                }
                step = s.replacingOccurrences(of: "\\r", with: "\r")
                count += 1
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
        #endif
    }

    /** The step, if it's [name] (and what follows its colon). */
    func arg(_ name: String) -> String? {
        guard let s = step else { return nil }
        if s == name { return "" }
        return s.hasPrefix(name + ":") ? String(s.dropFirst(name.count + 1)) : nil
    }

    static var dev: Bool { ProcessInfo.processInfo.arguments.contains("-wfDev") }
}
