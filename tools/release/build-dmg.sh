#!/bin/bash
# Builds macos/build/A747-GPS-Logger-<version>.dmg (Release, ad-hoc signed, not notarized).
#   tools/release/build-dmg.sh 1.0.0
set -euo pipefail
VERSION="${1:?version}"
cd "$(dirname "$0")/../../macos"
xcodegen generate --quiet
xcodebuild -project A747.xcodeproj -scheme A747 -configuration Release -derivedDataPath build/release \
  MARKETING_VERSION="$VERSION" CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual build -quiet
APP="build/release/Build/Products/Release/A747 GPS Logger.app"
codesign --verify --deep "$APP"
STAGE=$(mktemp -d)
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
OUT="build/A747-GPS-Logger-$VERSION.dmg"
rm -f "$OUT"
hdiutil create -volname "A747 GPS Logger" -srcfolder "$STAGE" -format UDZO -fs HFS+ "$OUT" -quiet
rm -rf "$STAGE"
echo "$OUT"
