// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "NostrBar",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "NostrBar", targets: ["NostrBar"])
    ],
    dependencies: [
        .package(url: "https://github.com/sindresorhus/KeyboardShortcuts.git", exact: "3.0.1"),
        .package(url: "https://github.com/21-DOT-DEV/swift-secp256k1.git", exact: "0.23.2"),
    ],
    targets: [
        .executableTarget(
            name: "NostrBar",
            dependencies: [
                .product(name: "KeyboardShortcuts", package: "KeyboardShortcuts"),
                .product(name: "P256K", package: "swift-secp256k1"),
            ],
            path: "Sources/NostrBar"
        ),
        .testTarget(
            name: "NostrBarTests",
            dependencies: [
                "NostrBar",
                .product(name: "P256K", package: "swift-secp256k1"),
            ],
            path: "Tests/NostrBarTests"
        )
    ]
)
