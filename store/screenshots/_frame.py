"""Compose raw 1080x2400 emulator captures into 1080x1920 Play screenshots.

Usage: python frame.py <raw_dir> <out_dir>
Reads <raw_dir>/captions.json: list of {out, images:[raw names], caption, sub, bg, fg}.
"""
import json, sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920
FONT = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_SUB = r"C:\Windows\Fonts\segoeui.ttf"


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(raw, target_w, radius=64, bezel_pad=22):
    scale = target_w / raw.width
    ph = int(raw.height * scale)
    scr = raw.resize((target_w, ph), Image.LANCZOS)
    bz = Image.new("RGBA", (target_w + 2 * bezel_pad, ph + 2 * bezel_pad), (0, 0, 0, 0))
    ImageDraw.Draw(bz).rounded_rectangle([0, 0, bz.width - 1, bz.height - 1], radius + bezel_pad, fill=(18, 18, 20, 255))
    scr = rounded(scr, radius)
    bz.paste(scr, (bezel_pad, bezel_pad), scr)
    return bz


def paste_with_shadow(canvas, im, x, y):
    shadow = Image.new("RGBA", (im.width + 160, im.height + 160), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([80, 100, 80 + im.width, 100 + im.height], 80, fill=(0, 0, 0, 120))
    shadow = shadow.filter(ImageFilter.GaussianBlur(30))
    canvas.paste(shadow, (x - 80, y - 80), shadow)
    canvas.paste(im, (x, y), im)


def frame(spec, raw_dir, out_path):
    bg, fg = tuple(spec["bg"]), tuple(spec["fg"])
    canvas = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(canvas)
    f1 = ImageFont.truetype(FONT, 60)
    f2 = ImageFont.truetype(FONT_SUB, 40)
    y = 100
    for line in spec["caption"].split("\n"):
        w = d.textlength(line, font=f1)
        d.text(((W - w) / 2, y), line, font=f1, fill=fg)
        y += 76
    sub = spec.get("sub", "")
    if sub:
        w = d.textlength(sub, font=f2)
        muted = tuple(int(c * 0.7 + (255 - c) * 0.0) if sum(bg) < 300 else int(c + (128 - c) * 0.5) for c in fg)
        d.text(((W - w) / 2, y + 8), sub, font=f2, fill=muted)
        y += 56
    top = y + 50
    raws = [Image.open(os.path.join(raw_dir, n + ".png")).convert("RGB") for n in spec["images"]]
    if len(raws) == 1:
        ph = phone(raws[0], 640, radius=50, bezel_pad=18)
        paste_with_shadow(canvas, ph, (W - ph.width) // 2, top)
    elif spec.get("layout") == "row":
        # three phones side by side, no overlap, for screens whose content sits low
        phones = [phone(r, 344, radius=32, bezel_pad=10) for r in raws]
        gap = (W - sum(p.width for p in phones)) // 4
        x = gap
        ptop = top + max(0, (H - top - phones[0].height) // 2 - 60)
        for p in phones:
            paste_with_shadow(canvas, p, x, ptop)
            x += p.width + gap
    else:
        # fan of three: middle one in front, sides tucked behind and lower
        mid = phone(raws[1], 560, radius=44, bezel_pad=16)
        side_l = phone(raws[0], 500, radius=40, bezel_pad=14)
        side_r = phone(raws[2], 500, radius=40, bezel_pad=14)
        paste_with_shadow(canvas, side_l, 20, top + 90)
        paste_with_shadow(canvas, side_r, W - 20 - side_r.width, top + 90)
        paste_with_shadow(canvas, mid, (W - mid.width) // 2, top)
    canvas.save(out_path, optimize=True)


def main(raw_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    specs = json.load(open(os.path.join(raw_dir, "captions.json"), encoding="utf8"))
    for spec in specs:
        out = os.path.join(out_dir, spec["out"] + ".png")
        frame(spec, raw_dir, out)
        print("framed", spec["out"])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
