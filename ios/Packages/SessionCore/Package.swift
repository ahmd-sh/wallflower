// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "SessionCore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "SessionCore", targets: ["SessionCore"])],
    dependencies: [
        .package(path: "../Wire"),
        .package(path: "../ReadModel"),
        .package(path: "../ScreenReading"),
        .package(path: "../TerminalCore"),
        .package(url: "https://github.com/apple/swift-nio-ssh.git", from: "0.15.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.80.0"),
        .package(url: "https://github.com/apple/swift-nio-transport-services.git", from: "1.25.0"),
    ],
    targets: [
        .target(name: "SessionCore", dependencies: [
            "Wire", "ReadModel", "ScreenReading", "TerminalCore",
            .product(name: "NIOSSH", package: "swift-nio-ssh"),
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOTransportServices", package: "swift-nio-transport-services"),
        ]),
        .testTarget(name: "SessionCoreTests", dependencies: [
            "SessionCore",
            .product(name: "NIOSSH", package: "swift-nio-ssh"),
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOTransportServices", package: "swift-nio-transport-services"),
        ]),
    ],
    swiftLanguageModes: [.v5]
)
