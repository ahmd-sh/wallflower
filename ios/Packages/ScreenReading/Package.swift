// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ScreenReading",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "ScreenReading", targets: ["ScreenReading"])],
    targets: [
        .target(name: "ScreenReading"),
        .testTarget(name: "ScreenReadingTests", dependencies: ["ScreenReading"]),
    ],
    swiftLanguageModes: [.v5]
)
