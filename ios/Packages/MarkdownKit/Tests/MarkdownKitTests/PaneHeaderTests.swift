import XCTest
import MarkdownKit

final class PaneHeaderTests: XCTestCase {
    func testADirectoryGoesByItsNameAloneWhenNoOtherSharesIt() {
        XCTAssertEqual(
            placeNames(["/Users/me/dev/wallflower", "/Users/me/dev/api", "/tmp"]),
            ["/Users/me/dev/wallflower": "wallflower", "/Users/me/dev/api": "api", "/tmp": "tmp"]
        )
    }

    func testDirectoriesOfTheSameNameTakeAsManyAboveThemAsTellThemApart() {
        XCTAssertEqual(
            placeNames(["/Users/me/dev/api", "/Users/me/work/api"]),
            ["/Users/me/dev/api": "dev/api", "/Users/me/work/api": "work/api"]
        )
        XCTAssertEqual(
            placeNames(["/a/x/api", "/b/x/api", "/c/api"]),
            ["/a/x/api": "a/x/api", "/b/x/api": "b/x/api", "/c/api": "c/api"]
        )
        XCTAssertEqual(
            placeNames(["/srv/api", "/srv/api/api"]),
            ["/srv/api": "srv/api", "/srv/api/api": "api/api"]
        )
    }

    func testPanesInOneDirectoryShareItsName() {
        XCTAssertEqual(
            placeNames(["/Users/me/dev/api", "/Users/me/dev/api", "/Users/me/dev/api/"]),
            ["/Users/me/dev/api": "api", "/Users/me/dev/api/": "api"]
        )
    }

    func testTheHomeIsTildeAndTheRootASlash() {
        XCTAssertEqual(placeNames(["/Users/me", "/"]), ["/Users/me": "~", "/": "/"])
        XCTAssertEqual(placeNames(["/home/me/api", "/home/me/dev/api"]), ["/home/me/api": "~/api", "/home/me/dev/api": "dev/api"])
        XCTAssertEqual(
            placeNames(["/Users/me/api", "/Users/me/dev/api"]),
            ["/Users/me/api": "~/api", "/Users/me/dev/api": "dev/api"]
        )
        XCTAssertEqual(placeNames(["/api", "/srv/api"]), ["/api": "/api", "/srv/api": "srv/api"])
        XCTAssertEqual(placeNames(["/Users", "/home"]), ["/Users": "Users", "/home": "home"])
    }

    func testTwoHomesThatWouldReadTheSameKeepTheirNames() {
        XCTAssertEqual(
            placeNames(["/Users/me/notes", "/Users/Shared/notes"]),
            ["/Users/me/notes": "me/notes", "/Users/Shared/notes": "Shared/notes"]
        )
        XCTAssertEqual(placeNames(["/Users/me", "/home/me"]), ["/Users/me": "Users/me", "/home/me": "home/me"])
    }

    func testAPaneWithNoDirectoryGoesByItsCommand() {
        let panes = [pane("%1", "/Users/me/dev/api", "zsh"), pane("%2", "", "htop"), pane("%3", "/Users/me/work/api", "nvim")]
        XCTAssertEqual(headerNames(panes), ["%1": "dev/api", "%2": "htop", "%3": "work/api"])
    }

    private struct Pane: HeaderPane {
        var id: String
        var path: String
        var command: String
    }

    private func pane(_ id: String, _ path: String, _ command: String) -> Pane {
        Pane(id: id, path: path, command: command)
    }
}
