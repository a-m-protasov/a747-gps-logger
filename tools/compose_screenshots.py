"""Composes the README pictures from raw screenshots: Mac windows with a shadow, Android
screens in a phone frame, laid out on a transparent background.

    python3 tools/compose_screenshots.py <dir with the raw screenshots>

Expected files (PNG): mac-track (a Mac window, `screencapture -o -l <window>`), mac-add (the
"Add waypoint" sheet, cropped), android-track, android-map, android-add (`adb exec-out screencap -p`).
Writes docs/images/hero.png and docs/images/waypoints.png. Needs Pillow.
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "images")


def rounded(im: Image.Image, r: int) -> Image.Image:
    """The image with rounded corners (keeps any transparency it already has)."""
    im = im.convert("RGBA")
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), r, fill=255)
    im.putalpha(ImageChops.multiply(im.getchannel("A"), mask))
    return im


def phone(screen: Image.Image, height: int) -> Image.Image:
    """A screenshot in a plain dark phone frame, `height` px tall."""
    s = screen.convert("RGBA")
    bezel = round(s.width * 0.035)
    w, h = s.width + 2 * bezel, s.height + 2 * bezel
    frame = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((0, 0, w - 1, h - 1), round(w * 0.13), fill=(28, 28, 30, 255))
    d.rounded_rectangle((3, 3, w - 4, h - 4), round(w * 0.13) - 3, outline=(70, 70, 74, 255), width=3)
    frame.alpha_composite(rounded(s, round(w * 0.13) - bezel), (bezel, bezel))
    return frame.resize((round(w * height / h), height), Image.LANCZOS)


def shadowed(canvas: Image.Image, im: Image.Image, xy, blur=28, offset=14, alpha=0.32):
    """Pastes `im` at `xy` over a soft shadow cast by its own shape."""
    a = im.getchannel("A").point(lambda v: int(v * alpha))
    sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
    sh.putalpha(a)
    pad = blur * 3
    big = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    big.alpha_composite(sh, (pad, pad))
    big = big.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(big, (xy[0] - pad, xy[1] - pad + offset))
    canvas.alpha_composite(im, xy)


def scale(im: Image.Image, width=None, height=None) -> Image.Image:
    if width:
        return im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    return im.resize((round(im.width * height / im.height), height), Image.LANCZOS)


def main(src: str):
    load = lambda n: Image.open(os.path.join(src, n + ".png")).convert("RGBA")

    # A Mac window and a phone of the same height next to it, tops and bottoms aligned
    mac = scale(load("mac-track"), width=1900)
    ph = phone(load("android-track"), mac.height)
    m = 70
    W, H = m + mac.width + 60 + ph.width + m, m + mac.height + m
    c = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadowed(c, mac, (m, m))
    shadowed(c, ph, (W - m - ph.width, m))
    c.save(os.path.join(OUT, "hero.png"), optimize=True)

    # Adding waypoints: the Mac sheet and two Android screens side by side
    h = 1150
    items = [rounded(scale(load("mac-add"), height=h - 120), 26), phone(load("android-map"), h), phone(load("android-add"), h)]
    gap = 80
    W = m + sum(i.width for i in items) + gap * (len(items) - 1) + m
    c = Image.new("RGBA", (W, m + h + m), (0, 0, 0, 0))
    x = m
    for i in items:
        shadowed(c, i, (x, m + (h - i.height) // 2))
        x += i.width + gap
    c.save(os.path.join(OUT, "waypoints.png"), optimize=True)


if __name__ == "__main__":
    main(sys.argv[1])
