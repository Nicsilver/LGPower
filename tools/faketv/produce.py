"""Turn tour_raw.mp4 + marks.txt + taps.txt into a produced 1080x1920 60 fps clip.

Each section is rendered as its own segment (light drifting background, big phone in a
graphite bezel, caption, tap pulses, drag rings, a slow push-in towards the section's
focus), then the segments are joined with short cross-dissolves. A theme montage from
stills is cut in at the "montage" mark. Title and end cards bookend it.

Usage: python produce.py <raw.mp4> <marks.txt> <out.mp4> [theme_stills_dir]
"""
import glob, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter

SC = os.path.dirname(os.path.abspath(sys.argv[1])) if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
W, H = 1080, 1920
BW, BH = 1300, 2300               # oversized background so it can drift
PW, PH = 780, 1734                # phone screen on the canvas (1080x2400 scaled)
PX, PY = (W - PW) // 2, 236
BEZ = 20
FPS = 60
FONT_B = "C\\:/Windows/Fonts/segoeuib.ttf"
FONT_R = "C\\:/Windows/Fonts/segoeui.ttf"
INK = "0x1E1B1B"
MUTED = "0x6B6465"
RED = "0xD9342B"
LAG = 0.54  # script clock vs scrcpy capture, measured on the d-pad hold (varies per run)
PULSE_STEPS, PULSE_DT = 7, 0.05
XFADE = 0.35
ZOOM_ENABLED = False
SPEED = 1.15   # global playback speed-up; marks and taps are scaled to match   # the push-in was tried and rejected; kept behind a switch

# where the slow push-in aims, per section, in raw phone pixels
FOCUS = {
    "D-pad": (540, 1400), "Volume": (540, 1300), "Touchpad": (540, 1200), "Type": (540, 1800),
    "Numpad": (540, 900), "Picture": (540, 1650), "Input": (540, 1850), "App": (540, 420),
    "Themes": (540, 1200), "Eight": (540, 1200), "Screen": (300, 1900),
}


# ── static art ────────────────────────────────────────────────────────────────

def background(path):
    img = Image.new("RGB", (BW, BH), (238, 234, 231))
    blobs = Image.new("RGB", (BW, BH), (238, 234, 231))
    d = ImageDraw.Draw(blobs)
    d.ellipse([-250, 150, 650, 1050], fill=(246, 190, 178))     # coral, upper left
    d.ellipse([700, 1300, 1550, 2250], fill=(184, 206, 234))    # sky, lower right
    d.ellipse([600, -150, 1350, 550], fill=(244, 222, 180))     # pale gold, top right
    d.ellipse([-100, 1500, 500, 2300], fill=(214, 226, 214))    # sage, bottom left
    blobs = blobs.filter(ImageFilter.GaussianBlur(200))
    img = Image.blend(img, blobs, 0.85)
    vig = Image.new("L", (BW, BH), 0)
    ImageDraw.Draw(vig).ellipse([-200, -150, BW + 200, BH + 150], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(300))
    edge = Image.new("RGB", (BW, BH), (214, 208, 205))
    img = Image.composite(img, edge, vig)
    img.save(path)


def bezel(path):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([PX - BEZ - 8, PY - BEZ + 60, PX + PW + BEZ + 8, PY + PH + BEZ + 70], 60, fill=(30, 20, 20, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(50))
    im.alpha_composite(shadow)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([PX + PW + BEZ - 2, PY + 300, PX + PW + BEZ + 8, PY + 420], 5, fill=(40, 40, 44, 255))
    d.rounded_rectangle([PX + PW + BEZ - 2, PY + 470, PX + PW + BEZ + 8, PY + 700], 5, fill=(40, 40, 44, 255))
    d.rounded_rectangle([PX - BEZ - 8, PY + 380, PX - BEZ + 2, PY + 520], 5, fill=(40, 40, 44, 255))
    d.rounded_rectangle([PX - BEZ, PY - BEZ, PX + PW + BEZ, PY + PH + BEZ], 56, fill=(28, 28, 31, 255), outline=(96, 96, 102, 255), width=3)
    d.rounded_rectangle([PX - BEZ + 4, PY - BEZ + 4, PX + PW + BEZ - 4, PY + PH + BEZ - 4], 52, outline=(12, 12, 14, 255), width=3)
    im.save(path)


def screen_mask(path):
    m = Image.new("L", (PW, PH), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, PW - 1, PH - 1], 34, fill=255)
    m.save(path)


def pulses():
    out = []
    for i in range(PULSE_STEPS):
        k = i / (PULSE_STEPS - 1)
        r = int(15 + 26 * k)
        a = int(215 * (1 - k) ** 1.3)
        pad = 4
        im = Image.new("RGBA", (2 * r + 2 * pad, 2 * r + 2 * pad), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.ellipse([pad, pad, pad + 2 * r, pad + 2 * r], fill=(255, 255, 255, a // 2), outline=(255, 255, 255, a), width=3)
        p = os.path.join(SC, f"prod_pulse{i}.png"); im.save(p); out.append((p, r + pad))
    r, pad = 24, 4
    im = Image.new("RGBA", (2 * r + 2 * pad, 2 * r + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse([pad, pad, pad + 2 * r, pad + 2 * r], fill=(255, 255, 255, 70), outline=(255, 255, 255, 210), width=3)
    p = os.path.join(SC, "prod_drag.png"); im.save(p); out.append((p, r + pad))
    return out


from PIL import ImageFont

STORE = r"C:\Programming\LGPowerWidget\store"


def _font(bold, size):
    return ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf", size)


def _card_base():
    im = Image.new("RGB", (PW, PH), (20, 20, 22))
    glow = Image.new("RGB", (PW, PH), (20, 20, 22))
    ImageDraw.Draw(glow).ellipse([-200, -300, PW + 200, 700], fill=(120, 30, 28))
    glow = glow.filter(ImageFilter.GaussianBlur(180))
    return Image.blend(im, glow, 0.55)


def _center_text(d, y, text, font, fill):
    w = d.textlength(text, font=font); d.text(((PW - w) / 2, y), text, font=font, fill=fill); return y + font.size


def _icon(size):
    ic = Image.open(os.path.join(STORE, "play_icon_512.png")).convert("RGBA").resize((size, size), Image.LANCZOS)
    m = Image.new("L", (size, size), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], int(size * 0.22), fill=255)
    ic.putalpha(m); return ic


def _pill(d, cx, y, text, font, fill, ink, pad=26, h=None):
    w = d.textlength(text, font=font); h = h or font.size + 28
    d.rounded_rectangle([cx - w / 2 - pad, y, cx + w / 2 + pad, y + h], h // 2, fill=fill)
    d.text((cx - w / 2, y + (h - font.size) / 2 - 4), text, font=font, fill=ink)
    return y + h


def title_card(path):
    im = _card_base(); d = ImageDraw.Draw(im)
    ic = _icon(300); im.paste(ic, ((PW - 300) // 2, 400), ic)
    y = _center_text(d, 760, "LG Power", _font(True, 112), (255, 255, 255))
    y = _center_text(d, y + 22, "A remote for LG webOS TVs", _font(False, 42), (200, 200, 205))
    f = _font(False, 30); y += 70; x = PW / 2
    labels = ["Wi-Fi control", "IR power fallback", "No ads"]
    widths = [d.textlength(s, font=f) + 44 for s in labels]; gap = 18; total = sum(widths) + gap * 2; x0 = (PW - total) / 2
    for s, w in zip(labels, widths):
        d.rounded_rectangle([x0, y, x0 + w, y + 58], 29, outline=(110, 110, 118), width=2)
        d.text((x0 + 22, y + 10), s, font=f, fill=(210, 210, 215)); x0 += w + gap
    im.save(path)


def end_card(path):
    im = _card_base(); d = ImageDraw.Draw(im)
    ic = _icon(210); im.paste(ic, ((PW - 210) // 2, 250), ic)
    y = _center_text(d, 500, "LG Power", _font(True, 92), (255, 255, 255))
    y = _center_text(d, y + 14, "Remote for LG webOS TVs", _font(False, 38), (200, 200, 205))
    y = _pill(d, PW / 2, y + 60, "Free on Google Play", _font(True, 40), (255, 255, 255), (20, 20, 22), pad=40)
    y += 26
    y = _center_text(d, y, "Open source · AGPL-3.0", _font(False, 34), (170, 170, 178))
    y = _center_text(d, y + 8, "github.com/Nicsilver/LGPower", _font(False, 34), (255, 106, 92))
    y += 56; d.line([(90, y), (PW - 90, y)], fill=(60, 60, 66), width=2); y += 44
    f = _font(False, 34)
    for line in ["Power, d-pad, touchpad, keyboard", "Pickers, numpad, app shortcuts", "Widgets, eight themes, an editor"]:
        w = d.textlength(line, font=f); x = (PW - w) / 2
        d.ellipse([x - 34, y + 13, x - 18, y + 29], fill=(217, 52, 43)); d.text((x, y), line, font=f, fill=(225, 225, 230)); y += 56
    # QR to the Play listing, in a white tile
    qr = Image.open(os.path.join(SC, "qr_play.png")).convert("RGB").resize((300, 300), Image.NEAREST)
    tile = Image.new("RGB", (340, 340), (255, 255, 255)); tile.paste(qr, (20, 20))
    tm = Image.new("L", (340, 340), 0); ImageDraw.Draw(tm).rounded_rectangle([0, 0, 339, 339], 28, fill=255)
    ty = y + 40; im.paste(tile, ((PW - 340) // 2, ty), tm)
    _center_text(d, ty + 340 + 22, "Scan to install", _font(False, 30), (200, 200, 205))
    _center_text(d, PH - 90, "Independent app, not affiliated with LG", _font(False, 24), (120, 120, 128))
    im.save(path)


def esc(s):
    return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace(",", "\\,")


# ── events ────────────────────────────────────────────────────────────────────

def read_taps(path):
    ev = []
    for line in open(path, encoding="utf8"):
        p = line.split()
        t = (float(p[0]) - LAG) / SPEED
        if p[1] == "tap":
            ev.append(dict(kind="tap", t=t, x=int(p[2]), y=int(p[3])))
        else:
            x1, y1, x2, y2, ms = map(int, p[2:7])
            ease = len(p) > 7 and p[7] == "ease"
            if (x1, y1) == (x2, y2):
                ev.append(dict(kind="hold", t=t, x=x1, y=y1, dur=ms / 1000 / SPEED))
            else:
                ev.append(dict(kind="drag", t=t, x=x1, y=y1, x2=x2, y2=y2, dur=ms / 1000 / SPEED, ease=ease))
    return ev


def marker_filters(events, t_from, t_to, art, first_input, chain_in):
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
        if e.get("ease"):
            k = f"(1-pow(1-{k},2))"   # ease-out: fast start, slow end
        xs = f"if({c},{x1:.0f}+({x2 - x1:.0f})*{k},{xs})"; ys = f"if({c},{y1:.0f}+({y2 - y1:.0f})*{k},{ys})"; en.append(c)
    if en:
        f += f"[{cur}][{first_input + PULSE_STEPS}:v]overlay=x='{xs}':y='{ys}':enable='{'+'.join(en)}'[mkd];"
        cur = "mkd"
    return f, cur


# ── rendering ─────────────────────────────────────────────────────────────────

def render(out, dur, t_offset, bg, bz, mask, art, src=None, src_from=0.0, caption=None, events=(), t_from=0.0, cards=(), focus=None, zoom=1.045, speed=1.0, still=None):
    """One segment. caption: text shown for the whole segment. focus: raw phone (x, y) the push-in aims at."""
    inputs = ["-loop", "1", "-framerate", str(FPS), "-i", bg, "-loop", "1", "-framerate", str(FPS), "-i", bz]
    n = 2
    fc = (f"[0:v]crop={W}:{H}:x='({BW - W})*(0.5+0.5*sin((t+{t_offset:.1f})/13))':y='({BH - H})*(0.5+0.5*cos((t+{t_offset:.1f})/17))',"
          f"noise=alls=4:allf=t+u[bgd];[bgd][1:v]overlay=0:0[base];")
    cur = "base"
    if still:
        inputs += ["-loop", "1", "-framerate", str(FPS), "-i", still, "-loop", "1", "-i", mask]
        fc += (f"[{n}:v]scale={PW}:{PH}[scr];[{n + 1}:v]format=gray[m];[scr][m]alphamerge[scrA];"
               f"[{cur}][scrA]overlay={PX}:{PY}:format=auto[withscr];")
        cur = "withscr"; n += 2
    if src:
        inputs += ["-ss", f"{src_from * speed:.3f}", "-i", src, "-loop", "1", "-i", mask]
        fc += (f"[{n}:v]setpts=(PTS-STARTPTS)/{speed},scale={PW}:{PH}[scr];[{n + 1}:v]format=gray[m];[scr][m]alphamerge[scrA];"
               f"[{cur}][scrA]overlay={PX}:{PY}:format=auto[withscr];")
        cur = "withscr"; n += 2
    for p, _ in art:
        inputs += ["-loop", "1", "-framerate", str(FPS), "-i", p]
    if events:
        mf, cur = marker_filters(events, t_from, t_from + dur, art, n, cur)
        fc += mf
    zoomf = ""
    if focus and ZOOM_ENABLED:
        fx, fy = PX + focus[0] * PW / 1080, PY + focus[1] * PH / 2400
        cxp, cyp = (W / 2 + fx) / 2, (H / 2 + fy) / 2
        frames = int(dur * FPS)
        z = f"1+({zoom - 1})*min(1,on/{frames})"
        zoomf = (f",scale={W * 2}:{H * 2}:flags=bicubic,zoompan=z='{z}':x='{2 * cxp:.0f}-(iw/zoom)/2':y='{2 * cyp:.0f}-(ih/zoom)/2':d=1:s={W}x{H}:fps={FPS}")
    txt = ""
    if caption:
        txt += (f",drawtext=fontfile='{FONT_R}':text='LG POWER':fontsize=22:fontcolor={MUTED}:x=(w-text_w)/2:y=86"
                f",drawtext=fontfile='{FONT_B}':text='{esc(caption)}':fontsize=54:fontcolor={INK}:x=(w-text_w)/2:y=118"
                f",drawbox=x=(iw-52)/2:y=194:w=52:h=4:color={RED}@0.95:t=fill")
    for text, size, color, font, y in cards:
        txt += f",drawtext=fontfile='{font}':text='{esc(text)}':fontsize={size}:fontcolor={color}:x=(w-text_w)/2:y={y}"
    txt = zoomf + txt
    fc += f"[{cur}]null{txt},format=yuv420p[v]"
    script = out + ".filter"; open(script, "w", encoding="utf8").write(fc)
    subprocess.check_call(["ffmpeg", "-v", "error", "-y"] + inputs + ["-/filter_complex", script, "-map", "[v]", "-t", f"{dur:.3f}", "-r", str(FPS),
                           "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", out])


MONTAGE_ORDER = ["onelight", "solarized_light", "catppuccin", "dracula", "monokai", "nord", "dark", "light"]


def montage_source(stills, out, each=0.34, fade=0.12):
    """Stills cross-dissolving into each other, ordered so the run ends on the theme the
    recording continues in (Light)."""
    by = {os.path.basename(s).split("_", 1)[1][:-4]: s for s in stills}
    order = [by[k] for k in MONTAGE_ORDER if k in by]
    inputs = []
    for s in order:
        inputs += ["-loop", "1", "-framerate", str(FPS), "-t", f"{each:.2f}", "-i", s]
    fc = "".join(f"[{i}:v]scale={PW}:{PH},setsar=1[i{i}];" for i in range(len(order)))
    cur = "i0"; off = 0.0
    for i in range(1, len(order)):
        off += each - fade
        fc += f"[{cur}][i{i}]xfade=transition=fade:duration={fade}:offset={off:.3f}[m{i}];"; cur = f"m{i}"
    fc += f"[{cur}]format=yuv420p[v]"
    script = out + ".filter"; open(script, "w", encoding="utf8").write(fc)
    subprocess.check_call(["ffmpeg", "-v", "error", "-y"] + inputs + ["-/filter_complex", script, "-map", "[v]", "-r", str(FPS),
                           "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", out])
    return each * len(order) - fade * (len(order) - 1)


def join(parts, out):
    """Cross-dissolve consecutive parts (each part's ends overlap by XFADE)."""
    durs = [float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]).decode()) for p in parts]
    inputs = []
    for p in parts:
        inputs += ["-i", p]
    fc = ""; cur = "0:v"; offset = 0.0
    for i in range(1, len(parts)):
        offset += durs[i - 1] - XFADE
        fc += f"[{cur}][{i}:v]xfade=transition=fade:duration={XFADE}:offset={offset:.3f}[x{i}];"
        cur = f"x{i}"
    fc += f"[{cur}]fade=t=in:st=0:d=0.4,format=yuv420p[v]"
    script = out + ".filter"; open(script, "w", encoding="utf8").write(fc)
    subprocess.check_call(["ffmpeg", "-v", "error", "-y"] + inputs + ["-/filter_complex", script, "-map", "[v]", "-r", str(FPS),
                           "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out])


def main(raw, marks_path, out, stills_dir=None):
    bg = os.path.join(SC, "prod_bg.png"); bz = os.path.join(SC, "prod_bezel.png"); mask = os.path.join(SC, "prod_mask.png")
    background(bg); bezel(bz); screen_mask(mask)
    art = pulses()
    marks = [((float(l.split(" ", 1)[0]) - LAG) / SPEED, l.split(" ", 1)[1].strip()) for l in open(marks_path, encoding="utf8").read().strip().splitlines()]
    events = read_taps(os.path.join(os.path.dirname(os.path.abspath(marks_path)), "taps.txt"))
    parts = []; toff = 0.0

    def seg(name, **kw):
        p = os.path.join(SC, f"prod_{name}.mp4"); render(p, t_offset=toff, bg=bg, bz=bz, mask=mask, art=art, **kw); parts.append(p); return p

    tc = os.path.join(SC, "prod_title_card.png"); title_card(tc)
    seg("title", dur=2.6, still=tc)
    toff += 2.6
    for i, (t, lab) in enumerate(marks[:-1]):
        t_next = marks[i + 1][0]
        a = t - (0.5 if i == 0 else XFADE / 2)
        b = t_next + (XFADE / 2 if i + 1 < len(marks) - 1 else 0.2)
        if lab == "montage":
            if not stills_dir:
                continue
            stills = sorted(glob.glob(os.path.join(stills_dir, "*.png")))
            msrc = os.path.join(SC, "prod_montage_src.mp4"); mdur = montage_source(stills, msrc) + XFADE
            seg("montage", dur=mdur, src=msrc, caption="Eight themes and an editor", focus=FOCUS["Eight"], zoom=1.03)
            toff += mdur
            continue
        key = lab.split()[0].rstrip(",:")
        seg(f"s{i:02d}", dur=b - a, src=raw, src_from=a, caption=lab, events=events, t_from=a, focus=FOCUS.get(key), speed=SPEED)
        toff += b - a
    ec = os.path.join(SC, "prod_end_card.png"); end_card(ec)
    seg("end", dur=4.2, still=ec)
    join(parts, out)
    print("wrote", out, round(os.path.getsize(out) / 1e6, 2), "MB", len(parts), "segments")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
