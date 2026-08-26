#!/bin/bash
# Packages build/NostrBar.app into a drag-to-Applications disk image.
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/build_app.sh

STAGING="build/dmg-staging"
rm -rf "$STAGING" build/NostrBar.dmg
mkdir -p "$STAGING"
cp -R "build/NostrBar.app" "$STAGING/"
ln -s /Applications "$STAGING/Applications"

hdiutil create \
    -volname "NostrBar" \
    -srcfolder "$STAGING" \
    -ov \
    -format UDZO \
    "build/NostrBar.dmg"

rm -rf "$STAGING"
echo "Packaged build/NostrBar.dmg"
