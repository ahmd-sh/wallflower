// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TerminalCore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "TerminalCore", targets: ["TerminalCore"])],
    targets: [
        .binaryTarget(name: "GhosttyVT", path: "Vendor/ghostty-vt.xcframework"),
        .target(name: "CTerminal", dependencies: ["GhosttyVT"]),
        .target(name: "TerminalCore", dependencies: ["CTerminal"]),
        .testTarget(name: "TerminalCoreTests", dependencies: ["TerminalCore"]),
    ],
    swiftLanguageModes: [.v5]
)
