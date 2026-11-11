#!/bin/sh
# Renders shared/brand/*.svg into the app icon assets (needs rsvg-convert: brew install librsvg).
set -e
cd "$(dirname "$0")/../.."
B=shared/brand
A=macos/A747/Resources/Assets.xcassets
cp $B/logger.svg $A/LoggerGlyph.imageset/logger.svg
for s in 16 32 128 256 512; do for sc in 1 2; do
  px=$((s*sc)); rsvg-convert -w $px -h $px $B/icon.svg -o $A/AppIcon.appiconset/icon_${s}x${s}@${sc}x.png
done; done
R=android/app/src/main/res
rsvg-convert -h 264 $B/logger.svg -o $R/drawable-nodpi/logger.png
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  n=${d%%:*}; f=${d##*:}
  a=$(python3 -c "print(round(108*$f))"); l=$(python3 -c "print(round(48*$f))")
  rsvg-convert -w $a -h $a $B/icon-foreground.svg -o $R/mipmap-$n/ic_launcher_foreground.png
  cp $R/mipmap-$n/ic_launcher_foreground.png $R/mipmap-$n/ic_launcher_monochrome.png
  rsvg-convert -w $a -h $a $B/icon-background.svg -o $R/mipmap-$n/ic_launcher_background.png
  rsvg-convert -w $l -h $l $B/icon.svg -o $R/mipmap-$n/ic_launcher.png
done
rsvg-convert -w 256 $B/icon.svg -o docs/images/icon.png
