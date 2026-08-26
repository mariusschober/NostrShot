import AppKit

@main
enum NostrBarApp {
    static func main() {
        // Single-instance guard: prevent duplicate menu-bar processes
        // (~/Projects/NostrShot/build vs /Applications) doubling resident memory.
        let bundleID = AppConstants.bundleIdentifier
        let running = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
        // If another instance is already running, activate it and exit.
        if running.filter({ $0.processIdentifier != NSRunningApplication.current.processIdentifier }).first != nil {
            // Best-effort activation of existing instance; exit duplicate.
            for app in running where app.processIdentifier != NSRunningApplication.current.processIdentifier {
                app.activate(options: [])
                break
            }
            return
        }

        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
