"""Compose raw 1080x2400 captures into 1080x1920 Play screenshots: one continuous pastel
background runs across the whole set, so the right edge of each shot meets the left edge of
the next; a graphite bezel, one big phone and a caption.

Usage: python _frame.py <raw_dir> <out_dir>   (reads <raw_dir>/captions.json: [{out, image, caption}])
The bezel and screen mask come from tools/faketv/produce.py so the shots match the video.
"""
import importlib.util, json, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SC = os.path.dirname(os.path.abspath(__file__))
FAKETV = os.path.join(SC, "..", "..", "tools", "faketv")
ARGS = sys.argv[1:]
spec = importlib.util.spec_from_file_location("produce", os.path.join(FAKETV, "produce.py"))
sys.argv = ["produce.py", os.path.join(FAKETV, "tour_raw.mp4")]
produce = importlib.util.module_from_spec(spec); spec.loader.exec_module(produce)
W, H, PW, PH, PX = produce.W, produce.H, produce.PW, produce.PH, produce.PX
INK, MUTED = (30, 27, 27), (96, 90, 91)
BASE, EDGE = (238, 234, 231), (218, 212, 209)
# The four pastels of the video background, a touch stronger so the strip reads as one
PASTELS = [(246, 176, 160), (168, 198, 236), (246, 214, 158), (196, 222, 198)]
# Without the header the phone moves up and shows more of its bottom edge
PY = 178


def font(bold, size):
    return ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf", size)


def panorama(n):
    """One background n screens wide. Blobs sit at staggered heights every ~two thirds of a
    screen so every slice gets two or three colours and the seams carry straight through."""
    TW = W * n
    img = Image.new("RGB", (TW, H), BASE)
    blobs = Image.new("RGB", (TW, H), BASE)
    d = ImageDraw.Draw(blobs)
    # One blob per ~three quarters of a screen, so every seam has a blob straddling it
    step = int(W * 0.75)
    x = -200
    i = 0
    while x < TW + 400:
        r = 560 + (i % 3) * 60
        cy = [260, 1560, 900, 1760, 380][i % 5]
        c = PASTELS[i % len(PASTELS)]
        d.ellipse([x - r, cy - r * 0.95, x + r, cy + r * 0.95], fill=c)
        x += step
        i += 1
    blobs = blobs.filter(ImageFilter.GaussianBlur(200))
    img = Image.blend(img, blobs, 0.95)
    # Soft top and bottom fall-off only; left/right must stay seamless
    vig = Image.new("L", (TW, H), 255)
    dv = ImageDraw.Draw(vig)
    for y in range(0, 160):
        a = int(255 * (y / 160) ** 1.5)
        dv.line([(0, y), (TW, y)], fill=a)
        dv.line([(0, H - 1 - y), (TW, H - 1 - y)], fill=a)
    vig = vig.filter(ImageFilter.GaussianBlur(40))
    edge = Image.new("RGB", (TW, H), EDGE)
    return Image.composite(img, edge, vig)


def build_static():
    bz = os.path.join(SC, "prod_bezel.png"); mk = os.path.join(SC, "prod_mask.png")
    produce.PY = PY
    produce.bezel(bz); produce.screen_mask(mk)
    bezel, mask = Image.open(bz).convert("RGBA"), Image.open(mk).convert("L")
    os.remove(bz); os.remove(mk)
    return bezel, mask


def frame(raw_path, caption, out_path, base, bezel, mask):
    canvas = base.copy()
    canvas.paste(bezel, (0, 0), bezel)
    scr = Image.open(raw_path).convert("RGB").resize((PW, PH), Image.LANCZOS)
    canvas.paste(scr, (PX, PY), mask)
    d = ImageDraw.Draw(canvas)
    # Caption never runs wider than the phone screen; long ones shrink to fit
    size = 54
    while True:
        f_cap = font(True, size)
        cw = d.textlength(caption, font=f_cap)
        if cw <= PW or size <= 36: break
        size -= 2
    d.text(((W - cw) / 2, 52 + (54 - size) // 2), caption, font=f_cap, fill=INK)
    canvas.save(out_path, optimize=True)


def main(raw_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    specs = json.load(open(os.path.join(raw_dir, "captions.json"), encoding="utf8"))
    bezel, mask = build_static()
    strip = panorama(len(specs))
    for i, s in enumerate(specs):
        base = strip.crop((i * W, 0, (i + 1) * W, H))
        frame(os.path.join(raw_dir, s["image"] + ".png"), s["caption"], os.path.join(out_dir, s["out"] + ".png"), base, bezel, mask)
        print("framed", s["out"])


if __name__ == "__main__":
    main(ARGS[0], ARGS[1])
