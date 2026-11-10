// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "A747Core",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "A747Core", targets: ["A747Core"])],
    targets: [
        .target(name: "A747Core"),
        .testTarget(name: "A747CoreTests", dependencies: ["A747Core"]),
    ]
)
