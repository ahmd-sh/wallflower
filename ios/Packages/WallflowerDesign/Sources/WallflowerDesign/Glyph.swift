import SwiftUI

/** wallflower's icons; from [escape] on, keys'; from [hexagon] on, programs' (with [terminal], [spark] and [edit]: see glyphOf). */
public enum Glyph: String, CaseIterable {
    case plus, close, chat, terminal, spark, copy, check, edit
    case escape, tab, backTab, enter, up, down, left, right, backspace, space, ctrl, alt, shift, arrows, bolt
    case hexagon, snake, branch, pulse, globe, boxes, folder, database, book, hammer
}

/** Line icons, drawn to match the send button rather than pulled from an icon set. */
public struct GlyphIcon: View {
    public let glyph: Glyph
    public var color: Color
    public var size: CGFloat = 16

    public init(_ glyph: Glyph, _ color: Color, size: CGFloat = 16) {
        self.glyph = glyph
        self.color = color
        self.size = size
    }

    public var body: some View {
        Canvas { ctx, sz in GlyphIcon.draw(glyph, in: ctx, size: sz, color: color) }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }

    public static func draw(_ glyph: Glyph, in context: GraphicsContext, size: CGSize, color: Color) {
        let w: CGFloat = 1.8
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let r = min(size.width, size.height) / 2 - w
        let line = StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round)
        let shade = GraphicsContext.Shading.color(color)
        func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * r, y: c.y + y * r) }
        func stroke(_ ctx: GraphicsContext, _ points: [(CGFloat, CGFloat)], closed: Bool = false) {
            var p = Path()
            for (i, (x, y)) in points.enumerated() {
                if i == 0 { p.move(to: at(x, y)) } else { p.addLine(to: at(x, y)) }
            }
            if closed { p.closeSubpath() }
            ctx.stroke(p, with: shade, style: line)
        }
        func lineFrom(_ ctx: GraphicsContext, _ a: CGPoint, _ b: CGPoint) {
            var p = Path()
            p.move(to: a)
            p.addLine(to: b)
            ctx.stroke(p, with: shade, style: line)
        }
        func curve(_ ctx: GraphicsContext, _ points: [(CGFloat, CGFloat)]) {
            let pts = points.map { at($0.0, $0.1) }
            var p = Path()
            p.move(to: pts[0])
            var i = 1
            while i + 2 < pts.count {
                p.addCurve(to: pts[i + 2], control1: pts[i], control2: pts[i + 1])
                i += 3
            }
            ctx.stroke(p, with: shade, style: line)
        }
        /** An arc of the oval in [rect], from [start] degrees clockwise from three o'clock, through [sweep]. */
        func arc(_ rect: CGRect, _ start: Double, _ sweep: Double) -> Path {
            var unit = Path()
            unit.addArc(center: .zero, radius: 1, startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: sweep < 0)
            return unit.applying(CGAffineTransform(translationX: rect.midX, y: rect.midY).scaledBy(x: rect.width / 2, y: rect.height / 2))
        }
        func rotated(_ degrees: Double, _ body: (GraphicsContext) -> Void) {
            var g = context
            g.translateBy(x: c.x, y: c.y)
            g.rotate(by: .degrees(degrees))
            g.translateBy(x: -c.x, y: -c.y)
            body(g)
        }
        func up(_ ctx: GraphicsContext) {
            stroke(ctx, [(0, 1), (0, -1)])
            stroke(ctx, [(-0.65, -0.35), (0, -1), (0.65, -0.35)])
        }
        func tab(_ ctx: GraphicsContext) {
            stroke(ctx, [(-1, 0), (0.6, 0)])
            stroke(ctx, [(0.05, -0.55), (0.6, 0), (0.05, 0.55)])
            stroke(ctx, [(1, -0.75), (1, 0.75)])
        }
        let ctx = context
        switch glyph {
        case .plus:
            lineFrom(ctx, CGPoint(x: c.x - r, y: c.y), CGPoint(x: c.x + r, y: c.y))
            lineFrom(ctx, CGPoint(x: c.x, y: c.y - r), CGPoint(x: c.x, y: c.y + r))
        case .close:
            let d = r * 0.75
            lineFrom(ctx, CGPoint(x: c.x - d, y: c.y - d), CGPoint(x: c.x + d, y: c.y + d))
            lineFrom(ctx, CGPoint(x: c.x + d, y: c.y - d), CGPoint(x: c.x - d, y: c.y + d))
        case .chat:
            let top = c.y - r * 0.8
            let bottom = c.y + r * 0.45
            ctx.stroke(Path(roundedRect: CGRect(x: c.x - r, y: top, width: r * 2, height: bottom - top), cornerRadius: r * 0.45), with: shade, style: StrokeStyle(lineWidth: w))
            lineFrom(ctx, CGPoint(x: c.x - r * 0.35, y: bottom), CGPoint(x: c.x - r * 0.6, y: c.y + r))
            lineFrom(ctx, CGPoint(x: c.x - r * 0.6, y: c.y + r), CGPoint(x: c.x + r * 0.05, y: bottom))
        case .terminal:
            lineFrom(ctx, CGPoint(x: c.x - r, y: c.y - r * 0.6), CGPoint(x: c.x - r * 0.35, y: c.y))
            lineFrom(ctx, CGPoint(x: c.x - r * 0.35, y: c.y), CGPoint(x: c.x - r, y: c.y + r * 0.6))
            lineFrom(ctx, CGPoint(x: c.x + r * 0.05, y: c.y + r * 0.7), CGPoint(x: c.x + r, y: c.y + r * 0.7))
        case .copy:
            // A sheet, and the edges of one behind it.
            let side = r * 1.4
            let k = r * 0.3
            ctx.stroke(Path(roundedRect: CGRect(x: c.x + r - side, y: c.y + r - side, width: side, height: side), cornerRadius: k), with: shade, style: StrokeStyle(lineWidth: w))
            var p = Path()
            p.move(to: CGPoint(x: c.x - r, y: c.y - r + side - k))
            p.addLine(to: CGPoint(x: c.x - r, y: c.y - r + k))
            p.addQuadCurve(to: CGPoint(x: c.x - r + k, y: c.y - r), control: CGPoint(x: c.x - r, y: c.y - r))
            p.addLine(to: CGPoint(x: c.x - r + side - k, y: c.y - r))
            ctx.stroke(p, with: shade, style: StrokeStyle(lineWidth: w, lineCap: .round))
        case .check:
            lineFrom(ctx, CGPoint(x: c.x - r, y: c.y + r * 0.05), CGPoint(x: c.x - r * 0.3, y: c.y + r * 0.7))
            lineFrom(ctx, CGPoint(x: c.x - r * 0.3, y: c.y + r * 0.7), CGPoint(x: c.x + r, y: c.y - r * 0.6))
        case .edit:
            rotated(-45) { g in
                stroke(g, [(-1.2, 0), (-0.7, -0.32), (1.1, -0.32), (1.1, 0.32), (-0.7, 0.32)], closed: true)
                stroke(g, [(0.6, -0.32), (0.6, 0.32)])
            }
        case .escape:
            ctx.stroke(arc(CGRect(origin: at(-0.85, -0.85), size: CGSize(width: r * 1.7, height: r * 1.7)), 270, 270), with: shade, style: line)
            stroke(ctx, [(0, 0), (-0.95, -0.95)])
            stroke(ctx, [(-0.95, -0.4), (-0.95, -0.95), (-0.4, -0.95)])
        case .tab:
            tab(ctx)
        case .backTab:
            var g = ctx
            g.translateBy(x: c.x, y: 0)
            g.scaleBy(x: -1, y: 1)
            g.translateBy(x: -c.x, y: 0)
            tab(g)
        case .enter:
            stroke(ctx, [(0.8, -0.9), (0.8, 0.25), (-0.85, 0.25)])
            stroke(ctx, [(-0.3, -0.3), (-0.85, 0.25), (-0.3, 0.8)])
        case .up: up(ctx)
        case .down: rotated(180) { up($0) }
        case .left: rotated(-90) { up($0) }
        case .right: rotated(90) { up($0) }
        case .arrows:
            // Arrow keys as a keyboard has them, one over three.
            let k = size.width * 0.28
            let step = (size.width - k) / 2
            let top = c.y - (k + step) / 2
            for (x, y) in [(0, 0), (-1, 1), (0, 1), (1, 1)] {
                let rect = CGRect(x: c.x - k / 2 + CGFloat(x) * step, y: top + CGFloat(y) * step, width: k, height: k)
                ctx.fill(Path(roundedRect: rect, cornerRadius: k * 0.25), with: shade)
            }
        case .backspace:
            stroke(ctx, [(-1, 0), (-0.45, -0.75), (1, -0.75), (1, 0.75), (-0.45, 0.75)], closed: true)
            stroke(ctx, [(-0.02, -0.3), (0.58, 0.3)])
            stroke(ctx, [(0.58, -0.3), (-0.02, 0.3)])
        case .space:
            stroke(ctx, [(-1, -0.15), (-1, 0.5), (1, 0.5), (1, -0.15)])
        case .ctrl:
            stroke(ctx, [(-0.7, 0.35), (0, -0.4), (0.7, 0.35)])
        case .alt:
            stroke(ctx, [(-1, -0.6), (-0.35, -0.6), (0.35, 0.6), (1, 0.6)])
            stroke(ctx, [(0.15, -0.6), (1, -0.6)])
        case .bolt:
            stroke(ctx, [(0.3, -1), (-0.6, 0.15), (0.05, 0.15), (-0.3, 1), (0.6, -0.15), (-0.05, -0.15)], closed: true)
        case .shift:
            stroke(ctx, [(0, -1), (0.9, -0.05), (0.42, -0.05), (0.42, 0.85), (-0.42, 0.85), (-0.42, -0.05), (-0.9, -0.05)], closed: true)
        case .spark:
            var p = Path()
            p.move(to: CGPoint(x: c.x, y: c.y - r))
            p.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: c)
            p.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: c)
            p.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: c)
            p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: c)
            p.closeSubpath()
            ctx.fill(p, with: shade)
        case .hexagon:
            stroke(ctx, [(0, -1), (0.87, -0.5), (0.87, 0.5), (0, 1), (-0.87, 0.5), (-0.87, -0.5)], closed: true)
        case .snake:
            curve(ctx, [(0.45, -0.7), (-0.1, -1), (-0.85, -0.7), (-0.6, -0.2), (-0.4, 0.2), (0.65, 0), (0.6, 0.5), (0.55, 1), (-0.4, 1), (-0.9, 0.75)])
            let e = at(0.55, -0.62)
            ctx.fill(Path(ellipseIn: CGRect(x: e.x - r * 0.32, y: e.y - r * 0.32, width: r * 0.64, height: r * 0.64)), with: shade)
        case .branch:
            stroke(ctx, [(-0.67, -1), (-0.67, 0.33)])
            for (x, y) in [(0.67, -0.67), (-0.67, 0.67)] as [(CGFloat, CGFloat)] {
                let e = at(x, y)
                ctx.stroke(Path(ellipseIn: CGRect(x: e.x - r * 0.33, y: e.y - r * 0.33, width: r * 0.66, height: r * 0.66)), with: shade, style: line)
            }
            ctx.stroke(arc(CGRect(origin: at(-1.33, -1.33), size: CGSize(width: r * 2, height: r * 2)), 0, 90), with: shade, style: line)
        case .pulse:
            stroke(ctx, [(-1, 0), (-0.55, 0), (-0.25, -0.8), (0.25, 0.8), (0.55, 0), (1, 0)])
        case .globe:
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: shade, style: line)
            ctx.stroke(Path(ellipseIn: CGRect(origin: at(-0.42, -1), size: CGSize(width: r * 0.84, height: r * 2))), with: shade, style: line)
            stroke(ctx, [(-1, 0), (1, 0)])
        case .boxes:
            ctx.stroke(Path(roundedRect: CGRect(origin: at(-1, 0), size: CGSize(width: r * 2, height: r * 0.85)), cornerRadius: r * 0.15), with: shade, style: line)
            ctx.stroke(Path(roundedRect: CGRect(origin: at(-0.5, -0.85), size: CGSize(width: r, height: r * 0.85)), cornerRadius: r * 0.15), with: shade, style: line)
            stroke(ctx, [(0, 0), (0, 0.85)])
        case .folder:
            stroke(ctx, [(-1, 0.75), (-1, -0.75), (-0.3, -0.75), (-0.1, -0.45), (1, -0.45), (1, 0.75)], closed: true)
        case .database:
            let ring = CGSize(width: r * 1.7, height: r * 0.6)
            ctx.stroke(Path(ellipseIn: CGRect(origin: at(-0.85, -0.92), size: ring)), with: shade, style: line)
            ctx.stroke(arc(CGRect(origin: at(-0.85, -0.3), size: ring), 0, 180), with: shade, style: line)
            ctx.stroke(arc(CGRect(origin: at(-0.85, 0.32), size: ring), 0, 180), with: shade, style: line)
            stroke(ctx, [(-0.85, -0.62), (-0.85, 0.62)])
            stroke(ctx, [(0.85, -0.62), (0.85, 0.62)])
        case .book:
            stroke(ctx, [(0, -0.55), (-1, -0.85), (-1, 0.6), (0, 0.85)], closed: true)
            stroke(ctx, [(0, -0.55), (1, -0.85), (1, 0.6), (0, 0.85)], closed: true)
        case .hammer:
            rotated(45) { g in
                stroke(g, [(0, 1.1), (0, -0.25)])
                stroke(g, [(-0.6, -0.85), (0.6, -0.85), (0.6, -0.25), (-0.6, -0.25)], closed: true)
            }
        }
    }
}
