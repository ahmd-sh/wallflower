// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Wire",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "Wire", targets: ["Wire"])],
    targets: [
        .target(name: "Wire"),
        .testTarget(name: "WireTests", dependencies: ["Wire"]),
    ],
    swiftLanguageModes: [.v5]
)
