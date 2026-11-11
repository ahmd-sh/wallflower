// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ReadModel",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "ReadModel", targets: ["ReadModel"])],
    dependencies: [.package(path: "../Wire")],
    targets: [
        .target(name: "ReadModel", dependencies: ["Wire"]),
        .testTarget(name: "ReadModelTests", dependencies: ["ReadModel", "Wire"]),
    ],
    swiftLanguageModes: [.v5]
)
