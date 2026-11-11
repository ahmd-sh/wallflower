import SwiftUI

/**
 * How things move: springs, so a touch can interrupt an animation midway
 * and it carries on from where it is, never blocking what the touch does.
 * Things that move or change size use [spatial]; fades and colors the
 * quicker [effect].
 */
public enum Motion {
    public static let spatial = Animation.spring(response: 0.42, dampingFraction: 1)
    public static let effect = Animation.spring(response: 0.28, dampingFraction: 1)
    /** Something that arrives with a little give: past where it stops, and back. */
    public static let bounce = Animation.spring(response: 0.35, dampingFraction: 0.6)
}
