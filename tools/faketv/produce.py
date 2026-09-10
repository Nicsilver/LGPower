"""Turn tour_raw.mp4 + marks.txt into a produced 1080x1920 clip with title card, bezel,
section captions and end card.

Usage: python produce.py <raw.mp4> <marks.txt> <out.mp4>
"""
import os, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter

SC = os.path.dirname(os.path.abspath(sys.argv[1]))  # work next to the raw recording
W, H = 1080, 1920
PW, PH = 690, 1533                # phone screen on the canvas
PX, PY = (W - PW) // 2, 290       # screen top-left
BEZ = 22
FONT_B = "C\\:/Windows/Fonts/segoeuib.ttf"
FONT_R = "C\\:/Windows/Fonts/segoeui.ttf"
RED = "0xE53935"
LAG = 0.5   # scrcpy starts capturing ~0.5 s after the script stamps T0 (measured on the d-pad hold)


def background(path):
    img = Image.new("RGB", (W, H), (12, 12, 14))
    # warm red glow behind the phone, blurred
    glow = Image.new("RGB", (W, H), (12, 12, 14))
    g = ImageDraw.Draw(glow)
    g.ellipse([PX - 260, PY + 200, PX + PW + 260, PY + PH + 100], fill=(70, 22, 22))
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img = Image.blend(img, glow, 1.0)
    d = ImageDraw.Draw(img)
    # bezel with soft shadow
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([PX - BEZ, PY - BEZ + 40, PX + PW + BEZ, PY + PH + BEZ + 40], 76, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(40))
    img.paste(shadow, (0, 0), shadow)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([PX - BEZ, PY - BEZ, PX + PW + BEZ, PY + PH + BEZ], 76, fill=(20, 20, 22), outline=(48, 48, 52), width=2)
    img.save(path)


def screen_mask(path):
    m = Image.new("L", (PW, PH), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, PW - 1, PH - 1], 56, fill=255)
    m.save(path)


def rings():
    out = []
    for i, (r, a) in enumerate([(26, 230), (42, 150), (58, 80)]):
        im = Image.new("RGBA", (2 * r + 8, 2 * r + 8), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.ellipse([4, 4, 2 * r + 4, 2 * r + 4], outline=(255, 255, 255, a), width=6)
        if i == 0:
            d.ellipse([4, 4, 2 * r + 4, 2 * r + 4], fill=(255, 255, 255, 90))
        p = os.path.join(SC, f"prod_ring{i}.png"); im.save(p); out.append((p, r + 4))
    return out


def tap_overlays(taps_path, start, ring_paths, inputs, chain_in):
    """Returns (filter string, label_out) adding expanding rings for each tap/swipe."""
    if not os.path.exists(taps_path):
        return "", chain_in
    sx, sy = PW / 1080, PH / 2400
    events = []  # (t, x, y)
    for line in open(taps_path, encoding="utf8"):
        parts = line.split()
        t0 = float(parts[0]) - LAG - start
        if parts[1] == "tap":
            events.append((t0, int(parts[2]), int(parts[3]), 0.0))
        else:
            x1, y1, x2, y2, ms = map(int, parts[2:7])
            hold = ms / 1000
            if (x1, y1) == (x2, y2):
                events.append((t0, x1, y1, hold))
            else:
                events.append((t0, x1, y1, 0.0)); events.append((t0 + hold, x2, y2, 0.0))
    steps = [(0.0, 0.13), (0.13, 0.26), (0.26, 0.40)]
    # One overlay per ring size; position and visibility are piecewise expressions of t,
    # because an input stream can only feed one filter in a graph.
    f = ""; cur = chain_in
    for i, (a, b) in enumerate(steps):
        p, rad = ring_paths[i]
        xs, ys, en = "-9999", "-9999", []
        for n, (t0, x, y, hold) in enumerate(events):
            cx, cy = PX + x * sx - rad, PY + y * sy - rad
            bb = a + hold if (hold > 0 and i == 1) else b
            cond = f"between(t,{t0 + a:.2f},{t0 + bb:.2f})"
            xs = f"if({cond},{cx:.0f},{xs})"; ys = f"if({cond},{cy:.0f},{ys})"; en.append(cond)
        f += f"[{cur}][{inputs + i}:v]overlay=x='{xs}':y='{ys}':enable='{'+'.join(en)}'[ring{i}];"
        cur = f"ring{i}"
    return f, cur


def esc(s):
    return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace(",", "\\,")


def main(raw, marks_path, out):
    bg = os.path.join(SC, "prod_bg.png"); mask = os.path.join(SC, "prod_mask.png")
    background(bg); screen_mask(mask)
    marks = [l.split(" ", 1) for l in open(marks_path, encoding="utf8").read().strip().splitlines()]
    marks = [(float(t) - LAG, lab.strip()) for t, lab in marks]
    start = marks[0][0] - 0.6
    end = marks[-1][0] + 0.2
    sections = [(t - start, marks[i + 1][0] - start, lab) for i, (t, lab) in enumerate(marks[:-1])]
    dur = end - start

    # caption filters: label + section title, each fading in over 0.25 s
    cap = ""
    for a, b, lab in sections:
        alpha = f"if(lt(t,{a:.2f}+0.25),(t-{a:.2f})/0.25,1)"
        cap += (f",drawtext=fontfile='{FONT_B}':text='{esc(lab)}':fontsize=60:fontcolor=white:"
                f"x=(w-text_w)/2:y=150:alpha='{alpha}':enable='between(t,{a:.2f},{b:.2f})'")
    label = (f",drawtext=fontfile='{FONT_R}':text='LG POWER':fontsize=26:fontcolor={RED}:"
             f"x=(w-text_w)/2:y=104:enable='gte(t,0)'")

    main_mp4 = os.path.join(SC, "prod_main.mp4")
    ring_paths = rings()
    taps_path = os.path.join(os.path.dirname(os.path.abspath(marks_path)), "taps.txt")
    tap_f, tap_out = tap_overlays(taps_path, start, ring_paths, 3, "base")
    fc = (f"[1:v]trim=start={start:.2f}:end={end:.2f},setpts=PTS-STARTPTS,scale={PW}:{PH}[scr];"
          f"[2:v]format=gray[m];[scr][m]alphamerge[scrA];"
          f"[0:v][scrA]overlay={PX}:{PY}:format=auto[base];{tap_f}"
          f"[{tap_out}]null{label}{cap},"
          f"fade=t=in:st=0:d=0.4,fade=t=out:st={dur - 0.5:.2f}:d=0.5,format=yuv420p[v]")
    script = os.path.join(SC, "prod_filter.txt"); open(script, "w", encoding="utf8").write(fc)
    cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", "30", "-i", bg, "-i", raw, "-loop", "1", "-i", mask]
    for p, _ in ring_paths:
        cmd += ["-loop", "1", "-framerate", "30", "-i", p]
    cmd += ["-/filter_complex", script, "-map", "[v]", "-t", f"{dur:.2f}", "-r", "30",
            "-c:v", "libx264", "-preset", "slow", "-crf", "19", main_mp4]
    subprocess.check_call(cmd)

    def card(path, lines, secs):
        txt = ""
        y = 760
        for text, size, color, font in lines:
            txt += (f",drawtext=fontfile='{font}':text='{esc(text)}':fontsize={size}:fontcolor={color}:"
                    f"x=(w-text_w)/2:y={y}")
            y += int(size * 1.45)
        fc = f"[0:v]format=yuv420p{txt},fade=t=in:st=0:d=0.4,fade=t=out:st={secs - 0.5}:d=0.5[v]"
        subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", "30", "-i", bg,
                               "-filter_complex", fc, "-map", "[v]", "-t", str(secs), "-r", "30",
                               "-c:v", "libx264", "-preset", "slow", "-crf", "19", path])

    title = os.path.join(SC, "prod_title.mp4"); endc = os.path.join(SC, "prod_end.mp4")
    card(title, [("LG Power", 120, "white", FONT_B), ("A remote for LG webOS TVs", 44, "0xBBBBBB", FONT_R),
                 ("Wi-Fi control, IR power fallback, no ads", 34, "0x8A8A8A", FONT_R)], 2.4)
    card(endc, [("Free on Google Play", 72, "white", FONT_B), ("Open source, AGPL-3.0", 40, "0xBBBBBB", FONT_R),
                ("github.com/Nicsilver/LGPower", 36, RED, FONT_R)], 2.6)

    lst = os.path.join(SC, "prod_list.txt")
    open(lst, "w").write("\n".join(f"file '{p}'" for p in (title, main_mp4, endc)))
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
                           "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out])
    print("wrote", out, round(os.path.getsize(out) / 1e6, 2), "MB", "sections:", [(round(a, 1), lab) for a, b, lab in sections])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
