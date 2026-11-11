// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MobileViewUI",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "MobileViewUI", targets: ["MobileViewUI"])],
    dependencies: [
        .package(path: "../WallflowerDesign"),
        .package(path: "../ReadModel"),
        .package(path: "../ScreenReading"),
        .package(path: "../MarkdownKit"),
    ],
    targets: [
        .target(name: "MobileViewUI", dependencies: ["WallflowerDesign", "ReadModel", "ScreenReading", "MarkdownKit"]),
    ],
    swiftLanguageModes: [.v5]
)
