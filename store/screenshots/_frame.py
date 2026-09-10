"""Compose raw 1080x2400 captures into 1080x1920 Play screenshots in the video's look:
pastel background, graphite bezel, one big phone, caption with an accent underline.

Usage: python frame_v2.py <raw_dir> <out_dir>   (reads <raw_dir>/captions.json: [{out, image, caption, sub}])
"""
import importlib.util, json, os, sys
from PIL import Image, ImageDraw, ImageFont

SC = os.path.dirname(os.path.abspath(__file__))
ARGS = sys.argv[1:]
spec = importlib.util.spec_from_file_location("produce", os.path.join(SC, "produce.py"))
sys.argv = ["produce.py", os.path.join(SC, "tour_raw.mp4")]
produce = importlib.util.module_from_spec(spec); spec.loader.exec_module(produce)
W, H, PW, PH, PX, PY = produce.W, produce.H, produce.PW, produce.PH, produce.PX, produce.PY
INK, MUTED, RED = (30, 27, 27), (107, 100, 101), (217, 52, 43)


def font(bold, size):
    return ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf", size)


def build_static():
    bg = os.path.join(SC, "prod_bg.png"); bz = os.path.join(SC, "prod_bezel.png"); mk = os.path.join(SC, "prod_mask.png")
    produce.background(bg); produce.bezel(bz); produce.screen_mask(mk)
    big = Image.open(bg).convert("RGB")
    ox, oy = (produce.BW - W) // 2, (produce.BH - H) // 2
    base = big.crop((ox, oy, ox + W, oy + H))
    return base, Image.open(bz).convert("RGBA"), Image.open(mk).convert("L")


def frame(raw_path, caption, sub, out_path, base, bezel, mask, shift=0):
    canvas = base.copy()
    canvas.paste(bezel, (0, 0), bezel)
    scr = Image.open(raw_path).convert("RGB").resize((PW, PH), Image.LANCZOS)
    canvas.paste(scr, (PX, PY), mask)
    d = ImageDraw.Draw(canvas)
    f1, f0 = font(True, 54), font(False, 22)
    w = d.textlength("LG POWER", font=f0); d.text(((W - w) / 2, 86), "LG POWER", font=f0, fill=MUTED)
    w = d.textlength(caption, font=f1); d.text(((W - w) / 2, 112), caption, font=f1, fill=INK)
    d.rectangle([(W - 52) / 2, 206, (W + 52) / 2, 210], fill=RED)
    canvas.save(out_path, optimize=True)


def main(raw_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    base, bezel, mask = build_static()
    for spec_ in json.load(open(os.path.join(raw_dir, "captions.json"), encoding="utf8")):
        frame(os.path.join(raw_dir, spec_["image"] + ".png"), spec_["caption"], spec_.get("sub", ""), os.path.join(out_dir, spec_["out"] + ".png"), base, bezel, mask)
        print("framed", spec_["out"])


if __name__ == "__main__":
    main(ARGS[0], ARGS[1])
