#!/bin/bash
# Builds NostrBar.app from the SwiftPM release build output.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG=release
NAME=NostrBar
VERSION=$(sed -n 's/.*static let version = "\([^"]*\)".*/\1/p' Sources/NostrBar/AppConstants.swift)

swift build -c "$CONFIG"
BIN="$(swift build -c "$CONFIG" --show-bin-path)"

APP="build/$NAME.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# Generate the app icon if missing.
if [ ! -f build/AppIcon.icns ]; then
    swift scripts/make_icon.swift build/icon.iconset >/dev/null
    iconutil -c icns build/icon.iconset -o build/AppIcon.icns
fi
cp build/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleExecutable</key>
	<string>$NAME</string>
	<key>CFBundleIdentifier</key>
	<string>com.nostrbar.app</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>$NAME</string>
	<key>CFBundleDisplayName</key>
	<string>$NAME</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>$VERSION</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>CFBundleIconFile</key>
	<string>AppIcon</string>
	<key>LSMinimumSystemVersion</key>
	<string>14.0</string>
	<key>LSUIElement</key>
	<true/>
	<key>NSHumanReadableCopyright</key>
	<string>MIT License</string>
	<key>NSMicrophoneUsageDescription</key>
	<string>NostrBar uses the microphone only when you dictate a note.</string>
	<key>NSSpeechRecognitionUsageDescription</key>
	<string>Dictation runs entirely on this Mac; your speech never leaves it.</string>
	<key>NSPrincipalClass</key>
	<string>NSApplication</string>
</dict>
</plist>
PLIST

cp "$BIN/$NAME" "$APP/Contents/MacOS/$NAME"
# Strip debug symbols and local symbols: saves ~200K __LINKEDIT resident and
# reduces file size. No sanitizer in Release; dsym already absent.
strip -x -S "$APP/Contents/MacOS/$NAME" 2>/dev/null || true
codesign --force --sign - "$APP"

echo "Built $APP"
