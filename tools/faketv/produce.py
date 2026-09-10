"""Turn tour_raw.mp4 + marks.txt + taps.txt into a produced 1080x1920 60 fps clip:
drifting background with grain, phone in a bezel, section captions, white tap pulses and
travelling drag rings, a theme montage cut in at the "montage" mark, title and end cards.

Usage: python produce.py <raw.mp4> <marks.txt> <out.mp4> [theme_stills_dir]
"""
import glob, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter

SC = os.path.dirname(os.path.abspath(sys.argv[1])) if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
W, H = 1080, 1920
BW, BH = 1300, 2300               # oversized background so it can drift
PW, PH = 690, 1532                # phone screen on the canvas
PX, PY = (W - PW) // 2, 290
BEZ = 22
FPS = 60
FONT_B = "C\\:/Windows/Fonts/segoeuib.ttf"
FONT_R = "C\\:/Windows/Fonts/segoeui.ttf"
RED = "0xE53935"
LAG = 0.63  # script clock vs scrcpy capture, measured on the d-pad hold
PULSE_STEPS, PULSE_DT = 7, 0.05


# ── static art ────────────────────────────────────────────────────────────────

def background(path):
    img = Image.new("RGB", (BW, BH), (13, 13, 16))
    blobs = Image.new("RGB", (BW, BH), (13, 13, 16))
    d = ImageDraw.Draw(blobs)
    d.ellipse([-200, 200, 700, 1100], fill=(96, 26, 30))       # crimson, upper left
    d.ellipse([700, 1300, 1500, 2300], fill=(24, 34, 62))      # slate blue, lower right
    d.ellipse([500, -100, 1200, 500], fill=(40, 30, 50))       # faint violet, top right
    blobs = blobs.filter(ImageFilter.GaussianBlur(220))
    img = Image.blend(img, blobs, 0.9)
    band = Image.new("L", (BW, BH), 0)
    ImageDraw.Draw(band).polygon([(0, 1500), (BW, 300), (BW, 700), (0, 1900)], fill=40)
    band = band.filter(ImageFilter.GaussianBlur(120))
    img.paste(Image.new("RGB", (BW, BH), (255, 235, 230)), (0, 0), band)
    vig = Image.new("L", (BW, BH), 0)
    ImageDraw.Draw(vig).ellipse([-150, -100, BW + 150, BH + 100], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(260))
    dark = Image.new("RGB", (BW, BH), (6, 6, 8))
    img = Image.composite(img, dark, vig)
    img.save(path)


def bezel(path):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([PX - BEZ - 10, PY - BEZ + 50, PX + PW + BEZ + 10, PY + PH + BEZ + 60], 80, fill=(0, 0, 0, 190))
    shadow = shadow.filter(ImageFilter.GaussianBlur(46))
    im.alpha_composite(shadow)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([PX - BEZ, PY - BEZ, PX + PW + BEZ, PY + PH + BEZ], 76, fill=(22, 22, 25, 255), outline=(70, 70, 76, 255), width=2)
    d.arc([PX - BEZ, PY - BEZ, PX - BEZ + 152, PY - BEZ + 152], 180, 270, fill=(150, 150, 160, 140), width=2)
    im.save(path)


def screen_mask(path):
    m = Image.new("L", (PW, PH), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, PW - 1, PH - 1], 56, fill=255)
    m.save(path)


def pulses():
    """White pulse: small disc that grows r 14->38 while fading, one PNG per step, plus a
    travelling ring for drags."""
    out = []
    for i in range(PULSE_STEPS):
        k = i / (PULSE_STEPS - 1)
        r = int(14 + 24 * k)
        a = int(210 * (1 - k) ** 1.3)
        pad = 4
        im = Image.new("RGBA", (2 * r + 2 * pad, 2 * r + 2 * pad), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.ellipse([pad, pad, pad + 2 * r, pad + 2 * r], fill=(255, 255, 255, a // 2), outline=(255, 255, 255, a), width=3)
        p = os.path.join(SC, f"prod_pulse{i}.png"); im.save(p); out.append((p, r + pad))
    r, pad = 22, 4
    im = Image.new("RGBA", (2 * r + 2 * pad, 2 * r + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse([pad, pad, pad + 2 * r, pad + 2 * r], fill=(255, 255, 255, 70), outline=(255, 255, 255, 200), width=3)
    p = os.path.join(SC, "prod_drag.png"); im.save(p); out.append((p, r + pad))
    return out


def esc(s):
    return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace(",", "\\,")


# ── events ────────────────────────────────────────────────────────────────────

def read_taps(path):
    ev = []
    for line in open(path, encoding="utf8"):
        p = line.split()
        t = float(p[0]) - LAG
        if p[1] == "tap":
            ev.append(dict(kind="tap", t=t, x=int(p[2]), y=int(p[3])))
        else:
            x1, y1, x2, y2, ms = map(int, p[2:7])
            if (x1, y1) == (x2, y2):
                ev.append(dict(kind="hold", t=t, x=x1, y=y1, dur=ms / 1000))
            else:
                ev.append(dict(kind="drag", t=t, x=x1, y=y1, x2=x2, y2=y2, dur=ms / 1000))
    return ev


def marker_filters(events, t_from, t_to, art, first_input, chain_in):
    """Pulses for taps/holds and a travelling ring for drags; times relative to t_from."""
    sx, sy = PW / 1080, PH / 2400
    f, cur = "", chain_in
    evs = [e for e in events if t_from - 1 <= e["t"] <= t_to]
    for i in range(PULSE_STEPS):
        p, rad = art[i]
        xs, ys, en = "-9999", "-9999", []
        for e in evs:
            if e["kind"] == "drag":
                continue
            t0 = e["t"] - t_from
            hold = e.get("dur", 0.0) if e["kind"] == "hold" else 0.0
            a = t0 + i * PULSE_DT + (hold if i > 2 else 0)
            b = a + PULSE_DT + (hold if i == 2 else 0)
            cx, cy = PX + e["x"] * sx - rad, PY + e["y"] * sy - rad
            c = f"between(t,{a:.3f},{b:.3f})"
            xs = f"if({c},{cx:.0f},{xs})"; ys = f"if({c},{cy:.0f},{ys})"; en.append(c)
        if en:
            f += f"[{cur}][{first_input + i}:v]overlay=x='{xs}':y='{ys}':enable='{'+'.join(en)}'[mk{i}];"
            cur = f"mk{i}"
    p, rad = art[PULSE_STEPS]
    xs, ys, en = "-9999", "-9999", []
    for e in evs:
        if e["kind"] != "drag":
            continue
        a = e["t"] - t_from; b = a + e["dur"]
        x1, y1 = PX + e["x"] * sx - rad, PY + e["y"] * sy - rad
        x2, y2 = PX + e["x2"] * sx - rad, PY + e["y2"] * sy - rad
        c = f"between(t,{a:.3f},{b + 0.08:.3f})"
        k = f"min(1,(t-{a:.3f})/{e['dur']:.3f})"
        xs = f"if({c},{x1:.0f}+({x2 - x1:.0f})*{k},{xs})"; ys = f"if({c},{y1:.0f}+({y2 - y1:.0f})*{k},{ys})"; en.append(c)
    if en:
        f += f"[{cur}][{first_input + PULSE_STEPS}:v]overlay=x='{xs}':y='{ys}':enable='{'+'.join(en)}'[mkd];"
        cur = "mkd"
    return f, cur


# ── rendering ─────────────────────────────────────────────────────────────────

def render(out, dur, t_offset, bg, bz, mask, art, src=None, src_from=0.0, captions=(), events=(), t_from=0.0, cards=()):
    """One segment. captions: (a, b, text) relative to segment start. cards: (text, size, color, font, y)."""
    inputs = ["-loop", "1", "-framerate", str(FPS), "-i", bg, "-loop", "1", "-framerate", str(FPS), "-i", bz]
    n = 2
    fc = (f"[0:v]crop={W}:{H}:x='({BW - W})*(0.5+0.5*sin((t+{t_offset:.1f})/13))':y='({BH - H})*(0.5+0.5*cos((t+{t_offset:.1f})/17))',"
          f"noise=alls=7:allf=t+u[bgd];[bgd][1:v]overlay=0:0[base];")
    cur = "base"
    if src:
        inputs += ["-ss", f"{src_from:.3f}", "-i", src, "-loop", "1", "-i", mask]
        fc += (f"[{n}:v]setpts=PTS-STARTPTS,scale={PW}:{PH}[scr];[{n + 1}:v]format=gray[m];[scr][m]alphamerge[scrA];"
               f"[{cur}][scrA]overlay={PX}:{PY}:format=auto[withscr];")
        cur = "withscr"; n += 2
    for p, _ in art:
        inputs += ["-loop", "1", "-framerate", str(FPS), "-i", p]
    if events:
        mf, cur = marker_filters(events, t_from, t_from + dur, art, n, cur)
        fc += mf
    txt = ""
    for a, b, text in captions:
        al = f"if(lt(t,{a:.2f}+0.3),(t-{a:.2f})/0.3,1)"
        txt += (f",drawtext=fontfile='{FONT_B}':text='{esc(text)}':fontsize=58:fontcolor=white:x=(w-text_w)/2:y=142:alpha='{al}':enable='between(t,{a:.2f},{b:.2f})'"
                f",drawbox=x=(iw-56)/2:y=224:w=56:h=4:color={RED}@0.9:t=fill:enable='between(t,{a:.2f},{b:.2f})'")
    if captions:
        txt += f",drawtext=fontfile='{FONT_R}':text='LG POWER':fontsize=24:fontcolor=white@0.55:x=(w-text_w)/2:y=100"
    for text, size, color, font, y in cards:
        txt += f",drawtext=fontfile='{font}':text='{esc(text)}':fontsize={size}:fontcolor={color}:x=(w-text_w)/2:y={y}"
    fc += f"[{cur}]null{txt},fade=t=in:st=0:d=0.35,fade=t=out:st={dur - 0.35:.2f}:d=0.35,format=yuv420p[v]"
    script = out + ".filter"; open(script, "w", encoding="utf8").write(fc)
    subprocess.check_call(["ffmpeg", "-v", "error", "-y"] + inputs + ["-/filter_complex", script, "-map", "[v]", "-t", f"{dur:.3f}", "-r", str(FPS),
                           "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", out])


def montage_source(stills, out, each=0.3):
    lst = out + ".txt"
    with open(lst, "w", encoding="utf8") as f:
        for s in stills:
            f.write(f"file '{s}'\nduration {each}\n")
        f.write(f"file '{stills[-1]}'\n")
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-vf", f"scale={PW}:{PH},fps={FPS}",
                           "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", out])
    return each * len(stills)


def main(raw, marks_path, out, stills_dir=None):
    bg = os.path.join(SC, "prod_bg.png"); bz = os.path.join(SC, "prod_bezel.png"); mask = os.path.join(SC, "prod_mask.png")
    background(bg); bezel(bz); screen_mask(mask)
    art = pulses()
    marks = [(float(l.split(" ", 1)[0]) - LAG, l.split(" ", 1)[1].strip()) for l in open(marks_path, encoding="utf8").read().strip().splitlines()]
    events = read_taps(os.path.join(os.path.dirname(os.path.abspath(marks_path)), "taps.txt"))
    start = marks[0][0] - 0.5
    end = marks[-1][0] + 0.2
    mont_i = next((i for i, (t, l) in enumerate(marks) if l == "montage"), None)
    parts = []

    def section_caps(t_from, t_to):
        caps = []
        for i, (t, lab) in enumerate(marks[:-1]):
            if lab == "montage":
                continue
            a, b = max(t, t_from), min(marks[i + 1][0], t_to)
            if b > a:
                caps.append((a - t_from, b - t_from, lab))
        return caps

    title = os.path.join(SC, "prod_title.mp4")
    render(title, 2.4, 0, bg, bz, mask, art, cards=[("LG Power", 118, "white", FONT_B, 780), ("A remote for LG webOS TVs", 42, "0xC8C8C8", FONT_R, 950),
                                                     ("Wi-Fi control, IR power fallback, no ads", 32, "0x8E8E8E", FONT_R, 1020)])
    parts.append(title); toff = 2.4

    if mont_i is not None and stills_dir:
        t_mont = marks[mont_i][0]; t_after = marks[mont_i + 1][0]
        a_mp4 = os.path.join(SC, "prod_a.mp4")
        render(a_mp4, t_mont - start, toff, bg, bz, mask, art, src=raw, src_from=start, captions=section_caps(start, t_mont), events=events, t_from=start)
        parts.append(a_mp4); toff += t_mont - start
        stills = sorted(glob.glob(os.path.join(stills_dir, "*.png")))
        msrc = os.path.join(SC, "prod_montage_src.mp4"); mdur = montage_source(stills, msrc)
        m_mp4 = os.path.join(SC, "prod_montage.mp4")
        render(m_mp4, mdur, toff, bg, bz, mask, art, src=msrc, captions=[(0, mdur, "Eight themes and an editor")])
        parts.append(m_mp4); toff += mdur
        b_mp4 = os.path.join(SC, "prod_b.mp4")
        render(b_mp4, end - t_after, toff, bg, bz, mask, art, src=raw, src_from=t_after, captions=section_caps(t_after, end), events=events, t_from=t_after)
        parts.append(b_mp4); toff += end - t_after
    else:
        a_mp4 = os.path.join(SC, "prod_a.mp4")
        render(a_mp4, end - start, toff, bg, bz, mask, art, src=raw, src_from=start, captions=section_caps(start, end), events=events, t_from=start)
        parts.append(a_mp4); toff += end - start

    endc = os.path.join(SC, "prod_end.mp4")
    render(endc, 2.6, toff, bg, bz, mask, art, cards=[("Free on Google Play", 70, "white", FONT_B, 820), ("Open source, AGPL-3.0", 38, "0xC8C8C8", FONT_R, 940),
                                                        ("github.com/Nicsilver/LGPower", 34, RED, FONT_R, 1010)])
    parts.append(endc)
    lst = os.path.join(SC, "prod_list.txt")
    open(lst, "w", encoding="utf8").write("\n".join(f"file '{p}'" for p in parts))
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", out])
    print("wrote", out, round(os.path.getsize(out) / 1e6, 2), "MB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
