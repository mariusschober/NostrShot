// Generates build/AppIcon.icns from a programmatically drawn icon.
// Run via: swift scripts/make_icon.swift <output-dir>
import AppKit

let outputDir = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "build/icon.iconset"

func renderIcon(size: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: size,
        pixelsHigh: size,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    )!
    rep.size = NSSize(width: size, height: size)

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(size)

    // Squircle-ish rounded background with a subtle vertical gradient.
    let inset = s * 0.09
    let rect = NSRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let radius = rect.width * 0.225
    let path = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
    path.addClip()

    let gradient = NSGradient(
        starting: NSColor(calibratedWhite: 0.30, alpha: 1),
        ending: NSColor(calibratedWhite: 0.13, alpha: 1)
    )!
    gradient.draw(in: path, angle: -90)

    // Speech bubble glyph.
    let symbol = NSImage(systemSymbolName: "text.bubble.fill", accessibilityDescription: nil)!
    let config = NSImage.SymbolConfiguration(pointSize: s * 0.52, weight: .medium)
    let configured = symbol.withSymbolConfiguration(config)!
    let glyphSize = configured.size
    let drawRect = NSRect(
        x: (s - glyphSize.width) / 2,
        y: (s - glyphSize.height) / 2,
        width: glyphSize.width,
        height: glyphSize.height
    )
    NSColor.white.withAlphaComponent(0.96).set()
    configured.draw(in: drawRect)

    NSGraphicsContext.restoreGraphicsState()
    return rep
}

try? FileManager.default.createDirectory(atPath: outputDir, withIntermediateDirectories: true)

let entries: [(name: String, size: Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]

for entry in entries {
    let rep = renderIcon(size: entry.size)
    guard let data = rep.representation(using: .png, properties: [:]) else {
        FileHandle.standardError.write("failed to render \(entry.name)\n".data(using: .utf8)!)
        continue
    }
    try data.write(to: URL(fileURLWithPath: "\(outputDir)/\(entry.name)"))
}
print("iconset written to \(outputDir)")
