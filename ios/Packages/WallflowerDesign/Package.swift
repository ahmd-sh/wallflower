// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WallflowerDesign",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "WallflowerDesign", targets: ["WallflowerDesign"])],
    dependencies: [.package(path: "../TerminalCore")],
    targets: [
        .target(name: "WallflowerDesign", dependencies: ["TerminalCore"], resources: [.copy("Fonts")]),
    ],
    swiftLanguageModes: [.v5]
)
