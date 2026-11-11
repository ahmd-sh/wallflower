import SwiftUI
import UIKit

/**
 * Sideways drags, to move between panes: how far, as they go, then how far
 * and how fast (pt/s) when let go. Only a drag that starts out clearly
 * sideways is one; anything else is the list's to scroll, and a block
 * inside that scrolls sideways itself gets first say.
 */
struct Swipe: UIGestureRecognizerRepresentable {
    let onDrag: ((CGFloat) -> Void)?
    let onEnd: ((CGFloat, CGFloat) -> Void)?

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        pan.maximumNumberOfTouches = 1
        return pan
    }

    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        recognizer.isEnabled = onDrag != nil || onEnd != nil
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let dx = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .began, .changed: onDrag?(dx)
        case .ended: onEnd?(dx, recognizer.velocity(in: recognizer.view).x)
        case .cancelled, .failed: onEnd?(dx, 0)
        default: break
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            let v = pan.velocity(in: pan.view)
            let t = pan.translation(in: pan.view)
            let x = abs(v.x) + abs(t.x)
            let y = abs(v.y) + abs(t.y)
            return x > y * 2
        }

        /** A block that scrolls sideways (code, a table, a diff) scrolls first; only if it won't is the drag a swipe. */
        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
            guard let scroll = other.view as? UIScrollView, other === scroll.panGestureRecognizer,
                  let mine = recognizer.view, scroll.isDescendant(of: mine) else { return false }
            return scroll.contentSize.width > scroll.bounds.width + 1 && scroll.bounds.contains(other.location(in: scroll))
        }
    }
}
