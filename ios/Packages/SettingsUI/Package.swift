// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "SettingsUI",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "SettingsUI", targets: ["SettingsUI"])],
    dependencies: [
        .package(path: "../WallflowerDesign"),
        .package(path: "../SessionCore"),
        .package(path: "../ScreenReading"),
        .package(path: "../Wire"),
    ],
    targets: [
        .target(name: "SettingsUI", dependencies: ["WallflowerDesign", "SessionCore", "ScreenReading", "Wire"]),
    ],
    swiftLanguageModes: [.v5]
)
