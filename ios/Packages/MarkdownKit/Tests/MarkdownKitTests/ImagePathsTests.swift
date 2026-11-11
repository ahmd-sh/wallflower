import XCTest
import MarkdownKit

final class ImagePathsTests: XCTestCase {
    private func found(_ text: String) -> [String] { ImagePaths.find(in: text).map { String(text[$0]) } }

    func testPathsInWhatsSaid() {
        XCTAssertEqual(found("Saved it to /Users/me/shot.png and ~/Desktop/a.JPG."), ["/Users/me/shot.png", "~/Desktop/a.JPG"])
        XCTAssertEqual(found("see ./out/screen.png, out/screen-2.webp; and (shot.png)"), ["./out/screen.png", "out/screen-2.webp", "shot.png"])
        XCTAssertEqual(found("../up/x.heic: done"), ["../up/x.heic"])
        XCTAssertEqual(found("the logo ✓ logo@2x.png"), ["logo@2x.png"])
    }

    func testNotPictures() {
        XCTAssertEqual(found("https://example.com/a.png and http://x.io/b/c.jpg"), [])
        XCTAssertEqual(found("shot.png.bak, main.go, a.pngx, notes.md"), [])
        XCTAssertEqual(found("png alone, .png alone"), [])
    }

    func testCodeThatsAPath() {
        XCTAssertTrue(ImagePaths.whole("/Users/me/Screen Shot 1.png"))
        XCTAssertTrue(ImagePaths.whole("out/a.gif"))
        XCTAssertFalse(ImagePaths.whole("open shot.png now\nplease.png"))
        XCTAssertFalse(ImagePaths.whole("https://a.io/x.png"))
        XCTAssertFalse(ImagePaths.whole("main.go"))
    }

    func testResolvedFromWhereThePaneIs() {
        XCTAssertEqual(ImagePaths.resolve("/tmp/a.png", in: "/Users/me"), "/tmp/a.png")
        XCTAssertEqual(ImagePaths.resolve("~/a.png", in: "/Users/me/dev"), "~/a.png")
        XCTAssertEqual(ImagePaths.resolve("./out/a.png", in: "/Users/me/dev"), "/Users/me/dev/out/a.png")
        XCTAssertEqual(ImagePaths.resolve("../a.png", in: "/Users/me/dev"), "/Users/me/a.png")
        XCTAssertEqual(ImagePaths.resolve("a.png", in: ""), "~/a.png")
    }
}
