"""
Procedural bezel artwork for a Honor Magic V6 (two screens).

Each system gets two images:
  <sys>_wide.png   2420x1080  (outer/cover screen, landscape)
  <sys>_square.png 2352x2352  (inner screen, either orientation; body = middle 2352x2172)

The game hole is transparent.  params.json records the hole and body rects
(normalised) that the adaptive-bezel shader needs.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SS = 2                              # supersampling factor
FONTDIR = r"C:\Windows\Fonts"
WIDE = (2420, 1080)
SQUARE = (2352, 2352)
SQ_BODY = (0, 90, 2352, 2262)        # x0,y0,x1,y1 of the body inside the square canvas


def S(v):
    return int(round(v * SS))


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTDIR, name), max(1, S(size)))


# --------------------------------------------------------------------------- textures

def noise_field(w, h, cx, cy, seed):
    rng = np.random.default_rng(seed)
    sw, sh = max(2, w // max(1, cx) + 2), max(2, h // max(1, cy) + 2)
    small = (rng.random((sh, sw)) * 255).astype(np.uint8)
    return np.asarray(Image.fromarray(small).resize((w, h), Image.BICUBIC), np.float32) / 255.0


def mix(c0, c1, t):
    c0 = np.array(c0, np.float32)
    c1 = np.array(c1, np.float32)
    return c0 * (1 - t[..., None]) + c1 * t[..., None]


def out8(a):
    return np.clip(a, 0, 255).astype(np.uint8)


def vgrad(c0, c1, gamma=1.0, grain=0.0, seed=0):
    def f(w, h):
        t = np.linspace(0, 1, h, dtype=np.float32)[:, None] ** gamma
        t = np.broadcast_to(t, (h, w)).copy()
        a = mix(c0, c1, t)
        if grain:
            a += (np.random.default_rng(seed).standard_normal((h, w, 1)) * grain).astype(np.float32)
        return out8(a)
    return f


def solid(c, grain=0.0, seed=0):
    return vgrad(c, c, grain=grain, seed=seed)


def woodgrain(dark, light, seed=1, scale=1.0, vertical=False):
    def f(w, h):
        ww, hh = (h, w) if vertical else (w, h)
        warp = noise_field(ww, hh, S(260), S(90), seed)
        warp2 = noise_field(ww, hh, S(60), S(14), seed + 1)
        fib = noise_field(ww, hh, S(70), max(1, S(1.5)), seed + 2)
        y = np.arange(hh, dtype=np.float32)[:, None] / SS
        x = np.arange(ww, dtype=np.float32)[None, :] / SS
        ring = np.sin((y * 0.075 / scale + warp * 9.0 + warp2 * 1.6 + x * 0.002) * 2.0)
        t = (ring * 0.5 + 0.5) ** 1.8
        t = 0.55 * t + 0.45 * fib
        knots = noise_field(ww, hh, S(400), S(160), seed + 3)
        t = np.clip(t * (0.75 + 0.5 * knots), 0, 1)
        a = mix(dark, light, t)
        if vertical:
            a = a.transpose(1, 0, 2)
        return out8(a)
    return f


def brushed(c, seed=5, sheen=0.18):
    def f(w, h):
        streak = noise_field(w, h, S(140), max(1, S(0.7)), seed)
        t = np.linspace(0, 1, h, dtype=np.float32)[:, None]
        band = 1.0 + sheen * np.cos((t - 0.35) * 6.0)
        a = np.array(c, np.float32) * ((0.86 + 0.22 * streak) * band)[..., None]
        return out8(a)
    return f


def fabric(base, thread, seed=7, period=4.0, lurex=None):
    def f(w, h):
        y = np.arange(h, dtype=np.float32)[:, None]
        x = np.arange(w, dtype=np.float32)[None, :]
        p = S(period)
        wx = 0.5 + 0.5 * np.sin(x * 2 * np.pi / p)
        wy = 0.5 + 0.5 * np.sin(y * 2 * np.pi / p)
        weave = np.maximum(wx * (1 - wy * 0.6), wy * (1 - wx * 0.6))
        slub = noise_field(w, h, S(30), S(3), seed)
        t = np.clip(weave * 0.7 + slub * 0.3, 0, 1)
        a = mix(base, thread, t)
        if lurex is not None:
            stripe = (np.sin(x * 2 * np.pi / S(38)) > 0.93).astype(np.float32)
            a = a * (1 - stripe[..., None] * 0.6) + np.array(lurex, np.float32) * stripe[..., None] * 0.6
        return out8(a)
    return f


def perforated(base, hole_col, pitch=14, r=4.2, stagger=True):
    def f(w, h):
        y = np.arange(h, dtype=np.float32)[:, None] / SS
        x = np.arange(w, dtype=np.float32)[None, :] / SS
        row = np.floor(y / pitch)
        xo = x + (row % 2) * pitch * 0.5 if stagger else x + 0 * row
        fx = (xo % pitch) - pitch / 2
        fy = (y % pitch) - pitch / 2
        d = np.sqrt(fx * fx + fy * fy)
        t = np.clip((r - d) * SS * 0.7 + 0.5, 0, 1)
        a = mix(base, hole_col, t)
        # tiny highlight on lower lip of each hole
        lip = np.clip(1 - np.abs(d - r - 0.8) * 1.2, 0, 1) * (fy > 0)
        a += lip[..., None] * 18
        return out8(a)
    return f


def slots(base, slot_col, pitch=16, width=7, vertical=False):
    def f(w, h):
        ax = np.arange(w if vertical else h, dtype=np.float32) / SS
        ph = ax % pitch
        t = np.clip(np.minimum(ph, width - ph) * SS * 0.8, 0, 1) * (ph < width)
        if vertical:
            t = np.broadcast_to(t[None, :], (h, w))
        else:
            t = np.broadcast_to(t[:, None], (h, w))
        a = mix(base, slot_col, t.astype(np.float32))
        return out8(a)
    return f


def mesh(base, wire, pitch=5.0):
    def f(w, h):
        y = np.arange(h, dtype=np.float32)[:, None] / SS
        x = np.arange(w, dtype=np.float32)[None, :] / SS
        u = np.abs(np.sin((x + y) * np.pi / pitch))
        v = np.abs(np.sin((x - y) * np.pi / pitch))
        t = np.clip((1 - np.minimum(u, v)) ** 6, 0, 1)
        return out8(mix(base, wire, t))
    return f


# --------------------------------------------------------------------------- painter

class Painter:
    def __init__(self, size, bg):
        self.w, self.h = size
        self.bg = bg
        self.im = Image.new('RGBA', (S(self.w), S(self.h)), tuple(bg) + (255,))
        self.hole = None

    # masks -----------------------------------------------------------
    def rr(self, box, r, radii=None):
        x0, y0, x1, y1 = [S(v) for v in box]
        w, h = x1 - x0, y1 - y0
        if radii is None:
            radii = (r, r, r, r)
        m = Image.new('L', (w, h), 0)
        for i, rad in enumerate(radii):
            rad = min(S(rad), w // 2, h // 2)
            t = Image.new('L', (w, h), 0)
            ImageDraw.Draw(t).rounded_rectangle((0, 0, w - 1, h - 1), radius=rad, fill=255)
            qx = 0 if i in (0, 3) else w // 2
            qy = 0 if i in (0, 1) else h // 2
            qw = w // 2 if i in (0, 3) else w - w // 2
            qh = h // 2 if i in (0, 1) else h - h // 2
            m.paste(t.crop((qx, qy, qx + qw, qy + qh)), (qx, qy))
        return (x0, y0), m

    def ell(self, cx, cy, rx, ry=None):
        ry = rx if ry is None else ry
        x0, y0 = S(cx - rx), S(cy - ry)
        w, h = S(2 * rx), S(2 * ry)
        m = Image.new('L', (w, h), 0)
        ImageDraw.Draw(m).ellipse((0, 0, w - 1, h - 1), fill=255)
        return (x0, y0), m

    # compositing -----------------------------------------------------
    def comp(self, tile, x0, y0):
        W, H = self.im.size
        sx0, sy0 = max(0, -x0), max(0, -y0)
        dx0, dy0 = max(0, x0), max(0, y0)
        w = min(tile.width - sx0, W - dx0)
        h = min(tile.height - sy0, H - dy0)
        if w <= 0 or h <= 0:
            return
        if sx0 or sy0 or w < tile.width or h < tile.height:
            tile = tile.crop((sx0, sy0, sx0 + w, sy0 + h))
        self.im.alpha_composite(tile, (dx0, dy0))

    def fill(self, pm, src, alpha=1.0):
        (x0, y0), m = pm
        w, h = m.size
        if callable(src):
            tile = Image.fromarray(src(w, h), 'RGB').convert('RGBA')
        else:
            tile = Image.new('RGBA', (w, h), tuple(src[:3]) + (255,))
        tile.putalpha(m if alpha >= 1 else m.point(lambda v: int(v * alpha)))
        self.comp(tile, x0, y0)

    def shadow(self, pm, dx, dy, blur, alpha, color=(0, 0, 0)):
        (x0, y0), m = pm
        pad = S(blur * 3) + 2
        big = Image.new('L', (m.width + 2 * pad, m.height + 2 * pad), 0)
        big.paste(m, (pad, pad))
        big = big.filter(ImageFilter.GaussianBlur(S(blur))).point(lambda v: int(v * alpha))
        tile = Image.new('RGBA', big.size, tuple(color) + (255,))
        tile.putalpha(big)
        self.comp(tile, x0 - pad + S(dx), y0 - pad + S(dy))

    def bevel(self, pm, width, hi=0.35, lo=0.35, inset=False, soft=0.6):
        (x0, y0), m = pm
        k = max(1, S(width))
        a = np.asarray(m, np.float32) / 255.0
        dn = np.zeros_like(a); dn[k:, k:] = a[:-k, :-k]
        up = np.zeros_like(a); up[:-k, :-k] = a[k:, k:]
        tl = np.clip(a - dn, 0, 1)
        br = np.clip(a - up, 0, 1)
        hiM, loM = (br, tl) if inset else (tl, br)
        def tile(mask, col, alpha):
            mm = Image.fromarray(out8(mask * 255 * alpha))
            if soft:
                mm = mm.filter(ImageFilter.GaussianBlur(max(0.5, k * soft * 0.5)))
                mm = Image.fromarray(out8(np.asarray(mm, np.float32) * a))
            t = Image.new('RGBA', m.size, col + (255,))
            t.putalpha(mm)
            return t
        self.comp(tile(hiM, (255, 255, 255), hi), x0, y0)
        self.comp(tile(loM, (0, 0, 0), lo), x0, y0)

    def glow(self, cx, cy, r, color, alpha):
        pm = self.ell(cx, cy, r)
        self.shadow(pm, 0, 0, r * 0.6, alpha, color)

    def text(self, cx, cy, s, fname, size, color, italic=0.0, spacing=0.0,
             align='c', alpha=1.0, rotate=0.0):
        f = font(fname, size)
        widths = [f.getlength(ch) for ch in s]
        total = sum(widths) + S(spacing) * max(0, len(s) - 1)
        asc, desc = f.getmetrics()
        pad = S(size)
        img = Image.new('L', (int(total) + 2 * pad, asc + desc + 2 * pad), 0)
        d = ImageDraw.Draw(img)
        x = pad
        for ch, wc in zip(s, widths):
            d.text((x, pad), ch, font=f, fill=255)
            x += wc + S(spacing)
        if italic:
            img = img.transform(img.size, Image.AFFINE,
                                (1, italic, -italic * img.height / 2, 0, 1, 0),
                                resample=Image.BICUBIC)
        if rotate:
            img = img.rotate(rotate, resample=Image.BICUBIC, expand=True)
        bb = img.getbbox()
        if not bb:
            return
        img = img.crop(bb)
        if align == 'c':
            x0 = S(cx) - img.width // 2
        elif align == 'l':
            x0 = S(cx)
        else:
            x0 = S(cx) - img.width
        y0 = S(cy) - img.height // 2
        tile = Image.new('RGBA', img.size, tuple(color) + (255,))
        tile.putalpha(img.point(lambda v: int(v * alpha)))
        self.comp(tile, x0, y0)

    def multicolor_text(self, cx, cy, s, colors, fname, size, italic=0.0, spacing=0.0):
        f = font(fname, size)
        widths = [f.getlength(ch) / SS + spacing for ch in s]
        total = sum(widths) - spacing
        x = cx - total / 2
        for ch, wc, col in zip(s, widths, colors):
            self.text(x + (wc - spacing) / 2, cy, ch, fname, size, col, italic=italic)
            x += wc

    # hole --------------------------------------------------------------
    def cut_hole(self, box, r, inner_shadow=0.45, reflection=0.06, shadow_size=10):
        pm = self.rr(box, r)
        (x0, y0), m = pm
        self.hole = box
        arr = np.asarray(self.im).copy()
        mm = np.asarray(m, np.float32) / 255.0
        sub = arr[y0:y0 + m.height, x0:x0 + m.width, 3].astype(np.float32)
        arr[y0:y0 + m.height, x0:x0 + m.width, 3] = out8(sub * (1 - mm))
        self.im = Image.fromarray(arr, 'RGBA')
        # glass: inner shadow (heavier along the top) + soft reflection
        w, h = m.size
        inv = Image.fromarray(out8(255 - np.asarray(m, np.float32)))
        pad = S(shadow_size * 4)
        big = Image.new('L', (w + 2 * pad, h + 2 * pad), 255)
        big.paste(inv, (pad, pad + S(shadow_size * 0.6)))
        big = big.filter(ImageFilter.GaussianBlur(S(shadow_size)))
        sh = np.asarray(big.crop((pad, pad, pad + w, pad + h)), np.float32) / 255.0
        alpha = np.clip(sh * inner_shadow, 0, 1)
        rgb = np.zeros((h, w, 3), np.float32)
        if reflection:
            yy = np.linspace(0, 1, h, dtype=np.float32)[:, None]
            xx = np.linspace(0, 1, w, dtype=np.float32)[None, :]
            d = (xx * 0.8 + yy * 1.2)
            refl = np.clip(1 - d / 0.9, 0, 1) ** 2 * reflection
            band = np.exp(-((d - 1.05) ** 2) / 0.004) * reflection * 0.5
            r_a = refl + band
            tot = np.clip(alpha + r_a, 1e-6, 1)
            rgb = (255.0 * r_a / tot)[..., None] * np.ones(3, np.float32)
            alpha = np.clip(alpha + r_a, 0, 1)
        alpha = alpha * mm
        tile = Image.fromarray(np.dstack([out8(rgb), out8(alpha * 255)]), 'RGBA')
        self.comp(tile, x0, y0)

    def finish(self, edge_fade=14):
        im = self.im.resize((self.w, self.h), Image.LANCZOS)
        a = np.asarray(im).astype(np.float32)
        h, w = a.shape[:2]
        yy = np.arange(h, dtype=np.float32)[:, None]
        xx = np.arange(w, dtype=np.float32)[None, :]
        d = np.minimum(np.minimum(yy, h - 1 - yy), np.minimum(xx, w - 1 - xx))
        t = np.clip(d / edge_fade, 0, 1)[..., None]
        edge = np.array(list(self.bg) + [255], np.float32)
        hole_mask = a[..., 3:4] < 255
        a = np.where(hole_mask, a, a * t + edge * (1 - t))
        return Image.fromarray(out8(a), 'RGBA')


# --------------------------------------------------------------------------- shared parts

def room(p, style, focus_box):
    W, H = p.w, p.h
    def f(w, h):
        y = np.arange(h, dtype=np.float32)[:, None] / SS
        x = np.arange(w, dtype=np.float32)[None, :] / SS
        if style == '70s':
            k = np.sin(x * 2 * np.pi / 120 + 0.9 * np.sin(y * 2 * np.pi / 260))
            t = (k > 0.2).astype(np.float32) * 0.55 + (k > 0.75) * 0.45
            a = mix((46, 26, 12), (92, 52, 18), t)
        elif style == '80s':
            k = ((x % 64) < 4).astype(np.float32) * 0.6 + ((x % 64) > 30) * ((x % 64) < 32) * 0.3
            a = mix((52, 44, 50), (80, 70, 74), k)
        elif style == '90s':
            n = noise_field(w, h, S(6), S(6), 3)
            a = mix((22, 26, 36), (34, 40, 54), n)
        else:  # desk
            n = noise_field(w, h, S(3), S(3), 9)
            a = mix((20, 20, 23), (30, 30, 34), n)
        # vignette towards the edge colour, lit around the focus box
        cx = (focus_box[0] + focus_box[2]) / 2
        cy = (focus_box[1] + focus_box[3]) / 2
        rx = (focus_box[2] - focus_box[0]) / 2 + 260
        ry = (focus_box[3] - focus_box[1]) / 2 + 260
        dd = np.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)
        lit = np.clip(1.25 - dd, 0, 1) ** 1.5
        a = a * (0.25 + 0.75 * lit[..., None]) + np.array(p.bg, np.float32) * (1 - lit[..., None]) * 0.4
        return out8(a)
    p.fill(p.rr((0, 0, W, H), 0), f)


def knob(p, cx, cy, r, metal=(196, 196, 200), cap=(40, 40, 42), pointer=-40, ridges=48,
         label=None, label_col=(220, 220, 220), ticks=None, tick_col=(30, 30, 30)):
    if ticks:
        for i, t in enumerate(ticks):
            ang = math.radians(-135 + 270 * i / max(1, len(ticks) - 1) - 90)
            tx, ty = cx + math.cos(ang) * r * 1.38, cy + math.sin(ang) * r * 1.38
            p.text(tx, ty, t, 'arialbd.ttf', r * 0.2, tick_col)
    p.shadow(p.ell(cx, cy, r), 4, 8, r * 0.18, 0.6)
    pm = p.ell(cx, cy, r)
    (x0, y0), m = pm
    w, h = m.size
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ang = np.arctan2(yy - h / 2, xx - w / 2)
    rad = np.sqrt((yy - h / 2) ** 2 + (xx - w / 2) ** 2) / (w / 2)
    aniso = 0.72 + 0.28 * np.cos(2 * (ang + 0.8))
    rid = 0.92 + 0.08 * np.sin(ang * ridges) * (rad > 0.82)
    shade = aniso * rid * (1.05 - 0.15 * rad)
    arr = out8(np.array(metal, np.float32) * shade[..., None])
    p.fill(pm, lambda ww, hh: arr)
    p.bevel(pm, r * 0.06, 0.5, 0.4)
    pc = p.ell(cx, cy, r * 0.6)
    p.fill(pc, vgrad(tuple(min(255, c + 30) for c in cap), cap))
    p.bevel(pc, r * 0.04, 0.25, 0.5)
    a = math.radians(pointer - 90)
    px, py = cx + math.cos(a) * r * 0.48, cy + math.sin(a) * r * 0.48
    p.fill(p.ell(px, py, r * 0.07), (235, 235, 235))
    if label:
        p.text(cx, cy + r * 1.45, label, 'arialbd.ttf', max(11, r * 0.24), label_col, spacing=1)


def push_button(p, box, col, r=6, label=None, label_col=(200, 200, 200), lsize=13):
    pm = p.rr(box, r)
    p.shadow(pm, 0, 3, 3, 0.6)
    p.fill(pm, vgrad(tuple(min(255, c + 28) for c in col), col))
    p.bevel(pm, 2, 0.35, 0.45)
    if label:
        p.text((box[0] + box[2]) / 2, box[3] + lsize * 1.1, label, 'arialbd.ttf', lsize, label_col)


def led(p, cx, cy, r, col, on=True):
    p.fill(p.ell(cx, cy, r * 1.5), (20, 20, 20))
    if on:
        p.glow(cx, cy, r * 2.2, col, 0.55)
    c2 = col if on else tuple(int(c * 0.3) for c in col)
    p.fill(p.ell(cx, cy, r), c2)
    p.fill(p.ell(cx - r * 0.3, cy - r * 0.3, r * 0.35), (255, 255, 255), alpha=0.7 if on else 0.2)


def seg_display(p, box, text, col, bg=(30, 6, 6)):
    pm = p.rr(box, 6)
    p.fill(pm, bg)
    p.bevel(pm, 3, 0.2, 0.6, inset=True)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    hgt = box[3] - box[1]
    # glow pass then crisp pass
    p.text(cx, cy, text, 'consola.ttf', hgt * 0.8, col, italic=-0.12, spacing=hgt * 0.06, alpha=0.35)
    p.text(cx, cy, text, 'consola.ttf', hgt * 0.72, tuple(min(255, c + 40) for c in col),
           italic=-0.12, spacing=hgt * 0.08)


def grille(p, box, src, r=10, inset=True):
    pm = p.rr(box, r)
    p.fill(pm, src)
    p.bevel(pm, 3, 0.25, 0.55, inset=inset)


def dpad(p, cx, cy, size, col=(40, 40, 44), well=None):
    arm = size * 0.33
    if well:
        pw = p.ell(cx, cy, size * 0.62)
        p.fill(pw, well)
        p.bevel(pw, 4, 0.25, 0.35, inset=True)
    h = p.rr((cx - size / 2, cy - arm / 2, cx + size / 2, cy + arm / 2), arm * 0.18)
    v = p.rr((cx - arm / 2, cy - size / 2, cx + arm / 2, cy + size / 2), arm * 0.18)
    # union mask
    (hx, hy), hm = h
    (vx, vy), vm = v
    x0, y0 = min(hx, vx), min(hy, vy)
    w = max(hx + hm.width, vx + vm.width) - x0
    hh = max(hy + hm.height, vy + vm.height) - y0
    m = Image.new('L', (w, hh), 0)
    m.paste(hm, (hx - x0, hy - y0), hm)
    m.paste(vm, (vx - x0, vy - y0), vm)
    pm = ((x0, y0), m)
    p.shadow(pm, 3, 8, 6, 0.7)
    p.fill(pm, vgrad(tuple(c + 26 for c in col), col))
    p.bevel(pm, 4, 0.3, 0.5)
    pc = p.ell(cx, cy, arm * 0.28)
    p.bevel(pc, 3, 0.25, 0.3, inset=True)
    for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        tx, ty = cx + dx * size * 0.36, cy + dy * size * 0.36
        p.fill(p.ell(tx, ty, arm * 0.1), tuple(c + 18 for c in col))


def round_button(p, cx, cy, r, col, label=None, label_col=(40, 40, 120), lpos=(0, 1.55),
                 lsize=None, well=True, well_col=None):
    if well:
        pw = p.ell(cx, cy, r * 1.16)
        p.fill(pw, well_col or (0, 0, 0), alpha=0.18 if well_col is None else 1.0)
        p.bevel(pw, 3, 0.25, 0.3, inset=True)
    pm = p.ell(cx, cy, r)
    p.shadow(pm, 2, 7, 5, 0.6)
    p.fill(pm, vgrad(tuple(min(255, c + 40) for c in col), col, gamma=0.8))
    p.bevel(pm, r * 0.09, 0.45, 0.45)
    p.fill(p.ell(cx - r * 0.3, cy - r * 0.35, r * 0.35, r * 0.22), (255, 255, 255), alpha=0.18)
    if label:
        p.text(cx + lpos[0] * r, cy + lpos[1] * r, label, 'arialbi.ttf', lsize or r * 0.5, label_col)


def pill(p, cx, cy, w, h, col, angle=-25, label=None, label_col=(40, 40, 120), lsize=16):
    tile_p = Painter((int(w * 2.2), int(w * 2.2)), (0, 0, 0))
    tile_p.im = Image.new('RGBA', tile_p.im.size, (0, 0, 0, 0))
    c = w * 1.1
    well = tile_p.rr((c - w / 2 - 6, c - h / 2 - 6, c + w / 2 + 6, c + h / 2 + 6), h)
    tile_p.fill(well, (0, 0, 0), alpha=0.22)
    tile_p.bevel(well, 2, 0.2, 0.3, inset=True)
    pm = tile_p.rr((c - w / 2, c - h / 2, c + w / 2, c + h / 2), h / 2)
    tile_p.fill(pm, vgrad(tuple(min(255, x + 30) for x in col), col))
    tile_p.bevel(pm, 2, 0.35, 0.45)
    if label:
        tile_p.text(c, c + h * 1.35, label, 'arialbd.ttf', lsize, label_col, italic=0.15, spacing=1)
    t = tile_p.im.rotate(-angle * -1, resample=Image.BICUBIC, expand=False)
    p.comp(t, S(cx - c), S(cy - c))


def slot_cluster(p, cx, cy, n, length, width, gap, angle, col_in=(0, 0, 0)):
    tp = Painter((int(length * 2 + n * gap), int(length * 2 + n * gap)), (0, 0, 0))
    tp.im = Image.new('RGBA', tp.im.size, (0, 0, 0, 0))
    c = tp.w / 2
    for i in range(n):
        x = c + (i - (n - 1) / 2) * gap
        pm = tp.rr((x - width / 2, c - length / 2, x + width / 2, c + length / 2), width / 2)
        tp.fill(pm, col_in, alpha=0.55)
        tp.bevel(pm, 2, 0.3, 0.4, inset=True)
    t = tp.im.rotate(angle, resample=Image.BICUBIC)
    p.comp(t, S(cx - c), S(cy - c))


def hole_grid(p, box, pitch, r, col=(0, 0, 0), alpha=0.6, circle=None):
    x0, y0, x1, y1 = box
    y = y0 + pitch / 2
    while y < y1:
        x = x0 + pitch / 2
        while x < x1:
            if circle is None or (x - circle[0]) ** 2 + (y - circle[1]) ** 2 <= circle[2] ** 2:
                pm = p.ell(x, y, r)
                p.fill(pm, col, alpha=alpha)
                p.bevel(pm, 1.5, 0.25, 0.3, inset=True)
            x += pitch
        y += pitch


# --------------------------------------------------------------------------- TVs

TV_STYLES = {
    # 1977-ish woodgrain console set (Atari 2600)
    'atari2600': dict(
        room='70s', era='70s', bg=(10, 6, 4),
        cabinet=woodgrain((58, 32, 16), (120, 72, 36), seed=11),
        front=None,
        surround=('chrome', 30), recess=22, hole_r=0.11,
        w=dict(ft=72, fb=92, fs=58, lp=250, rp=260),
        s=dict(fs=110, ft=100),
        curve=0.045, scan=0.55, vignette=0.45),
    # mid-80s US set (NES): woodgrain vinyl case, silver front, right control panel
    'nes': dict(
        room='80s', era='80s_us', bg=(12, 10, 11),
        cabinet=woodgrain((46, 28, 16), (96, 60, 34), seed=21),
        front=vgrad((176, 176, 180), (140, 140, 146), grain=2.5, seed=2),
        surround=('plastic', 22, (54, 54, 58)), recess=16, hole_r=0.07,
        w=dict(ft=58, fb=84, fs=48, lp=70, rp=330),
        s=dict(fs=100, ft=92),
        curve=0.035, scan=0.55, vignette=0.4),
    # late-80s European set (Master System): black with silver pinstripe, green LED
    'mastersystem': dict(
        room='80s', era='80s_eu', bg=(12, 10, 11),
        cabinet=vgrad((40, 40, 44), (18, 18, 20), grain=2.0, seed=3),
        front=None,
        surround=('pinstripe', 20, (24, 24, 26)), recess=14, hole_r=0.065,
        w=dict(ft=56, fb=82, fs=46, lp=70, rp=320),
        s=dict(fs=96, ft=88),
        curve=0.035, scan=0.55, vignette=0.4),
    # 1990 hi-fi stereo set (Mega Drive): black, slot speakers both sides
    'megadrive': dict(
        room='90s', era='90s_md', bg=(8, 9, 12),
        cabinet=vgrad((34, 34, 38), (14, 14, 16), grain=1.5, seed=4),
        front=None,
        surround=('plastic', 18, (20, 20, 22)), recess=12, hole_r=0.05,
        w=dict(ft=52, fb=80, fs=40, lp=230, rp=230),
        s=dict(fs=90, ft=84),
        curve=0.03, scan=0.5, vignette=0.35),
    # 1992 surround-stereo set (SNES): charcoal grey with perforated speakers
    'snes': dict(
        room='90s', era='90s_snes', bg=(8, 9, 12),
        cabinet=vgrad((72, 72, 78), (46, 46, 50), grain=1.8, seed=5),
        front=None,
        surround=('plastic', 18, (30, 30, 33)), recess=12, hole_r=0.05,
        w=dict(ft=52, fb=80, fs=40, lp=230, rp=230),
        s=dict(fs=90, ft=84),
        curve=0.03, scan=0.5, vignette=0.35),
    # 1995 black 'fine pitch' set (Saturn): cloth speakers, red standby
    'saturn': dict(
        room='90s', era='late90s_black', bg=(8, 9, 12),
        cabinet=vgrad((30, 30, 32), (10, 10, 11), gamma=0.8, grain=1.2, seed=6),
        front=None,
        surround=('gloss', 24, (6, 6, 7)), recess=10, hole_r=0.035,
        w=dict(ft=50, fb=86, fs=34, lp=210, rp=210),
        s=dict(fs=84, ft=80),
        curve=0.022, scan=0.4, vignette=0.3),
    # 1998 silver set (N64): silver frame, black mesh speakers, blue LED
    'n64': dict(
        room='90s', era='late90s_silver', bg=(8, 9, 12),
        cabinet=brushed((170, 172, 178), seed=8, sheen=0.12),
        front=None,
        surround=('gloss', 26, (14, 14, 16)), recess=10, hole_r=0.03,
        w=dict(ft=48, fb=90, fs=34, lp=200, rp=200),
        s=dict(fs=84, ft=78),
        curve=0.02, scan=0.35, vignette=0.3),
    # ---- video playback sets (FFmpeg core) ----
    # 1980s woodgrain living-room CRT, 4:3 tube (video is letterboxed by the shader)
    'tvcrt': dict(
        room='80s', era='80s_us', bg=(12, 10, 11),
        cabinet=woodgrain((40, 22, 12), (88, 52, 28), seed=31),
        front=vgrad((60, 58, 56), (34, 33, 32), grain=2.0, seed=12),
        surround=('plastic', 22, (40, 40, 42)), recess=16, hole_r=0.07,
        w=dict(ft=58, fb=84, fs=48, lp=70, rp=330),
        s=dict(fs=100, ft=92),
        curve=0.035, scan=0.35, vignette=0.4),
    # late-90s black widescreen CRT, 16:9 tube
    'tvwide': dict(
        room='90s', era='late90s_black', bg=(8, 9, 12), aspect=16 / 9, badge='WIDE SCREEN',
        cabinet=vgrad((30, 30, 32), (10, 10, 11), gamma=0.8, grain=1.2, seed=16),
        front=None,
        surround=('gloss', 24, (6, 6, 7)), recess=10, hole_r=0.03,
        w=dict(ft=50, fb=86, fs=34, lp=150, rp=150),
        s=dict(fs=70, ft=70, base=440),
        curve=0.015, scan=0.2, vignette=0.25),
}


def tv_geom(layout, st):
    asp = st.get('aspect', 4 / 3)          # tube aspect (4:3 CRT, 16:9 widescreen)
    if layout == 'wide':
        W, H = WIDE
        m = 22
        g = st['w']
        hh = H - 2 * m - g['ft'] - g['fb']
        hw = hh * asp
        hx = (W - hw) / 2
        hy = m + g['ft']
        cab = (hx - g['fs'] - g['lp'], m, hx + hw + g['fs'] + g['rp'], H - m)
        return dict(cab=cab, hole=(hx, hy, hx + hw, hy + hh),
                    left=(cab[0], m, hx - g['fs'], H - m),
                    right=(hx + hw + g['fs'], m, cab[2], H - m),
                    bottom=(hx - g['fs'], hy + hh, hx + hw + g['fs'], H - m),
                    body=(0, 0, W, H))
    else:
        W, H = SQUARE
        m = 34
        g = st['s']
        cab = (m, SQ_BODY[1] + m, W - m, SQ_BODY[3] - m)
        hw = (cab[2] - cab[0]) - 2 * g['fs']
        hh = hw / asp
        hx = cab[0] + g['fs']
        hy = cab[1] + g['ft']
        body = SQ_BODY
        if 'base' in g:   # short wide tube: trim the cabinet to tube + base panel
            cab = (cab[0], cab[1], cab[2], hy + hh + g['base'])
            body = (0, SQ_BODY[1], W, cab[3] + m)
        return dict(cab=cab, hole=(hx, hy, hx + hw, hy + hh), left=None, right=None,
                    bottom=(cab[0], hy + hh, cab[2], cab[3]), body=body)


def draw_tv(sysname, layout):
    st = TV_STYLES[sysname]
    g = tv_geom(layout, st)
    size = WIDE if layout == 'wide' else SQUARE
    p = Painter(size, st['bg'])
    cab, hole = g['cab'], g['hole']
    room(p, st['room'], cab)
    # TV glow on the wall
    p.shadow(p.rr(cab, 40), 0, 0, 90, 0.35, (110, 130, 190))
    # floor shadow + cabinet
    p.shadow(p.rr(cab, 26), 0, 18, 28, 0.8)
    pm = p.rr(cab, 26)
    p.fill(pm, st['cabinet'])
    p.bevel(pm, 6, 0.28, 0.5)
    era = st['era']
    hx0, hy0, hx1, hy1 = hole
    hh = hy1 - hy0

    # inset front panel (80s US: silver plastic front inside woodgrain case)
    if st['front'] is not None:
        inset = 20
        fbox = (cab[0] + inset, cab[1] + inset, cab[2] - inset, cab[3] - inset)
        fp = p.rr(fbox, 14)
        p.shadow(fp, 0, 4, 6, 0.6)
        p.fill(fp, st['front'])
        p.bevel(fp, 4, 0.45, 0.35)

    # ---- side / bottom panels
    if layout == 'wide':
        L, R, B = g['left'], g['right'], g['bottom']
        pad = 26
        if era == '70s':
            grille(p, (L[0] + pad, L[1] + pad + 10, L[2] - 6, L[3] - pad - 10),
                   fabric((60, 40, 20), (150, 112, 60), seed=3, lurex=(200, 170, 110)), r=14)
            plate = (R[0] + 6, R[1] + pad + 10, R[2] - pad, R[1] + 470)
            pp = p.rr(plate, 12)
            p.fill(pp, brushed((196, 192, 182)))
            p.bevel(pp, 4, 0.5, 0.45)
            cx = (plate[0] + plate[2]) / 2
            knob(p, cx, plate[1] + 120, 72, ticks=[str(i) for i in range(2, 14)], pointer=-80, label='VHF',
                 label_col=(40, 30, 20))
            knob(p, cx, plate[1] + 330, 52, pointer=40, label='UHF', label_col=(40, 30, 20), ridges=36)
            grille(p, (R[0] + 6, plate[3] + 24, R[2] - pad, R[3] - pad - 10),
                   fabric((60, 40, 20), (150, 112, 60), seed=4, lurex=(200, 170, 110)), r=14)
            kx = cx
            knob(p, kx - 60, B[1] + (B[3] - B[1]) / 2 - 4, 22, metal=(200, 196, 186), ridges=24)
            knob(p, kx + 20, B[1] + (B[3] - B[1]) / 2 - 4, 22, metal=(200, 196, 186), ridges=24)
            p.text((hx0 + hx1) / 2, B[1] + (B[3] - B[1]) / 2 - 2, 'SOLID STATE  ·  COLOR',
                   'georgiab.ttf', 22, (222, 200, 150), spacing=4)
        elif era in ('80s_us', '80s_eu'):
            us = era == '80s_us'
            panel = (R[0] + 4, R[1] + 40, R[2] - 40, R[3] - 40)
            pp = p.rr(panel, 10)
            p.fill(pp, vgrad((40, 40, 44), (22, 22, 24)) if us else vgrad((30, 30, 32), (14, 14, 15)))
            p.bevel(pp, 3, 0.3, 0.5, inset=True)
            cx = (panel[0] + panel[2]) / 2
            if us:
                seg_display(p, (cx - 70, panel[1] + 40, cx + 70, panel[1] + 120), '03', (255, 40, 30))
            else:
                seg_display(p, (cx - 80, panel[1] + 40, cx + 80, panel[1] + 120), 'P01', (60, 255, 120),
                            bg=(4, 22, 10))
            by = panel[1] + 170
            labels = ['CH▲', 'CH▼', 'VOL+', 'VOL-', 'TV/AV', 'MENU'] if not us else \
                     ['CH▲', 'CH▼', 'VOL+', 'VOL-', 'BRT', 'COLOR']
            for i, lab in enumerate(labels):
                col = i % 2
                row = i // 2
                bx = cx - 85 + col * 100
                byy = by + row * 92
                push_button(p, (bx, byy, bx + 70, byy + 40),
                            (150, 150, 156) if us else (60, 60, 64),
                            label=lab, label_col=(200, 200, 200), lsize=13)
            gy0 = by + 3 * 92 + 20
            if us:
                grille(p, (panel[0] + 24, gy0, panel[2] - 24, panel[3] - 24),
                       slots((26, 26, 28), (4, 4, 4), pitch=14, width=7), r=6)
            else:
                grille(p, (panel[0] + 24, gy0, panel[2] - 24, panel[3] - 24),
                       perforated((22, 22, 24), (2, 2, 2), pitch=13, r=4.0), r=6)
            # left: thin strip with power button
            pb = (L[0] + (L[2] - L[0]) / 2 - 18, B[1] + 18, L[0] + (L[2] - L[0]) / 2 + 18, B[1] + 54)
            led(p, (hx0 + hx1) / 2 + (hx1 - hx0) / 2 - 30, B[1] + (B[3] - B[1]) / 2, 7,
                (255, 40, 30) if us else (60, 255, 110))
            p.text((hx0 + hx1) / 2, B[1] + (B[3] - B[1]) / 2,
                   'CABLE READY  ·  QUARTZ TUNING' if us else 'STEREO  ·  PAL / SECAM',
                   'arialbd.ttf', 20, (60, 60, 64) if us else (170, 170, 176), spacing=3)
            if not us:  # silver pinstripe around the whole front
                ln = p.rr((cab[0] + 14, cab[1] + 14, cab[2] - 14, cab[3] - 14), 18)
                (x0, y0), mm = ln
                inner = p.rr((cab[0] + 17, cab[1] + 17, cab[2] - 17, cab[3] - 17), 16)[1]
                ring = Image.fromarray(out8(np.asarray(mm, np.float32) - np.pad(
                    np.asarray(inner, np.float32), ((S(3), mm.height - inner.height - S(3)),
                                                     (S(3), mm.width - inner.width - S(3))))))
                p.fill(((x0, y0), ring), (170, 170, 176))
        elif era in ('90s_md', '90s_snes'):
            md = era == '90s_md'
            for side in (L, R):
                box = (side[0] + (30 if side is L else 8), side[1] + 40, side[2] - (8 if side is L else 30),
                       side[3] - 40)
                if md:
                    grille(p, box, slots((22, 22, 24), (2, 2, 2), pitch=15, width=8), r=12)
                else:
                    grille(p, box, perforated((52, 52, 56), (6, 6, 8), pitch=14, r=4.4), r=40)
            by = B[1] + (B[3] - B[1]) / 2
            led(p, hx0 + 40, by, 7, (255, 40, 30) if md else (60, 255, 110))
            for i in range(5):
                bx = hx1 - 60 - i * 62
                push_button(p, (bx - 22, by - 12, bx + 22, by + 12), (60, 60, 64) if md else (92, 92, 98), r=10)
            p.text((hx0 + hx1) / 2 - 80, by, 'HI-FI STEREO' if md else 'SURROUND STEREO',
                   'arialbi.ttf', 24, (150, 150, 156) if md else (190, 190, 196), spacing=4)
        else:  # late 90s
            black = era == 'late90s_black'
            for side in (L, R):
                box = (side[0] + (26 if side is L else 6), side[1] + 50, side[2] - (6 if side is L else 26),
                       side[3] - 50)
                if black:
                    grille(p, box, fabric((26, 26, 28), (44, 44, 48), seed=9, period=3.0), r=16)
                else:
                    grille(p, box, mesh((14, 14, 16), (64, 66, 70), pitch=4.0), r=16)
            by = B[1] + (B[3] - B[1]) / 2
            led(p, hx1 - 30, by, 6, (255, 40, 30) if black else (60, 150, 255))
            badge = ((hx0 + hx1) / 2 - 90, by - 16, (hx0 + hx1) / 2 + 90, by + 16)
            bp = p.rr(badge, 8)
            p.fill(bp, brushed((190, 190, 196)) if black else vgrad((30, 30, 34), (16, 16, 18)))
            p.bevel(bp, 2, 0.4, 0.4)
            p.text((hx0 + hx1) / 2, by, st.get('badge') or ('FINE PITCH' if black else 'DIGITAL SCAN'),
                   'arialbd.ttf', 16, (40, 40, 44) if black else (180, 180, 186), spacing=3)
            p.text(hx0 + 60, by, 'STEREO', 'arialbd.ttf', 16, (110, 110, 116) if black else (60, 60, 66),
                   spacing=3, align='l')
    else:
        B = g['bottom']
        bx0, by0, bx1, by1 = B
        bh = by1 - by0
        cy = by0 + bh / 2 + 6
        if era == '70s':
            gbox = (bx0 + 70, by0 + 70, bx0 + (bx1 - bx0) * 0.62, by1 - 60)
            grille(p, gbox, fabric((60, 40, 20), (150, 112, 60), seed=3, lurex=(200, 170, 110)), r=14)
            plate = (gbox[2] + 40, by0 + 60, bx1 - 70, by1 - 50)
            pp = p.rr(plate, 12)
            p.fill(pp, brushed((196, 192, 182)))
            p.bevel(pp, 4, 0.5, 0.45)
            pcx = (plate[0] + plate[2]) / 2
            knob(p, pcx - 170, cy - 20, 92, ticks=[str(i) for i in range(2, 14)], pointer=-80, label='VHF',
                 label_col=(40, 30, 20))
            knob(p, pcx + 120, cy - 30, 66, pointer=40, label='UHF', label_col=(40, 30, 20), ridges=36)
            knob(p, pcx + 300, cy - 40, 30, label='VOL', label_col=(40, 30, 20), ridges=24)
            p.text((gbox[0] + gbox[2]) / 2, by0 + 36, 'SOLID STATE  ·  COLOR', 'georgiab.ttf', 30,
                   (222, 200, 150), spacing=5)
        elif era in ('80s_us', '80s_eu'):
            us = era == '80s_us'
            panel = (bx0 + 60, by0 + 50, bx1 - 60, by1 - 50)
            pp = p.rr(panel, 12)
            p.fill(pp, vgrad((40, 40, 44), (22, 22, 24)) if us else vgrad((30, 30, 32), (14, 14, 15)))
            p.bevel(pp, 3, 0.3, 0.5, inset=True)
            pcy = (panel[1] + panel[3]) / 2
            if us:
                seg_display(p, (panel[0] + 60, pcy - 60, panel[0] + 260, pcy + 60), '03', (255, 40, 30))
            else:
                seg_display(p, (panel[0] + 60, pcy - 60, panel[0] + 300, pcy + 60), 'P01', (60, 255, 120),
                            bg=(4, 22, 10))
            labels = ['CH▲', 'CH▼', 'VOL+', 'VOL-', 'BRT', 'COLOR'] if us else \
                     ['CH▲', 'CH▼', 'VOL+', 'VOL-', 'TV/AV', 'MENU']
            for i, lab in enumerate(labels):
                x = panel[0] + 360 + i * 130
                push_button(p, (x, pcy - 40, x + 96, pcy + 14), (150, 150, 156) if us else (60, 60, 64),
                            label=lab, label_col=(200, 200, 200), lsize=18)
            gx0 = panel[0] + 360 + 6 * 130 + 30
            src = slots((26, 26, 28), (4, 4, 4), pitch=16, width=8, vertical=True) if us else \
                perforated((22, 22, 24), (2, 2, 2), pitch=15, r=4.6)
            grille(p, (gx0, panel[1] + 30, panel[2] - 30, panel[3] - 30), src, r=8)
            p.text((bx0 + bx1) / 2, by1 - 26, 'CABLE READY  ·  QUARTZ TUNING' if us else 'STEREO  ·  PAL / SECAM',
                   'arialbd.ttf', 22, (60, 60, 64) if us else (170, 170, 176), spacing=4)
            led(p, bx1 - 90, by0 + 26, 8, (255, 40, 30) if us else (60, 255, 110))
        elif era in ('90s_md', '90s_snes'):
            md = era == '90s_md'
            wspk = (bx1 - bx0) * 0.3
            for gx in (bx0 + 60, bx1 - 60 - wspk):
                box = (gx, by0 + 60, gx + wspk, by1 - 60)
                if md:
                    grille(p, box, slots((22, 22, 24), (2, 2, 2), pitch=16, width=8, vertical=True), r=14)
                else:
                    grille(p, box, perforated((52, 52, 56), (6, 6, 8), pitch=15, r=4.6), r=60)
            mx = (bx0 + bx1) / 2
            led(p, mx - 250, cy - 50, 9, (255, 40, 30) if md else (60, 255, 110))
            for i in range(5):
                x = mx - 160 + i * 90
                push_button(p, (x - 30, cy - 66, x + 30, cy - 34), (60, 60, 64) if md else (92, 92, 98), r=12)
            p.text(mx, cy + 50, 'HI-FI STEREO' if md else 'SURROUND STEREO', 'arialbi.ttf', 38,
                   (150, 150, 156) if md else (190, 190, 196), spacing=6)
        else:
            black = era == 'late90s_black'
            wspk = (bx1 - bx0) * 0.32
            for gx in (bx0 + 50, bx1 - 50 - wspk):
                box = (gx, by0 + 70, gx + wspk, by1 - 60)
                if black:
                    grille(p, box, fabric((26, 26, 28), (44, 44, 48), seed=9, period=3.0), r=20)
                else:
                    grille(p, box, mesh((14, 14, 16), (64, 66, 70), pitch=4.0), r=20)
            mx = (bx0 + bx1) / 2
            badge = (mx - 150, cy - 70, mx + 150, cy - 20)
            bp = p.rr(badge, 10)
            p.fill(bp, brushed((190, 190, 196)) if black else vgrad((30, 30, 34), (16, 16, 18)))
            p.bevel(bp, 2, 0.4, 0.4)
            p.text(mx, cy - 45, st.get('badge') or ('FINE PITCH' if black else 'DIGITAL SCAN'), 'arialbd.ttf', 24,
                   (40, 40, 44) if black else (180, 180, 186), spacing=4)
            led(p, mx, cy + 40, 8, (255, 40, 30) if black else (60, 150, 255))
            p.text(mx, cy + 90, 'STEREO', 'arialbd.ttf', 22, (110, 110, 116) if black else (60, 60, 66), spacing=4)

    # ---- screen surround
    kind = st['surround'][0]
    sw = st['surround'][1]
    hr = st['hole_r'] * hh
    sbox = (hx0 - sw - st['recess'], hy0 - sw - st['recess'], hx1 + sw + st['recess'], hy1 + sw + st['recess'])
    spm = p.rr(sbox, hr + sw + st['recess'])
    if kind == 'chrome':
        p.fill(spm, brushed((210, 208, 204), sheen=0.3))
        p.bevel(spm, 5, 0.6, 0.5)
    elif kind == 'pinstripe':
        p.fill(spm, st['surround'][2])
        p.bevel(spm, 3, 0.25, 0.5)
        ln = p.rr((sbox[0] + 6, sbox[1] + 6, sbox[2] - 6, sbox[3] - 6), hr + sw + st['recess'] - 6)
        inner = p.rr((sbox[0] + 9, sbox[1] + 9, sbox[2] - 9, sbox[3] - 9), hr + sw + st['recess'] - 9)
        (x0, y0), mm = ln
        im2 = np.zeros((mm.height, mm.width), np.float32)
        (ix, iy), imk = inner
        im2[iy - y0:iy - y0 + imk.height, ix - x0:ix - x0 + imk.width] = np.asarray(imk, np.float32)
        ring = Image.fromarray(out8(np.asarray(mm, np.float32) - im2))
        p.fill(((x0, y0), ring), (175, 175, 182))
    elif kind == 'gloss':
        p.fill(spm, vgrad(tuple(c + 26 for c in st['surround'][2]), st['surround'][2], gamma=0.6))
        p.bevel(spm, 3, 0.35, 0.3, inset=True)
    else:
        p.fill(spm, vgrad(tuple(c + 18 for c in st['surround'][2]), st['surround'][2]))
        p.bevel(spm, 4, 0.3, 0.5, inset=True)
    # tube recess (dark gap between the plastic and the glass)
    rbox = (hx0 - st['recess'], hy0 - st['recess'], hx1 + st['recess'], hy1 + st['recess'])
    rpm = p.rr(rbox, hr + st['recess'])
    p.fill(rpm, (6, 6, 7))
    p.bevel(rpm, 6, 0.08, 0.6, inset=True)
    # unlit phosphor colour is applied by the shader; cut the glass
    p.cut_hole(hole, hr, inner_shadow=0.55, reflection=0.07, shadow_size=12)
    return p, g


# --------------------------------------------------------------------------- handhelds

HANDHELD = {
    'gb': dict(aspect=10 / 9, bg=(14, 14, 16),
               shell=vgrad((206, 203, 196), (182, 179, 172), grain=2.2, seed=31),
               lens=vgrad((96, 98, 114), (82, 84, 100)), lens_txt=(200, 200, 212),
               ink=(38, 40, 120), big_corner=True),
    'gbc': dict(aspect=10 / 9, bg=(12, 10, 16),
                shell=vgrad((108, 78, 168), (78, 52, 132), grain=2.0, seed=32),
                lens=vgrad((34, 34, 40), (22, 22, 26)), lens_txt=(220, 220, 228),
                ink=(220, 214, 240), big_corner=False),
    'gba': dict(aspect=3 / 2, bg=(10, 10, 16),
                shell=vgrad((88, 80, 170), (60, 54, 132), grain=1.8, seed=33),
                lens=vgrad((30, 30, 38), (18, 18, 24)), lens_txt=(200, 200, 214),
                ink=(210, 206, 240), big_corner=False),
    'gamegear': dict(aspect=10 / 9, bg=(10, 10, 12),
                     shell=vgrad((44, 44, 48), (22, 22, 25), grain=1.6, seed=34),
                     lens=vgrad((46, 46, 52), (32, 32, 36)), lens_txt=(200, 200, 210),
                     ink=(150, 160, 210), big_corner=False),
}


def hh_geom(sysname, layout):
    st = HANDHELD[sysname]
    ar = st['aspect']
    if layout == 'wide':
        W, H = WIDE
        if sysname == 'gba':
            hh, mt, mb, ms, ly = 820, 46, 104, 60, 56
            shell = (24, 26, 2396, 1062)
        elif sysname == 'gamegear':
            hh, mt, mb, ms, ly = 800, 56, 110, 72, 50
            shell = (24, 22, 2396, 1060)
        else:
            hh, mt, mb, ms, ly = 790, 110, 70, 80, 60 if sysname == 'gb' else 50
            if sysname == 'gbc':
                hh, mt, mb = 790, 60, 120
            shell = (24, 20, 2396, 1060)
        hw = hh * ar
        lw, lh = hw + 2 * ms, hh + mt + mb
        lx = (W - lw) / 2
        lens = (lx, ly, lx + lw, ly + lh)
        hole = (lx + ms, ly + mt, lx + ms + hw, ly + mt + hh)
        return dict(shell=shell, lens=lens, hole=hole, body=(0, 0, W, H))
    else:
        W, H = SQUARE
        shell = (30, SQ_BODY[1] + 20, W - 30, SQ_BODY[3] - 20)
        if sysname == 'gba':
            hw, mt, mb, ms, ly = 1900, 70, 120, 70, 200
        elif sysname == 'gamegear':
            hw, mt, mb, ms, ly = 1640, 70, 130, 80, 180
        elif sysname == 'gbc':
            hw, mt, mb, ms, ly = 1560, 80, 140, 80, 180
        else:
            hw, mt, mb, ms, ly = 1500, 150, 80, 90, 180
        hh = hw / ar
        lw, lh = hw + 2 * ms, hh + mt + mb
        lx = (W - lw) / 2
        lens = (lx, ly, lx + lw, ly + lh)
        hole = (lx + ms, ly + mt, lx + ms + hw, ly + mt + hh)
        return dict(shell=shell, lens=lens, hole=hole, body=SQ_BODY)


def draw_handheld(sysname, layout):
    st = HANDHELD[sysname]
    g = hh_geom(sysname, layout)
    size = WIDE if layout == 'wide' else SQUARE
    p = Painter(size, st['bg'])
    room(p, 'desk', g['shell'])
    sh, ln, ho = g['shell'], g['lens'], g['hole']
    wide = layout == 'wide'
    W = size[0]

    # shoulders (GBA)
    if sysname == 'gba':
        for side in (0, 1):
            if wide:
                b = (sh[0] + 30, sh[1] - 8, sh[0] + 560, sh[1] + 260) if side == 0 else \
                    (sh[2] - 560, sh[1] - 8, sh[2] - 30, sh[1] + 260)
            else:
                b = (sh[0] + 20, sh[1] - 14, sh[0] + 620, sh[1] + 300) if side == 0 else \
                    (sh[2] - 620, sh[1] - 14, sh[2] - 20, sh[1] + 300)
            bp = p.rr(b, 220)
            p.fill(bp, vgrad((150, 150, 160), (110, 110, 120)))
            p.bevel(bp, 4, 0.4, 0.4)

    # shell
    if sysname == 'gb':
        radii = (60, 60, 260, 60) if wide else (70, 70, 300, 70)
    elif sysname == 'gba':
        radii = (300, 300, 300, 300) if wide else (220, 220, 260, 260)
    elif sysname == 'gamegear':
        radii = (320, 320, 320, 320) if wide else (200, 200, 260, 260)
    else:
        radii = (90, 90, 150, 150) if wide else (110, 110, 200, 200)
    spm = p.rr(sh, 0, radii)
    p.shadow(spm, 0, 14, 26, 0.85)
    p.fill(spm, st['shell'])
    p.bevel(spm, 8, 0.4, 0.45)
    # soft top sheen on shell
    shn = p.rr((sh[0] + 40, sh[1] + 14, sh[2] - 40, sh[1] + (sh[3] - sh[1]) * 0.35), 0,
               tuple(max(0, r - 30) for r in radii[:2]) + (40, 40))
    p.fill(shn, vgrad((255, 255, 255), (255, 255, 255)), alpha=0.05)

    # lens
    if sysname == 'gb':
        lr = (26, 26, 140, 26) if wide else (30, 30, 170, 30)
    elif sysname == 'gamegear':
        lr = (60, 60, 60, 60)
    else:
        lr = (40, 40, 40, 40)
    lpm = p.rr(ln, 0, lr)
    p.fill(lpm, st['lens'])
    p.bevel(lpm, 5, 0.18, 0.55, inset=True)
    lx0, ly0, lx1, ly1 = ln
    hx0, hy0, hx1, hy1 = ho
    lcx = (lx0 + lx1) / 2
    lsc = 1.0 if wide else 1.6   # text scale on the big layout

    if sysname == 'gb':
        ty = ly0 + (hy0 - ly0) * 0.48
        txt = 'DOT MATRIX WITH STEREO SOUND'
        p.text(lcx, ty, txt, 'arialbi.ttf', 21 * lsc, st['lens_txt'], spacing=1.5 * lsc)
        tw = 420 * lsc
        for x0, x1 in ((lx0 + 40, lcx - tw / 2 - 20), (lcx + tw / 2 + 20, lx1 - 40)):
            p.fill(p.rr((x0, ty - 9 * lsc, x1, ty - 3 * lsc), 0), (140, 28, 74))
            p.fill(p.rr((x0, ty + 3 * lsc, x1, ty + 9 * lsc), 0), (40, 40, 130))
        led(p, (lx0 + hx0) / 2, hy0 + (hy1 - hy0) * 0.28, 9 * lsc, (255, 30, 40))
        p.text((lx0 + hx0) / 2, hy0 + (hy1 - hy0) * 0.28 + 30 * lsc, 'BATTERY', 'arialbd.ttf', 11 * lsc,
               st['lens_txt'])
    elif sysname == 'gbc':
        led(p, (lx0 + hx0) / 2, hy0 + (hy1 - hy0) * 0.3, 9 * lsc, (255, 30, 40))
        p.text((lx0 + hx0) / 2, hy0 + (hy1 - hy0) * 0.3 + 30 * lsc, 'POWER', 'arialbd.ttf', 11 * lsc,
               st['lens_txt'])
        ty = hy1 + (ly1 - hy1) * 0.5
        p.text(lcx - 120 * lsc, ty, 'GAME BOY', 'ariblk.ttf', 40 * lsc, (236, 236, 244), italic=0.22,
               spacing=2 * lsc)
        p.multicolor_text(lcx + 140 * lsc, ty, 'COLOR',
                          [(230, 40, 60), (140, 60, 220), (40, 190, 80), (250, 200, 30), (40, 120, 240)],
                          'ariblk.ttf', 40 * lsc, italic=0.22, spacing=2 * lsc)
    elif sysname == 'gba':
        ty = hy1 + (ly1 - hy1) * 0.52
        p.text(lcx, ty, 'GAME BOY ADVANCE', 'ariblk.ttf', 36 * lsc, st['lens_txt'], italic=0.2, spacing=4 * lsc)
    elif sysname == 'gamegear':
        ty = hy1 + (ly1 - hy1) * 0.52
        p.text(lcx, ty, 'GAME GEAR', 'ariblk.ttf', 40 * lsc, (225, 225, 235), italic=0.18, spacing=6 * lsc)
        for i, col in enumerate([(230, 40, 60), (250, 200, 30), (40, 190, 80), (40, 120, 240)]):
            p.fill(p.ell(lcx + (200 + i * 34) * lsc, ty, 9 * lsc), col)
            p.fill(p.ell(lcx - (200 + i * 34) * lsc, ty, 9 * lsc), col)

    # ---- controls
    if wide:
        lxm = (sh[0] + lx0) / 2
        rxm = (lx1 + sh[2]) / 2
        cy = (sh[1] + sh[3]) / 2
        if sysname == 'gb':
            p.text(lxm, 200, 'GAME BOY', 'ariblk.ttf', 64, st['ink'], italic=0.25, spacing=2)
            dpad(p, lxm, cy + 40, 270, well=(170, 167, 160))
            pill(p, lxm - 80, 900, 104, 30, (120, 118, 126), angle=25, label='SELECT', label_col=st['ink'])
            pill(p, lxm + 80, 900, 104, 30, (120, 118, 126), angle=25, label='START', label_col=st['ink'])
            round_button(p, rxm - 95, cy + 80, 78, (156, 30, 84), label='B', label_col=st['ink'])
            round_button(p, rxm + 95, cy - 10, 78, (156, 30, 84), label='A', label_col=st['ink'])
            slot_cluster(p, rxm + 40, 930, 6, 150, 18, 38, 30)
            p.text(lxm - 120, 70, '◄ OFF · ON ►', 'arialbd.ttf', 20, (120, 118, 112), align='l', spacing=2)
        elif sysname == 'gbc':
            dpad(p, lxm, cy, 270, col=(36, 30, 50), well=(70, 46, 120))
            round_button(p, rxm - 95, cy + 70, 76, (44, 36, 60), label='B', label_col=st['ink'])
            round_button(p, rxm + 95, cy - 20, 76, (44, 36, 60), label='A', label_col=st['ink'])
            pill(p, lxm - 70, 880, 90, 28, (52, 44, 70), angle=0, label='SELECT', label_col=st['ink'])
            pill(p, lxm + 70, 880, 90, 28, (52, 44, 70), angle=0, label='START', label_col=st['ink'])
            hole_grid(p, (rxm - 90, 820, rxm + 90, 1000), 30, 7, circle=(rxm, 910, 90))
        elif sysname == 'gba':
            dpad(p, lxm + 40, cy - 20, 250, col=(30, 30, 34), well=(70, 64, 150))
            led(p, lxm + 40, 230, 8, (60, 230, 90))
            p.text(lxm + 40, 262, 'POWER', 'arialbd.ttf', 14, st['ink'], spacing=2)
            round_button(p, rxm - 120, cy + 40, 74, (150, 146, 196), label='B', label_col=st['ink'])
            round_button(p, rxm + 40, cy - 40, 74, (150, 146, 196), label='A', label_col=st['ink'])
            round_button(p, lxm + 0, 850, 22, (60, 56, 100), label='START', label_col=st['ink'], lsize=14,
                         lpos=(0, 2.2))
            round_button(p, lxm + 100, 850, 22, (60, 56, 100), label='SELECT', label_col=st['ink'], lsize=14,
                         lpos=(0, 2.2))
            hole_grid(p, (rxm - 60, 760, rxm + 160, 960), 28, 6, circle=(rxm + 50, 860, 100))
        elif sysname == 'gamegear':
            pw = p.ell(lxm + 20, cy, 170)
            p.fill(pw, (18, 18, 20))
            p.bevel(pw, 4, 0.2, 0.4, inset=True)
            dpad(p, lxm + 20, cy, 250, col=(44, 44, 50))
            round_button(p, rxm - 100, cy + 50, 80, (58, 58, 66), label='1', label_col=st['ink'], lsize=34)
            round_button(p, rxm + 100, cy - 10, 80, (58, 58, 66), label='2', label_col=st['ink'], lsize=34)
            round_button(p, rxm + 80, 230, 30, (40, 90, 200), label='START', label_col=st['ink'], lsize=16,
                         lpos=(0, 1.9))
            slot_cluster(p, lxm + 20, 920, 7, 120, 14, 34, 0)
    else:
        top = ly1
        bot = sh[3]
        cy = (top + bot) / 2
        lxm, rxm = 420, W - 420
        if sysname == 'gb':
            p.text(W / 2, top + 70, 'GAME BOY', 'ariblk.ttf', 84, st['ink'], italic=0.25, spacing=3)
            dpad(p, lxm, cy + 30, 300, well=(170, 167, 160))
            round_button(p, rxm - 110, cy + 70, 92, (156, 30, 84), label='B', label_col=st['ink'], lsize=40)
            round_button(p, rxm + 110, cy - 20, 92, (156, 30, 84), label='A', label_col=st['ink'], lsize=40)
            pill(p, W / 2 - 100, cy + 70, 130, 36, (120, 118, 126), angle=25, label='SELECT',
                 label_col=st['ink'], lsize=22)
            pill(p, W / 2 + 100, cy + 70, 130, 36, (120, 118, 126), angle=25, label='START',
                 label_col=st['ink'], lsize=22)
            p.text(sh[0] + 90, sh[1] + 40, '◄ OFF · ON ►', 'arialbd.ttf', 26, (120, 118, 112), align='l', spacing=3)
        elif sysname == 'gbc':
            dpad(p, lxm, cy, 300, col=(36, 30, 50), well=(70, 46, 120))
            round_button(p, rxm - 110, cy + 60, 92, (44, 36, 60), label='B', label_col=st['ink'], lsize=40)
            round_button(p, rxm + 110, cy - 30, 92, (44, 36, 60), label='A', label_col=st['ink'], lsize=40)
            pill(p, W / 2 - 100, cy + 40, 120, 34, (52, 44, 70), angle=0, label='SELECT', label_col=st['ink'],
                 lsize=22)
            pill(p, W / 2 + 100, cy + 40, 120, 34, (52, 44, 70), angle=0, label='START', label_col=st['ink'],
                 lsize=22)
        elif sysname == 'gba':
            dpad(p, lxm, cy, 330, col=(30, 30, 34), well=(70, 64, 150))
            round_button(p, rxm - 130, cy + 60, 100, (150, 146, 196), label='B', label_col=st['ink'], lsize=40)
            round_button(p, rxm + 90, cy - 40, 100, (150, 146, 196), label='A', label_col=st['ink'], lsize=40)
            round_button(p, W / 2 - 90, cy + 120, 30, (60, 56, 100), label='SELECT', label_col=st['ink'],
                         lsize=20, lpos=(0, 2.1))
            round_button(p, W / 2 + 90, cy + 120, 30, (60, 56, 100), label='START', label_col=st['ink'],
                         lsize=20, lpos=(0, 2.1))
            led(p, W / 2, cy - 90, 10, (60, 230, 90))
            p.text(W / 2, cy - 50, 'POWER', 'arialbd.ttf', 20, st['ink'], spacing=3)
        elif sysname == 'gamegear':
            pw = p.ell(lxm, cy, 170)
            p.fill(pw, (18, 18, 20))
            p.bevel(pw, 4, 0.2, 0.4, inset=True)
            dpad(p, lxm, cy, 260, col=(44, 44, 50))
            round_button(p, rxm - 110, cy + 50, 92, (58, 58, 66), label='1', label_col=st['ink'], lsize=40)
            round_button(p, rxm + 110, cy - 20, 92, (58, 58, 66), label='2', label_col=st['ink'], lsize=40)
            round_button(p, W / 2 + 200, cy - 20, 32, (40, 90, 200), label='START', label_col=st['ink'],
                         lsize=20, lpos=(0, 1.9))
            slot_cluster(p, W / 2 - 160, cy, 7, 140, 16, 38, 0)

    p.cut_hole(ho, 8, inner_shadow=0.35, reflection=0.035, shadow_size=8)
    return p, g


# --------------------------------------------------------------------------- pocket TVs (cover screen, portrait)
# Retro TV app only: the folded phone held upright becomes an original 80s/90s pocket television.

TALL = (1080, 2420)

POCKET = {
    # 80s pocket CRT: charcoal body, brushed-silver front, tuning scale + side thumbwheels
    'tvcrt': dict(aspect=4 / 3, hole_w=860, hole_y=232, era='crt'),
    # 90s pocket colour LCD: silver body, membrane keys, segment channel display
    'tvwide': dict(aspect=16 / 9, hole_w=900, hole_y=236, era='lcd',
                   fx=dict(scan=0.04, curve=0.0, vignette=0.12)),
}


def thumbwheel(p, box, col=(46, 46, 50), ribs=26, horizontal=False):
    """Ribbed wheel poking out of the body edge (side volume / tuning wheels)."""
    pm = p.rr(box, 10)
    p.shadow(pm, 0, 4, 6, 0.7)
    x0, y0, x1, y1 = box

    def f(w, h):
        t = np.linspace(0, 1, h if not horizontal else w, dtype=np.float32)
        curve = np.clip(np.sin(t * np.pi), 0, 1) ** 0.6                      # cylinder shading
        rib = 0.78 + 0.22 * (np.sin(t * np.pi * ribs * 2) > 0)  # knurled ridges
        v = (0.35 + 0.75 * curve) * rib
        v = v[:, None] if not horizontal else v[None, :]
        v = np.broadcast_to(v, (h, w))
        return out8(np.array(col, np.float32) * v[..., None] * 1.6)
    p.fill(pm, f)
    p.bevel(pm, 2, 0.2, 0.5)


def slide_switch(p, cx, cy, w, positions, sel, labels_col, track=(10, 10, 11), knob_col=(170, 170, 176),
                 title=None, lsize=19):
    """Horizontal slide switch with position labels underneath."""
    h = 34
    tr = p.rr((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), h / 2)
    p.fill(tr, track)
    p.bevel(tr, 3, 0.2, 0.6, inset=True)
    n = len(positions)
    step = (w - 56) / max(1, n - 1)
    kx = cx - w / 2 + 28 + step * sel
    kn = p.rr((kx - 30, cy - 22, kx + 30, cy + 22), 8)
    p.shadow(kn, 0, 4, 4, 0.6)
    p.fill(kn, vgrad(tuple(min(255, c + 40) for c in knob_col), knob_col))
    p.bevel(kn, 2, 0.4, 0.5)
    for i in range(4):
        p.fill(p.rr((kx - 14 + i * 9, cy - 13, kx - 11 + i * 9, cy + 13), 1), (60, 60, 64), alpha=0.6)
    for i, lab in enumerate(positions):
        p.text(cx - w / 2 + 28 + step * i, cy + 46, lab, 'arialbd.ttf', lsize, labels_col)
    if title:
        p.text(cx, cy - 50, title, 'arialbd.ttf', lsize, labels_col, spacing=3)


def tuning_scale(p, box, needle):
    """Backlit VHF/UHF channel scale with a red needle (0..1 across)."""
    x0, y0, x1, y1 = box
    pm = p.rr(box, 12)
    p.fill(pm, vgrad((22, 20, 16), (8, 8, 7)))
    p.bevel(pm, 4, 0.15, 0.7, inset=True)
    ink = (226, 214, 180)
    pad = 54
    span = x1 - x0 - 2 * pad
    ym = (y0 + y1) / 2
    p.text(x0 + 26, ym - 26, 'VHF', 'arialbd.ttf', 15, (240, 120, 70), align='l')
    p.text(x0 + 26, ym + 28, 'UHF', 'arialbd.ttf', 15, (120, 190, 240), align='l')
    vhf = [str(i) for i in range(2, 14)]
    for i, s in enumerate(vhf):
        x = x0 + pad + 30 + (span - 30) * i / (len(vhf) - 1)
        p.text(x, ym - 26, s, 'arialbd.ttf', 19, ink)
    uhf = ['14', '20', '30', '40', '50', '60', '70', '83']
    for i, s in enumerate(uhf):
        x = x0 + pad + 30 + (span - 30) * i / (len(uhf) - 1)
        p.text(x, ym + 28, s, 'arialbd.ttf', 19, ink)
    # tick rail through the middle
    for i in range(61):
        x = x0 + pad + 30 + (span - 30) * i / 60
        th = 12 if i % 5 == 0 else 6
        p.fill(p.rr((x - 1.2, ym - th / 2, x + 1.2, ym + th / 2), 1), ink, alpha=0.7)
    nx = x0 + pad + 30 + (span - 30) * needle
    p.glow(nx, ym, 30, (255, 60, 30), 0.35)
    p.fill(p.rr((nx - 3, y0 + 12, nx + 3, y1 - 12), 2), (255, 70, 40))
    # glass sheen
    p.fill(p.rr((x0 + 8, y0 + 6, x1 - 8, y0 + (y1 - y0) * 0.42), 8), (255, 255, 255), alpha=0.05)


def screw(p, cx, cy, r=9, col=(120, 120, 126)):
    pm = p.ell(cx, cy, r)
    p.fill(pm, vgrad(tuple(min(255, c + 50) for c in col), col))
    p.bevel(pm, 1.5, 0.4, 0.5)
    p.fill(p.rr((cx - r * 0.7, cy - 1.5, cx + r * 0.7, cy + 1.5), 1), (30, 30, 32), alpha=0.8)


def jack(p, cx, cy, r, label, ink, lsize=15):
    pr = p.ell(cx, cy, r * 1.6)
    p.fill(pr, brushed((150, 150, 156)))
    p.bevel(pr, 2, 0.4, 0.5)
    ph = p.ell(cx, cy, r)
    p.fill(ph, (4, 4, 4))
    p.bevel(ph, 2, 0.2, 0.6, inset=True)
    p.text(cx, cy + r * 1.6 + lsize, label, 'arialbd.ttf', lsize, ink, spacing=1)


def draw_pocket(sysname):
    st = POCKET[sysname]
    W, H = TALL
    crt = st['era'] == 'crt'
    p = Painter(TALL, (4, 4, 5))
    body = (6, 6, W - 6, H - 6)
    hw = st['hole_w']
    hh = hw / st['aspect']
    hx0 = (W - hw) / 2
    hy0 = st['hole_y']
    hole = (hx0, hy0, hx0 + hw, hy0 + hh)
    hx1, hy1 = hole[2], hole[3]
    cx = W / 2

    # ---- body shell (fills the whole cover screen; corners follow the display's rounding)
    bp = p.rr(body, 82)
    if crt:
        p.fill(bp, vgrad((46, 46, 50), (22, 22, 24), gamma=0.9, grain=1.4, seed=41))
    else:
        p.fill(bp, brushed((182, 184, 190), seed=42, sheen=0.1))
    p.bevel(bp, 7, 0.3 if crt else 0.55, 0.55)
    ink = (196, 196, 202) if crt else (40, 40, 46)
    ink_dim = (130, 130, 136) if crt else (88, 88, 96)

    # parting line round the case
    ln = p.rr((26, 26, W - 26, H - 26), 66)
    p.bevel(ln, 2, 0.12, 0.35, inset=True)

    # ---- top edge: collapsed telescopic antenna (clear of the camera hole), power LED
    ant = (640, 56, 1004, 82)
    p.shadow(p.rr(ant, 13), 0, 4, 4, 0.6)
    p.fill(p.rr(ant, 13), vgrad((236, 236, 240), (120, 120, 126), gamma=0.7))
    for i, x in enumerate((700, 780, 870)):
        p.fill(p.rr((x, ant[1] - 1, x + 6, ant[3] + 1), 2), (90, 90, 96))
    tip = p.ell(ant[2] - 6, (ant[1] + ant[3]) / 2, 17)
    p.fill(tip, vgrad((240, 240, 244), (130, 130, 136)))
    p.bevel(tip, 2, 0.4, 0.5)
    hinge = p.rr((ant[0] - 40, 46, ant[0] + 10, 92), 10)
    p.fill(hinge, (14, 14, 15) if crt else (60, 60, 66))
    p.bevel(hinge, 2, 0.3, 0.5)
    led(p, 120, 72, 8, (255, 50, 30))
    p.text(146, 72, 'POWER', 'arialbd.ttf', 16, ink_dim, spacing=2, align='l')

    # ---- screen surround
    sw = 46
    if crt:
        # brushed silver face plate carrying screen + tuning scale
        face = (hx0 - sw - 18, hy0 - sw - 34, hx1 + sw + 18, hy1 + 330)
        fp = p.rr(face, 40)
        p.shadow(fp, 0, 6, 8, 0.6)
        p.fill(fp, brushed((176, 176, 182), seed=43, sheen=0.22))
        p.bevel(fp, 4, 0.55, 0.45)
        sbox = (hx0 - sw, hy0 - sw, hx1 + sw, hy1 + sw)
        spm = p.rr(sbox, 34)
        p.fill(spm, vgrad((34, 34, 36), (12, 12, 13), gamma=0.6))
        p.bevel(spm, 4, 0.25, 0.55, inset=True)
        p.text(sbox[0] + 30, sbox[1] + 24, 'FLAT TUBE', 'arialbd.ttf', 14, (150, 150, 156), spacing=3,
               align='l')
        p.text(sbox[2] - 30, sbox[1] + 24, 'COLOUR', 'arialbd.ttf', 14, (150, 150, 156), spacing=3,
               align='r')
    else:
        sbox = (hx0 - 30, hy0 - 52, hx1 + 30, hy1 + 120)
        spm = p.rr(sbox, 30)
        p.shadow(spm, 0, 4, 6, 0.5)
        p.fill(spm, vgrad((30, 30, 34), (10, 10, 12), gamma=0.6))
        p.bevel(spm, 3, 0.35, 0.4, inset=True)
        p.text(sbox[0] + 30, sbox[1] + 26, 'ACTIVE MATRIX', 'arialbd.ttf', 14, (150, 150, 160), spacing=3,
               align='l')
        p.text(sbox[2] - 30, sbox[1] + 26, 'TFT', 'arialbd.ttf', 14, (150, 150, 160), spacing=3, align='r')
        # brand under the glass, on the black surround
        p.text(cx, hy1 + 62, 'PORTAVISION', 'arialbi.ttf', 40, (214, 214, 222), spacing=8)
        p.multicolor_text(cx, hy1 + 100, '●●●●', [(230, 60, 50), (240, 170, 40), (60, 180, 90), (60, 120, 230)],
                          'arialbd.ttf', 12, spacing=10)
    rec = 10
    rpm = p.rr((hx0 - rec, hy0 - rec, hx1 + rec, hy1 + rec), 26 + rec)
    p.fill(rpm, (5, 5, 6))
    p.bevel(rpm, 5, 0.08, 0.6, inset=True)

    if crt:
        # ---- brand + tuning scale on the face plate
        by = hy1 + sw + 54
        p.text(face[0] + 40, by, 'PORTAVISION', 'arialbi.ttf', 44, (34, 34, 38), spacing=6, align='l')
        p.text(face[2] - 40, by + 4, 'PV-8', 'arialbd.ttf', 30, (150, 30, 24), spacing=2, align='r')
        tuning_scale(p, (face[0] + 34, by + 46, face[2] - 34, face[3] - 30), needle=0.235)

        # ---- side thumbwheels (look like they stick out of the case edges)
        wy0 = face[3] + 70
        thumbwheel(p, (W - 62, wy0, W - 4, wy0 + 330), ribs=30)
        p.text(W - 82, wy0 + 165, 'TUNING', 'arialbd.ttf', 17, ink_dim, spacing=3, rotate=90)
        thumbwheel(p, (4, wy0 + 30, 62, wy0 + 270), ribs=24)
        p.text(82, wy0 + 150, 'VOLUME', 'arialbd.ttf', 17, ink_dim, spacing=3, rotate=-90)

        # ---- control strip: band + power slide switches, brightness knob
        cy0 = wy0 + 70
        slide_switch(p, 330, cy0 + 40, 300, ['VHF-L', 'VHF-H', 'UHF'], 1, ink, title='BAND')
        slide_switch(p, 740, cy0 + 40, 220, ['OFF', 'ON'], 1, ink, knob_col=(200, 60, 44), title='POWER')
        knob(p, 330, cy0 + 220, 40, metal=(170, 170, 176), cap=(30, 30, 32), pointer=30, ridges=36,
             label='BRIGHT', label_col=ink)
        knob(p, 540, cy0 + 220, 40, metal=(170, 170, 176), cap=(30, 30, 32), pointer=-20, ridges=36,
             label='CONTRAST', label_col=ink)
        knob(p, 750, cy0 + 220, 40, metal=(170, 170, 176), cap=(30, 30, 32), pointer=60, ridges=36,
             label='FINE', label_col=ink)

        # ---- speaker: rectangular perforated grille in a silver frame
        frame = (110, cy0 + 345, W - 110, cy0 + 795)
        fpm = p.rr(frame, 28)
        p.shadow(fpm, 0, 6, 10, 0.6)
        p.fill(fpm, brushed((168, 168, 174), seed=44, sheen=0.25))
        p.bevel(fpm, 4, 0.5, 0.5)
        well = (frame[0] + 24, frame[1] + 24, frame[2] - 24, frame[3] - 24)
        wpm = p.rr(well, 14)
        p.fill(wpm, (26, 26, 28))
        p.bevel(wpm, 4, 0.2, 0.6, inset=True)
        pitch = 30
        gw, gh = well[2] - well[0] - 30, well[3] - well[1] - 30
        nx, ny = int(gw // pitch), int(gh // pitch)
        gx0 = (well[0] + well[2]) / 2 - nx * pitch / 2
        gy0 = (well[1] + well[3]) / 2 - ny * pitch / 2
        hole_grid(p, (gx0, gy0, gx0 + nx * pitch, gy0 + ny * pitch), pitch, 8.5, col=(0, 0, 0), alpha=0.9)
        # small maker's plate on the frame's lower edge
        plate = (cx - 70, frame[3] - 20, cx + 70, frame[3] + 20)
        ppm = p.rr(plate, 8)
        p.shadow(ppm, 0, 3, 4, 0.5)
        p.fill(ppm, brushed((196, 196, 202), seed=45))
        p.bevel(ppm, 2, 0.5, 0.5)
        p.text(cx, frame[3], 'PV-8', 'arialbi.ttf', 22, (60, 60, 66), spacing=2)
        bot = frame[3] + 20
    else:
        # ---- 90s LCD: segment channel display + membrane keypad
        dy = sbox[3] + 70
        seg_display(p, (110, dy, 420, dy + 120), 'CH 03', (255, 176, 40), bg=(28, 16, 4))
        p.text(470, dy + 26, 'STEREO', 'arialbd.ttf', 18, ink, spacing=3, align='l')
        led(p, 470 + 12, dy + 78, 8, (60, 220, 90))
        p.text(500, dy + 78, 'SIGNAL', 'arialbd.ttf', 16, ink_dim, spacing=2, align='l')
        round_button(p, 900, dy + 60, 52, (200, 40, 36), label='POWER', label_col=ink, lpos=(0, 1.6),
                     lsize=18, well_col=(120, 120, 126))
        ky = dy + 210
        keys = [['CH ▲', 'VOL +', 'TV/AV'], ['CH ▼', 'VOL −', 'MENU']]
        for r, row in enumerate(keys):
            for c, lab in enumerate(row):
                kx = 110 + c * 300
                kb = (kx, ky + r * 150, kx + 260, ky + r * 150 + 86)
                push_button(p, kb, (64, 66, 74), r=40, label=lab, label_col=ink, lsize=19)
        # speaker slots
        gy = ky + 340
        gbox = (110, gy, W - 110, gy + 560)
        grille(p, gbox, slots((150, 152, 158), (20, 20, 22), pitch=22, width=10), r=40)
        p.text(cx, gbox[3] + 50, 'POCKET COLOUR TELEVISION', 'arialbd.ttf', 22, ink, spacing=5)
        thumbwheel(p, (W - 50, 1250, W - 4, 1520), col=(70, 70, 76), ribs=26)
        p.text(W - 70, 1385, 'VOLUME', 'arialbd.ttf', 16, ink_dim, spacing=3, rotate=90)
        bot = gbox[3] + 80

    # ---- bottom edge: jacks + screws
    jy = max(bot + 60, H - 215)
    jack(p, 200, jy, 14, 'EARPHONE', ink_dim)
    jack(p, 880, jy, 12, 'DC 6V', ink_dim)
    p.text(cx, jy, 'SOLID STATE' if crt else 'MADE FOR THE ROAD', 'arialbd.ttf', 18, ink_dim, spacing=5)
    for sx in (90, W - 90):
        screw(p, sx, H - 80, col=(90, 90, 96) if crt else (150, 150, 156))

    p.cut_hole(hole, 26, inner_shadow=0.55, reflection=0.06, shadow_size=10)
    return p, dict(hole=hole, body=(0, 0, W, H))


# --------------------------------------------------------------------------- main

def norm(rect, size):
    x0, y0, x1, y1 = rect
    W, H = size
    return [round(x0 / W, 5), round(y0 / H, 5), round((x1 - x0) / W, 5), round((y1 - y0) / H, 5)]


SYSTEM_FX = {
    'atari2600': dict(scan=0.55, curve=0.045, vignette=0.45, lcd=0.0),
    'nes': dict(scan=0.55, curve=0.035, vignette=0.4, lcd=0.0),
    'mastersystem': dict(scan=0.55, curve=0.035, vignette=0.4, lcd=0.0),
    'megadrive': dict(scan=0.5, curve=0.03, vignette=0.35, lcd=0.0),
    'snes': dict(scan=0.5, curve=0.03, vignette=0.35, lcd=0.0),
    'saturn': dict(scan=0.4, curve=0.022, vignette=0.3, lcd=0.0, lines=240),
    'n64': dict(scan=0.35, curve=0.02, vignette=0.3, lcd=0.0, lines=240),
    'gb': dict(scan=0.0, curve=0.0, vignette=0.0, lcd=0.45),
    'gbc': dict(scan=0.0, curve=0.0, vignette=0.0, lcd=0.4),
    'gba': dict(scan=0.0, curve=0.0, vignette=0.0, lcd=0.3),
    'gamegear': dict(scan=0.0, curve=0.0, vignette=0.0, lcd=0.4),
    # video sets: letterbox the video inside the tube (keep_ar), lighter CRT effects
    'tvcrt': dict(scan=0.3, curve=0.03, vignette=0.35, lcd=0.0, lines=288, keep_ar=1.0),
    'tvwide': dict(scan=0.15, curve=0.012, vignette=0.25, lcd=0.0, lines=360, keep_ar=1.0),
}


def main(outdir, only=None):
    os.makedirs(outdir, exist_ok=True)
    params = {}
    jp = os.path.join(outdir, 'params.json')
    if os.path.exists(jp):
        params = json.load(open(jp))
    systems = list(TV_STYLES) + list(HANDHELD)
    for sysname in systems:
        if only and sysname not in only:
            continue
        entry = dict(fx=SYSTEM_FX[sysname])
        for layout in ('wide', 'square'):
            if sysname in TV_STYLES:
                p, g = draw_tv(sysname, layout)
            else:
                p, g = draw_handheld(sysname, layout)
            img = p.finish()
            fn = f'{sysname}_{layout}.png'
            img.save(os.path.join(outdir, fn), optimize=True)
            size = WIDE if layout == 'wide' else SQUARE
            entry[layout] = dict(file=fn, hole=norm(g['hole'], size), body=norm(g['body'], size),
                                 ar=round(size[0] / size[1], 5))
            print(sysname, layout, 'hole px', [round(v) for v in g['hole']], flush=True)
        if sysname in POCKET:   # portrait cover-screen art (Retro TV app only)
            p, g = draw_pocket(sysname)
            fn = f'{sysname}_tall.png'
            p.finish(edge_fade=4).save(os.path.join(outdir, fn), optimize=True)
            entry['tall'] = dict(file=fn, hole=norm(g['hole'], TALL), body=norm(g['body'], TALL),
                                 ar=round(TALL[0] / TALL[1], 5))
            if 'fx' in POCKET[sysname]:
                entry['tall']['fx'] = POCKET[sysname]['fx']
            print(sysname, 'tall', 'hole px', [round(v) for v in g['hole']], flush=True)
        params[sysname] = entry
    json.dump(params, open(jp, 'w'), indent=1)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:] or None)
