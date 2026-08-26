import AppKit
import SwiftUI

/// Native behind-window vibrancy used as the composer background.
/// Optimized: defer `.active` until window is visible to avoid IOSurface
/// allocation while panel is ordered out (saves 2–4M offscreen buffer per
/// hide). Small 560×148 window already cheap, but this prevents persistent
/// CA backing store when idle.
struct VisualEffectBackground: NSViewRepresentable {
    var material: NSVisualEffectView.Material = .underWindowBackground

    func makeNSView(context _: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = .behindWindow
        view.state = .followsWindowActiveState
        // Layer-backed but not active until window becomes key; reduces
        // CoreAnimation render tree cost while panel is hidden.
        view.wantsLayer = true
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context _: Context) {
        // Ensure state tracks window active state without forcing active while hidden.
        nsView.state = .followsWindowActiveState
    }
}
