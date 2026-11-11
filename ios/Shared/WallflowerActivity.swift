import ActivityKit
import Foundation

/**
 * A program at work on the Mac, as the Lock Screen and the Dynamic Island
 * show it: what it's on, and whether it's working (since when) or waiting
 * for the user. One activity for the connection; it ends when nothing's at
 * work for a while, or the user disconnects.
 */
struct WallflowerActivity: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /** What the program calls what it's on, or its window's name. */
        var about: String
        /** Waiting for the user to answer, rather than working. */
        var waiting: Bool
        /** When it started working; nil if it doesn't say. */
        var since: Date?
        /** How many more programs are at work besides. */
        var others: Int
        /** Done: it finished its turn, shown a moment before the activity ends. */
        var done: Bool
        /** The connection's down, so this may be out of date. */
        var stale: Bool
    }

    /** The Mac's name. */
    var machine: String
}
