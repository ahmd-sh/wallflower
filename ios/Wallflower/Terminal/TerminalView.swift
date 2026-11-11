import CoreText
import QuartzCore
import TerminalCore
import UIKit
import WallflowerDesign

/**
 * Draws a [Terminal]. Each row is a layer redrawn only when the terminal
 * reports it dirty, so a frame costs as much as what changed. Content is
 * pulled once per display refresh after [requestFrame].
 *
 * Scrolling the primary screen moves the terminal's own viewport through
 * its scrollback and never sends anything; over a full-screen app it's a
 * wheel notch per row ([onAppScroll]). A sideways drag is for moving between
 * panes ([onSwipe], [onSwipeEnd]); a tap is [onTap]. While [typing], the
 * phone's keyboard types straight into the pane ([onTyped]).
 */
final class TerminalView: UIView, UIKeyInput, UIGestureRecognizerDelegate {
    /** What it shows; while it's in a window, its output brings a frame. */
    var terminal: Terminal? {
        didSet {
            guard terminal !== oldValue else { return }
            oldValue?.removeListener(self)
            if window != nil { listen() }
            forceFull = true
            requestFrame()
        }
    }

    var theme: Theme = .everforestDarkHard {
        didSet {
            guard theme != oldValue else { return }
            background = theme.background
            foreground = theme.foreground
            backgroundColor = color(theme.background)
            forceFull = true
            requestFrame()
        }
    }

    var fontSize: CGFloat = 13 {
        didSet {
            fontSize = min(max(fontSize, 6), 32)
            if fontSize != oldValue { updateMetrics() }
        }
    }

    /** The grid that fits this view at the current font size. */
    var onGridSize: ((Int, Int) -> Void)?
    /** Full-screen apps own their scrolling: a wheel notch per row ([wheel] its bytes), or an arrow key if the app doesn't track the mouse (nil). */
    var onAppScroll: ((Bool, Data?) -> Void)?
    var onTap: (() -> Void)?
    /** A sideways drag, for moving between panes: how far it has gone, as it goes. */
    var onSwipe: ((CGFloat) -> Void)?
    /** The sideways drag let go: how far it went, and how fast (points a second; negative is leftwards). */
    var onSwipeEnd: ((CGFloat, CGFloat) -> Void)?
    /** What's typed while it has the keyboard. */
    var onTyped: ((Typed) -> Void)?
    /** It took the keyboard, or let go of it. */
    var onTypingChanged: ((Bool) -> Void)?
    /** Whether a tap brings up the keyboard to type straight into the pane. */
    var typingEnabled = false {
        didSet { if !typingEnabled && isFirstResponder { resignFirstResponder() } }
    }

    /** Paused, it stops pulling frames (it's covered); it catches up when resumed. */
    var paused = false {
        didSet { if !paused && oldValue { requestFrame() } }
    }

    private let content = CALayer() // the rows, shifted and scaled as one
    private let overlay = OverlayLayer() // cursor and predictions
    private var rows: [RowLayer] = []
    private let fb = FrameBuffer()
    private var cols = 0
    private var gridRows = 0
    private var background: Int32 = Theme.everforestDarkHard.background
    private var foreground: Int32 = Theme.everforestDarkHard.foreground
    private var cursorVisible = false
    private var cursorX = 0
    private var cursorY = 0
    private var cursorStyle = FrameBuffer.CURSOR_BLOCK
    private var cursorColor: Int32 = FrameBuffer.DEFAULT
    private var altScreen = false
    private var scrollback = 0
    private var restHeight: CGFloat = 0
    private var forceFull = true
    private var link: CADisplayLink?
    private var frameRequested = false
    private let requested = Requested()
    let predictions = Predictions()
    private var expiry: Timer?

    private(set) var metrics = Metrics(size: 13)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        backgroundColor = color(background)
        layer.addSublayer(content)
        layer.addSublayer(overlay)
        content.anchorPoint = .zero
        overlay.anchorPoint = .zero
        overlay.contentsScale = UIScreen.main.scale
        overlay.view = self
        clipsToBounds = true
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.delegate = self
        pan.maximumNumberOfTouches = 1
        addGestureRecognizer(pan)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        updateMetrics()
    }

    required init?(coder: NSCoder) { fatalError() }

    deinit {
        terminal?.removeListener(self)
        link?.invalidate()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            listen()
            requestFrame()
        } else {
            terminal?.removeListener(self)
            link?.invalidate()
            link = nil
        }
    }

    private func listen() {
        let flag = requested
        terminal?.addListener(self) { [weak self] in
            guard flag.set() else { return }
            DispatchQueue.main.async { self?.requestFrame() }
        }
    }

    // MARK: Frames

    /** Coalesces to one pull per display refresh. */
    func requestFrame() {
        requested.clear()
        if frameRequested { return }
        frameRequested = true
        if link == nil {
            let l = CADisplayLink(target: Proxy(self), selector: #selector(Proxy.tick))
            l.add(to: .main, forMode: .common)
            link = l
        }
        link?.isPaused = false
    }

    fileprivate func pull() {
        frameRequested = false
        link?.isPaused = true
        guard let t = terminal, !paused else { return }
        if forceFull { t.setTheme(theme) }
        guard t.frame(into: fb, force: forceFull, reader: self) else { return }
        forceFull = false
        if fb.cols != cols || fb.rows != gridRows {
            // The frame before the grid changed size fades into the new one.
            if cols > 0 {
                let fade = CATransition()
                fade.type = .fade
                fade.duration = 0.18
                content.add(fade, forKey: "regrid")
            }
            cols = fb.cols
            gridRows = fb.rows
            rows.forEach { $0.removeFromSuperlayer() }
            rows = (0..<gridRows).map { _ in
                let r = RowLayer()
                r.contentsScale = UIScreen.main.scale
                r.anchorPoint = .zero
                r.actions = ["contents": NSNull(), "position": NSNull(), "bounds": NSNull()]
                content.addSublayer(r)
                return r
            }
            predictions.clear()
            layoutRows()
            if !fb.full {
                forceFull = true
                requestFrame()
                return
            }
        }
        background = fb.background
        foreground = fb.foreground
        backgroundColor = color(background)
        for i in 0..<fb.rowCount { record(i) }
        cursorVisible = fb.cursorVisible && fb.cursorInViewport
        cursorX = fb.cursorX
        cursorY = fb.cursorY
        cursorStyle = fb.cursorStyle
        cursorColor = fb.cursorColor
        altScreen = fb.altScreen
        scrollback = max(fb.scrollTotal - fb.scrollLength, 0)
        rest()
        if !predictions.isEmpty && predictions.confirm(cellAt: codepointAt, now: now()) { scheduleExpiry() }
        place()
        overlay.setNeedsDisplay()
    }

    private func codepointAt(_ x: Int, _ y: Int) -> UInt32 {
        guard y < rows.count, x < rows[y].cells.count else { return 0 }
        return rows[y].cells[x].codepoint
    }

    private func record(_ i: Int) {
        let y = fb.rowY(i)
        guard y < rows.count else { return }
        let row = rows[y]
        var cells = [Cell]()
        cells.reserveCapacity(cols)
        for x in 0..<cols {
            let flags = fb.flags(i, x)
            let cp = fb.codepoint(i, x)
            let inverse = flags & FrameBuffer.FLAG_INVERSE != 0
            let selected = flags & FrameBuffer.FLAG_SELECTED != 0
            var fg = inverse ? or(fb.bg(i, x), background) : or(fb.fg(i, x), foreground)
            var bg = inverse ? or(fb.fg(i, x), foreground) : fb.bg(i, x)
            if selected {
                fg = background
                bg = foreground
            }
            let text = (flags & FrameBuffer.FLAG_GRAPHEME != 0) ? fb.text(i, x) : nil
            cells.append(Cell(codepoint: cp, text: text, fg: fg, bg: bg, underline: fb.underlineColor(i, x), flags: flags))
        }
        row.cells = cells
        row.metrics = metrics
        row.setNeedsDisplay()
    }

    private func or(_ c: Int32, _ other: Int32) -> Int32 { c == FrameBuffer.DEFAULT ? other : c }

    // MARK: Layout

    private func layoutRows() {
        let width = CGFloat(cols) * metrics.cellWidth
        for (y, r) in rows.enumerated() {
            r.frame = CGRect(x: 0, y: CGFloat(y) * metrics.cellHeight, width: ceil(width), height: metrics.cellHeight)
        }
    }

    /** A pane wider than the view (the desktop owns the size) shrinks to fit. */
    private func fitScale() -> CGFloat {
        let w = CGFloat(cols) * metrics.cellWidth
        return w > bounds.width && bounds.width > 0 ? bounds.width / w : 1
    }

    /** Rows down to the cursor's, which tmux keeps in view; all of them if it's hidden. */
    private func cursorRows() -> Int { cursorVisible ? cursorY + 1 : gridRows }

    /**
     * How far up the rows are drawn; negative is down. Shorter than the
     * grid: the cursor stays in view, as tmux keeps it. Taller: rows move down
     * as far as tmux will pull history back in above them, all the way for
     * full-screen apps, which redraw against the bottom.
     */
    private func shift() -> CGFloat {
        guard gridRows > 0 else { return 0 }
        let cell = metrics.cellHeight * fitScale()
        let h = bounds.height
        let contentHeight = CGFloat(gridRows) * cell
        if contentHeight > h { return min(max(CGFloat(cursorRows()) * cell - h, 0), contentHeight - h) }
        let room = h - restHeight
        if restHeight == 0 || room <= 0 { return 0 }
        return -(altScreen ? room : min(room, CGFloat(scrollback) * cell))
    }

    /** Notes the height the grid fills, while it does. */
    private func rest() {
        let cell = metrics.cellHeight * fitScale()
        if gridRows > 0 && cell > 0 && Int(floor(bounds.height / cell)) == gridRows { restHeight = bounds.height }
    }

    private func place() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let s = fitScale()
        let t = CATransform3DScale(CATransform3DMakeTranslation(0, -shift(), 0), s, s, 1)
        content.transform = t
        overlay.transform = t
        overlay.frame = CGRect(x: 0, y: 0, width: max(bounds.width / s, 1), height: max(CGFloat(gridRows) * metrics.cellHeight, bounds.height))
        overlay.transform = t
        CATransaction.commit()
    }

    /** Where the cursor's row ends as drawn, in points from the top. */
    func cursorBottom() -> CGFloat { gridRows == 0 ? bounds.height : CGFloat(cursorRows()) * metrics.cellHeight * fitScale() - shift() }

    override func layoutSubviews() {
        super.layoutSubviews()
        rest()
        reportGrid()
        place()
    }

    private func updateMetrics() {
        metrics = Metrics(size: fontSize)
        forceFull = true
        layoutRows()
        reportGrid()
        requestFrame()
    }

    private var lastGrid: (Int, Int)?

    private func reportGrid() {
        guard bounds.width > 0, bounds.height > 0, metrics.cellWidth > 0 else { return }
        let g = (Int(floor(bounds.width / metrics.cellWidth)), Int(floor(bounds.height / metrics.cellHeight)))
        guard g.0 > 0, g.1 > 0 else { return }
        if let l = lastGrid, l == g { return }
        lastGrid = g
        onGridSize?(g.0, g.1)
    }

    // MARK: Predictions

    /** Shows [text] at the cursor right away, in grey, until the pane echoes it. Call just before sending it. */
    func predict(_ text: String) {
        guard cursorVisible, cols > 0 else { return }
        predictions.add(text, cursorX: cursorX, cursorY: cursorY, cols: cols, rows: gridRows, now: now())
        scheduleExpiry()
        overlay.setNeedsDisplay()
    }

    private func scheduleExpiry() {
        expiry?.invalidate()
        guard !predictions.isEmpty else { return }
        expiry = Timer.scheduledTimer(withTimeInterval: Predictions.timeout + 0.016, repeats: false) { [weak self] _ in
            guard let self else { return }
            if self.predictions.expire(now: self.now()) { self.overlay.setNeedsDisplay() }
        }
    }

    private func now() -> TimeInterval { CACurrentMediaTime() }

    fileprivate func drawOverlay(_ ctx: CGContext) {
        let m = metrics
        if !predictions.isEmpty {
            ctx.setFillColor(color(background).cgColor)
            for p in predictions.cells {
                let r = CGRect(x: CGFloat(p.x) * m.cellWidth, y: CGFloat(p.y) * m.cellHeight, width: CGFloat(p.width) * m.cellWidth, height: m.cellHeight)
                // Cover whatever is there now, e.g. a shell's autosuggestion.
                ctx.fill(r)
                Draw.text(p.text, in: ctx, at: CGPoint(x: r.minX, y: r.minY), metrics: m, font: m.regular, color: color(foreground).withAlphaComponent(0.44).cgColor)
            }
        }
        // The cursor runs ahead with the predictions, so typing feels local.
        let (cx, cy) = predictions.cursorAfter(cols: cols) ?? (cursorX, cursorY)
        guard cursorVisible, cx < cols, cy < gridRows else { return }
        let left = CGFloat(cx) * m.cellWidth
        let top = CGFloat(cy) * m.cellHeight
        let c = color(cursorColor == FrameBuffer.DEFAULT ? theme.cursor : cursorColor).cgColor
        ctx.setFillColor(c)
        switch cursorStyle {
        case FrameBuffer.CURSOR_BAR:
            ctx.fill(CGRect(x: left, y: top, width: max(2, m.cellWidth / 8), height: m.cellHeight))
        case FrameBuffer.CURSOR_UNDERLINE:
            let h = max(2, m.cellHeight / 10)
            ctx.fill(CGRect(x: left, y: top + m.cellHeight - h, width: m.cellWidth, height: h))
        case FrameBuffer.CURSOR_BLOCK_HOLLOW:
            ctx.setStrokeColor(c)
            ctx.setLineWidth(max(1, m.cellWidth / 10))
            ctx.stroke(CGRect(x: left, y: top, width: m.cellWidth, height: m.cellHeight).insetBy(dx: 0.5, dy: 0.5))
        default:
            ctx.fill(CGRect(x: left, y: top, width: m.cellWidth, height: m.cellHeight))
            let cp = codepointAt(cx, cy)
            if cp > 0x20, let u = Unicode.Scalar(cp) {
                Draw.text(String(Character(u)), in: ctx, at: CGPoint(x: left, y: top), metrics: m, font: m.regular, color: color(background).cgColor)
            }
        }
    }

    // MARK: Gestures

    private var swiping = false
    private var scrollRemainder: CGFloat = 0
    private var momentum: CADisplayLink?
    private var velocity: CGFloat = 0
    private var lastMomentum: CFTimeInterval = 0
    private var touchPoint: CGPoint = .zero

    @objc private func tapped() {
        stopMomentum()
        if typingEnabled && !isFirstResponder { becomeFirstResponder() }
        onTap?()
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { false }

    @objc private func panned(_ g: UIPanGestureRecognizer) {
        let t = g.translation(in: self)
        touchPoint = g.location(in: self)
        switch g.state {
        case .began:
            stopMomentum()
            let v = g.velocity(in: self)
            swiping = onSwipe != nil && abs(v.x) > abs(v.y) * 1.5
            scrollRemainder = 0
        case .changed:
            if swiping {
                onSwipe?(t.x)
            } else {
                scrollPixels(-t.y)
                g.setTranslation(CGPoint(x: t.x, y: 0), in: self)
            }
        case .ended, .cancelled, .failed:
            if swiping {
                swiping = false
                onSwipeEnd?(t.x, g.state == .ended ? g.velocity(in: self).x : 0)
            } else if g.state == .ended {
                startMomentum(-g.velocity(in: self).y)
            }
        default:
            break
        }
    }

    private func startMomentum(_ v: CGFloat) {
        guard abs(v) > 60 else { return }
        velocity = v
        lastMomentum = CACurrentMediaTime()
        let l = CADisplayLink(target: Proxy(self), selector: #selector(Proxy.coast))
        l.add(to: .main, forMode: .common)
        momentum = l
    }

    fileprivate func coast() {
        let now = CACurrentMediaTime()
        let dt = now - lastMomentum
        lastMomentum = now
        scrollPixels(velocity * CGFloat(dt))
        velocity *= CGFloat(pow(0.998, dt * 1000)) // UIScrollView's normal deceleration
        if abs(velocity) < 20 { stopMomentum() }
    }

    private func stopMomentum() {
        momentum?.invalidate()
        momentum = nil
    }

    private func scrollPixels(_ dy: CGFloat) {
        guard let t = terminal else { return }
        let cell = metrics.cellHeight * fitScale()
        scrollRemainder += dy / cell
        let n = Int(scrollRemainder)
        guard n != 0 else { return }
        scrollRemainder -= CGFloat(n)
        let up = n < 0 // towards history
        let col = min(max(Int(touchPoint.x / (metrics.cellWidth * fitScale())), 0), max(cols - 1, 0))
        let row = min(max(Int((touchPoint.y + shift()) / cell), 0), max(gridRows - 1, 0))
        switch t.wheel(up: up, col: col, row: row) {
        case .local:
            t.scrollBy(n)
            requestFrame()
        case .mouse(let bytes):
            for _ in 0..<abs(n) { onAppScroll?(up, bytes) }
        case .arrows:
            for _ in 0..<abs(n) { onAppScroll?(up, nil) }
        }
    }

    func scrollToBottom() {
        stopMomentum()
        terminal?.scrollToBottom()
        requestFrame()
    }

    // MARK: Typing straight into the pane

    override var canBecomeFirstResponder: Bool { typingEnabled }

    override func becomeFirstResponder() -> Bool {
        let ok = super.becomeFirstResponder()
        if ok { onTypingChanged?(true) }
        return ok
    }

    override func resignFirstResponder() -> Bool {
        let ok = super.resignFirstResponder()
        if ok { onTypingChanged?(false) }
        return ok
    }

    var hasText: Bool { true }

    func insertText(_ text: String) {
        if text == "\n" {
            onTyped?(.return)
        } else {
            onTyped?(.text(text))
        }
    }

    func deleteBackward() { onTyped?(.key("BSpace")) }

    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var smartQuotesType: UITextSmartQuotesType = .no
    var smartDashesType: UITextSmartDashesType = .no
    var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    var keyboardType: UIKeyboardType = .asciiCapable
    var keyboardAppearance: UIKeyboardAppearance = .dark
    var returnKeyType: UIReturnKeyType = .default
    var inlinePredictionType: UITextInlinePredictionType = .no

    /** A keyboard plugged in: keys by name, ctrl and alt with them. */
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var handled = false
        for p in presses {
            guard let key = p.key, let t = Typed.from(key) else { continue }
            onTyped?(t)
            handled = true
        }
        if !handled { super.pressesBegan(presses, with: event) }
    }

    // MARK: Colors

    private var colors: [Int32: UIColor] = [:]

    func color(_ rgb: Int32) -> UIColor {
        if let c = colors[rgb] { return c }
        let v = UInt32(bitPattern: rgb)
        let c = UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        colors[rgb] = c
        return c
    }
}

/** What a key typed straight into a pane is: text as it is, a key by its name, or the keyboard's return key. */
enum Typed: Equatable {
    case text(String)
    case key(String)
    case `return`

    /** A hardware key, as wallflower names keys; nil for one a terminal has no use for. */
    static func from(_ key: UIKey) -> Typed? {
        let ctrl = key.modifierFlags.contains(.control)
        let alt = key.modifierFlags.contains(.alternate)
        let shift = key.modifierFlags.contains(.shift)
        let named: String? = {
            switch key.keyCode {
            case .keyboardReturnOrEnter, .keypadEnter: return "Enter"
            case .keyboardDeleteOrBackspace: return "BSpace"
            case .keyboardDeleteForward: return "DC"
            case .keyboardTab: return shift ? "BTab" : "Tab"
            case .keyboardEscape: return "Escape"
            case .keyboardUpArrow: return "Up"
            case .keyboardDownArrow: return "Down"
            case .keyboardLeftArrow: return "Left"
            case .keyboardRightArrow: return "Right"
            case .keyboardHome: return "Home"
            case .keyboardEnd: return "End"
            case .keyboardPageUp: return "PageUp"
            case .keyboardPageDown: return "PageDown"
            case .keyboardInsert: return "IC"
            case .keyboardF1: return "F1"
            case .keyboardF2: return "F2"
            case .keyboardF3: return "F3"
            case .keyboardF4: return "F4"
            case .keyboardF5: return "F5"
            case .keyboardF6: return "F6"
            case .keyboardF7: return "F7"
            case .keyboardF8: return "F8"
            case .keyboardF9: return "F9"
            case .keyboardF10: return "F10"
            case .keyboardF11: return "F11"
            case .keyboardF12: return "F12"
            default: return nil
            }
        }()
        let shiftable: Set<String> = ["Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown", "DC", "IC"]
        var name: String
        if let named {
            // Enter and ⌫ come through insertText and deleteBackward, unless ctrl or alt is held.
            if (named == "Enter" || named == "BSpace") && !ctrl && !alt { return nil }
            name = named
        } else {
            guard ctrl || alt else { return nil } // text comes through insertText
            let chars = key.charactersIgnoringModifiers
            guard !chars.isEmpty else { return nil }
            name = chars == " " ? "Space" : chars
        }
        var prefix = ""
        if ctrl { prefix += "C-" }
        if alt { prefix += "M-" }
        if shift, let named, shiftable.contains(named) { prefix += "S-" }
        return .key(prefix + name)
    }
}

/** A cell as a row draws it. */
struct Cell {
    var codepoint: UInt32
    var text: String?
    var fg: Int32
    var bg: Int32
    var underline: Int32
    var flags: Int32
}

/** The font's measures at a size: its cell, where the baseline sits, and its faces. */
struct Metrics {
    let size: CGFloat
    let regular: CTFont
    let bold: CTFont
    let italic: CTFont
    let boldItalic: CTFont
    let cellWidth: CGFloat
    let cellHeight: CGFloat
    let baseline: CGFloat // from the top of the cell

    init(size: CGFloat) {
        self.size = size
        regular = Metrics.font(Fonts.monoName, size, italic: false)
        bold = Metrics.font(Fonts.monoBoldName, size, italic: false)
        italic = Metrics.font(Fonts.monoName, size, italic: true)
        boldItalic = Metrics.font(Fonts.monoBoldName, size, italic: true)
        var glyph = CGGlyph(0)
        var m: UniChar = 0x4D // M
        CTFontGetGlyphsForCharacters(regular, &m, &glyph, 1)
        var advance = CGSize.zero
        CTFontGetAdvancesForGlyphs(regular, .horizontal, &glyph, &advance, 1)
        cellWidth = advance.width
        let ascent = CTFontGetAscent(regular)
        let descent = CTFontGetDescent(regular)
        cellHeight = ceil((ascent + descent) * 1.15)
        baseline = (cellHeight - (ascent + descent)) / 2 + ascent
    }

    /**
     * The font, then symbols it lacks (TUI spinners, ⏺ ⎿ ⏵ ✻), then the
     * system's fonts.
     */
    private static func font(_ name: String, _ size: CGFloat, italic: Bool) -> CTFont {
        let cascade = ["NotoSansSymbols2-Regular", "NotoSansSymbols-Regular"].map { CTFontDescriptorCreateWithNameAndSize($0 as CFString, size) }
        let attrs: [CFString: Any] = [kCTFontNameAttribute: name, kCTFontCascadeListAttribute: cascade]
        let desc = CTFontDescriptorCreateWithAttributes(attrs as CFDictionary)
        var matrix = italic ? CGAffineTransform(a: 1, b: 0, c: 0.2, d: 1, tx: 0, ty: 0) : .identity
        return CTFontCreateWithFontDescriptor(desc, size, &matrix)
    }
}

enum Draw {
    /** [text] in [font] with its cell's top left at [at], in a context whose y runs down. */
    static func text(_ text: String, in ctx: CGContext, at: CGPoint, metrics: Metrics, font: CTFont, color: CGColor) {
        let attr = NSAttributedString(string: text, attributes: [.init(kCTFontAttributeName as String): font, .init(kCTForegroundColorAttributeName as String): color])
        let line = CTLineCreateWithAttributedString(attr)
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: at.x, y: at.y + metrics.baseline)
        CTLineDraw(line, ctx)
        ctx.restoreGState()
    }

    static func width(_ text: String, font: CTFont) -> CGFloat {
        let attr = NSAttributedString(string: text, attributes: [.init(kCTFontAttributeName as String): font])
        return CGFloat(CTLineGetTypographicBounds(CTLineCreateWithAttributedString(attr), nil, nil, nil))
    }
}

/** One row of the grid, drawn from its cells. */
final class RowLayer: CALayer {
    var cells: [Cell] = []
    var metrics = Metrics(size: 13)

    override func draw(in ctx: CGContext) {
        let m = metrics
        let h = bounds.height
        var x = 0
        let n = cells.count
        // Backgrounds, in runs of one color.
        while x < n {
            let bg = cells[x].bg
            var end = x + 1
            while end < n && cells[end].bg == bg { end += 1 }
            if bg != FrameBuffer.DEFAULT {
                ctx.setFillColor(Self.cg(bg))
                ctx.fill(CGRect(x: CGFloat(x) * m.cellWidth, y: 0, width: CGFloat(end - x) * m.cellWidth, height: h))
            }
            x = end
        }
        x = 0
        while x < n {
            let c = cells[x]
            let wide = FrameBuffer.wide(c.flags)
            if wide >= 2 || c.codepoint == 0 || c.flags & FrameBuffer.FLAG_INVISIBLE != 0 {
                x += 1
                continue
            }
            let font = Self.font(c.flags, m)
            let ink = Self.ink(c)
            if c.codepoint < 0x80 && wide == 0 && c.text == nil {
                // A run of plain ASCII in one style draws as one string; the
                // font is monospace, so it lands on the grid.
                var s = ""
                var end = x
                while end < n && Self.sameRun(cells[end], c) {
                    let e = cells[end].codepoint
                    s.unicodeScalars.append(Unicode.Scalar(e == 0 ? 0x20 : e)!)
                    end += 1
                }
                Draw.text(s, in: ctx, at: CGPoint(x: CGFloat(x) * m.cellWidth, y: 0), metrics: m, font: font, color: ink)
                decorate(ctx, c, from: x, to: end, ink: ink)
                x = end
            } else {
                // Anything else (wide, emoji, graphemes, fallback fonts) is
                // drawn alone, centered and shrunk to fit its cells.
                let s = c.text ?? Unicode.Scalar(c.codepoint).map { String(Character($0)) } ?? ""
                let span = wide == 1 ? 2 : 1
                let w = Draw.width(s, font: font)
                let room = CGFloat(span) * m.cellWidth
                let left = CGFloat(x) * m.cellWidth
                if w > room {
                    ctx.saveGState()
                    let k = room / w
                    ctx.translateBy(x: left + room / 2, y: h / 2)
                    ctx.scaleBy(x: k, y: k)
                    ctx.translateBy(x: -(left + room / 2), y: -h / 2)
                    Draw.text(s, in: ctx, at: CGPoint(x: left, y: 0), metrics: m, font: font, color: ink)
                    ctx.restoreGState()
                } else {
                    Draw.text(s, in: ctx, at: CGPoint(x: left + (room - w) / 2, y: 0), metrics: m, font: font, color: ink)
                }
                decorate(ctx, c, from: x, to: x + span, ink: ink)
                x += span
            }
        }
    }

    private static let styleMask: Int32 = FrameBuffer.FLAG_BOLD | FrameBuffer.FLAG_ITALIC | FrameBuffer.FLAG_FAINT | (7 << 8) | FrameBuffer.FLAG_STRIKETHROUGH | FrameBuffer.FLAG_OVERLINE | FrameBuffer.FLAG_INVERSE | FrameBuffer.FLAG_SELECTED

    private static func sameRun(_ a: Cell, _ first: Cell) -> Bool {
        a.codepoint < 0x80 && FrameBuffer.wide(a.flags) == 0 && a.text == nil && a.flags & FrameBuffer.FLAG_GRAPHEME == 0 &&
            a.flags & styleMask == first.flags & styleMask && a.fg == first.fg && a.flags & FrameBuffer.FLAG_INVISIBLE == 0
    }

    private static func font(_ flags: Int32, _ m: Metrics) -> CTFont {
        let b = flags & FrameBuffer.FLAG_BOLD != 0
        let i = flags & FrameBuffer.FLAG_ITALIC != 0
        return b ? (i ? m.boldItalic : m.bold) : (i ? m.italic : m.regular)
    }

    private static func ink(_ c: Cell) -> CGColor {
        let faint = c.flags & FrameBuffer.FLAG_FAINT != 0
        return faint ? cg(c.fg, alpha: 0x88 / 255.0) : cg(c.fg)
    }

    private func decorate(_ ctx: CGContext, _ c: Cell, from: Int, to: Int, ink: CGColor) {
        let underline = FrameBuffer.underline(c.flags)
        guard underline != 0 || c.flags & (FrameBuffer.FLAG_STRIKETHROUGH | FrameBuffer.FLAG_OVERLINE) != 0 else { return }
        let m = metrics
        let left = CGFloat(from) * m.cellWidth
        let right = CGFloat(to) * m.cellWidth
        let thickness = max(1, m.cellHeight / 16)
        ctx.saveGState()
        ctx.setLineWidth(thickness)
        ctx.setStrokeColor(ink)
        func hline(_ y: CGFloat) {
            ctx.move(to: CGPoint(x: left, y: y))
            ctx.addLine(to: CGPoint(x: right, y: y))
            ctx.strokePath()
        }
        if c.flags & FrameBuffer.FLAG_STRIKETHROUGH != 0 { hline(m.cellHeight * 0.55) }
        if c.flags & FrameBuffer.FLAG_OVERLINE != 0 { hline(thickness) }
        if underline != 0 {
            if c.underline != FrameBuffer.DEFAULT { ctx.setStrokeColor(Self.cg(c.underline)) }
            let y = m.baseline + thickness * 2
            switch underline {
            case 2:
                hline(y - thickness)
                hline(y + thickness)
            case 3:
                ctx.move(to: CGPoint(x: left, y: y))
                var px = left
                var up = true
                let step = m.cellWidth / 2
                while px < right {
                    ctx.addQuadCurve(to: CGPoint(x: px + step, y: y), control: CGPoint(x: px + step / 2, y: up ? y - thickness * 1.5 : y + thickness * 1.5))
                    px += step
                    up.toggle()
                }
                ctx.strokePath()
            case 4:
                ctx.setLineDash(phase: 0, lengths: [thickness, thickness * 2])
                hline(y)
            case 5:
                ctx.setLineDash(phase: 0, lengths: [m.cellWidth / 2, m.cellWidth / 4])
                hline(y)
            default:
                hline(y)
            }
        }
        ctx.restoreGState()
    }

    private static var colors: [Int64: CGColor] = [:]

    static func cg(_ rgb: Int32, alpha: CGFloat = 1) -> CGColor {
        let key = Int64(rgb) << 8 | Int64(alpha * 255)
        if let c = colors[key] { return c }
        let v = UInt32(bitPattern: rgb)
        let c = CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: alpha)
        colors[key] = c
        return c
    }
}

/** The cursor and what's predicted, over the rows. */
final class OverlayLayer: CALayer {
    weak var view: TerminalView?

    override init() {
        super.init()
        actions = ["contents": NSNull(), "position": NSNull(), "bounds": NSNull(), "transform": NSNull()]
    }

    override init(layer: Any) { super.init(layer: layer) }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(in ctx: CGContext) { view?.drawOverlay(ctx) }
}

/** Whether a frame's been asked for since the last pull, set from any thread. */
private final class Requested: @unchecked Sendable {
    private let lock = NSLock()
    private var on = false

    /** True if it wasn't already. */
    func set() -> Bool { lock.withLock { defer { on = true }; return !on } }
    func clear() { lock.withLock { on = false } }
}

/** Display links hold their target strongly; this doesn't hold the view. */
private final class Proxy: NSObject {
    weak var view: TerminalView?
    init(_ view: TerminalView) { self.view = view }
    @objc func tick() { view?.pull() }
    @objc func coast() { view?.coast() }
}
