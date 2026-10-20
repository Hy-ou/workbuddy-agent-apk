from PIL import Image, ImageDraw
import os, math

base = os.path.dirname(os.path.abspath(__file__))
res = os.path.join(base, "app", "src", "main", "res")
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = (18, 21, 30, 255)
ACCENT = (91, 124, 250, 255)
OK = (61, 220, 151, 255)

def draw_icon(px):
    s = px * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(s * 0.22)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=BG)
    # W 折线：与矢量图同比例 (108 viewport)
    pts = [(30, 41), (41, 70), (54, 49), (67, 70), (78, 41)]
    pts = [(x / 108 * s, y / 108 * s) for x, y in pts]
    w = int(s * 7 / 108)
    d.line(pts, fill=ACCENT, width=w, joint="curve")
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x - w/2, y - w/2, x + w/2, y + w/2], fill=ACCENT)
    # 状态点
    cx, cy, rr = 54 / 108 * s, 25 / 108 * s, 3.4 / 108 * s
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=OK)
    return img.resize((px, px), Image.LANCZOS)

for dens, px in sizes.items():
    outdir = os.path.join(res, "mipmap-" + dens)
    os.makedirs(outdir, exist_ok=True)
    draw_icon(px).save(os.path.join(outdir, "ic_launcher.png"))
    # round icon（API 24-25 回退）
    s = px * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, s - 1, s - 1], fill=BG)
    pts = [(30, 41), (41, 70), (54, 49), (67, 70), (78, 41)]
    pts = [(x / 108 * s, y / 108 * s) for x, y in pts]
    w = int(s * 7 / 108)
    d.line(pts, fill=ACCENT, width=w, joint="curve")
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x - w/2, y - w/2, x + w/2, y + w/2], fill=ACCENT)
    cx, cy, rr = 54 / 108 * s, 25 / 108 * s, 3.4 / 108 * s
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=OK)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(outdir, "ic_launcher_round.png"))

# Play 商店用 512 图标
draw_icon(512).save(os.path.join(base, "icon-512.png"))
print("icons done")
