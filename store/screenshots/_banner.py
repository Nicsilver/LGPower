"""README banner: the pastel wash from the store set, the app name and pitch on the left,
the remote in a graphite bezel rising from the bottom edge on the right.

Usage: python _banner.py <raw_main_screenshot.png> <out.png>
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SC = os.path.dirname(os.path.abspath(__file__))
W, H = 2400, 760
INK, MUTED = (30, 27, 27), (96, 90, 91)
PASTELS = [(246, 176, 160), (168, 198, 236), (246, 214, 158), (196, 222, 198)]


def font(bold, size):
    return ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf", size)


def background():
    img = Image.new("RGB", (W, H), (238, 234, 231))
    blobs = Image.new("RGB", (W, H), (238, 234, 231))
    d = ImageDraw.Draw(blobs)
    for i, (cx, cy) in enumerate([(120, 120), (760, 760), (1400, 40), (1900, 640), (2400, 200)]):
        r = 620
        d.ellipse([cx - r, cy - r * 0.8, cx + r, cy + r * 0.8], fill=PASTELS[i % 4])
    blobs = blobs.filter(ImageFilter.GaussianBlur(190))
    return Image.blend(img, blobs, 0.95)


def icon(size):
    ic = Image.open(os.path.join(SC, "..", "play_icon_512.png")).convert("RGBA").resize((size, size), Image.LANCZOS)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], int(size * 0.23), fill=255)
    ic.putalpha(m)
    return ic


def phone(raw_path, screen_w):
    """Phone with a graphite bezel, screen from the raw capture; returns an RGBA image."""
    sw = screen_w; sh = int(sw * 2400 / 1080); bez = int(sw * 0.026)
    pad = bez + 40
    im = Image.new("RGBA", (sw + 2 * pad, sh + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad - bez, pad - bez + 30, pad + sw + bez, pad + sh + bez + 30], int(sw * 0.075), fill=(30, 20, 20, 140))
    im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(28)))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([pad - bez, pad - bez, pad + sw + bez, pad + sh + bez], int(sw * 0.072), fill=(28, 28, 31, 255), outline=(96, 96, 102, 255), width=3)
    scr = Image.open(raw_path).convert("RGB").resize((sw, sh), Image.LANCZOS)
    m = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, sw - 1, sh - 1], int(sw * 0.045), fill=255)
    im.paste(scr, (pad, pad), m)
    return im


def main(raw, out):
    im = background()
    d = ImageDraw.Draw(im)
    # phone on the right, cut by the bottom edge
    ph = phone(raw, 520)
    im.paste(ph, (W - ph.width - 150, 90), ph)
    # text block on the left, vertically centred
    ic = icon(150)
    x0 = 170
    im.paste(ic, (x0, 175), ic)
    d.text((x0 + 150 + 42, 165), "LG Power", font=font(True, 132), fill=INK)
    d.text((x0 + 150 + 46, 320), "A remote for LG webOS TVs", font=font(False, 54), fill=MUTED)
    f = font(False, 34); y = 452; x = x0
    for label in ["Wi-Fi + Wake-on-LAN", "IR power fallback", "Several TVs", "No ads, open source"]:
        w = d.textlength(label, font=f) + 52
        d.rounded_rectangle([x, y, x + w, y + 66], 33, outline=(120, 110, 108), width=2)
        d.text((x + 26, y + 12), label, font=f, fill=(60, 55, 55))
        x += w + 18
    d.text((x0, 580), "Free on Google Play  ·  github.com/Nicsilver/LGPower", font=font(False, 34), fill=MUTED)
    im.save(out, optimize=True)
    print("wrote", out, im.size)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
