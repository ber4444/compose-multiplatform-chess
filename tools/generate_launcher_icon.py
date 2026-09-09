#!/usr/bin/env python3
"""Render the AI Chess Coach 3D launcher and Play Store icons.

The icon is not a drawing of the app's 3D board — it is a ray trace of it. Everything that can be
taken from the shipped asset is taken from it rather than invented:

  * marble        the board tiles are cropped live out of chess.glb's own "black-case" baseColor
                  texture, so the icon's stone is literally the stone the game renders
  * king          proportions from chess.glb's king POSITION accessor bounds (3.898 x 1.807)
                  times PIECE_SCALE 0.5 = 1.949 squares tall, 0.904 wide
  * wood          the ramp is measured off the "white" material's texture; the grain itself is
                  procedural, because that texture is a UV atlas with no clean swatch to tile
  * blue          the coach's highlight quad, whose colour is its emissive factor (0, 0, 0.4)

The mesh is Draco-compressed, so the body is a Staunton profile revolved to the model's real
bounds rather than the model's own triangles. The cross on top is separate box geometry: revolving
a cross profile yields a disc.

Camera is the game's own rig (Math3D) pulled in close — pitch 37 deg, FOV 50 - so the board fills
the frame edge to edge. Wear is procedural: chipped tile edges, scuffed polish, grime in the
piece's crevices, and the rubbed-back finish on the rings a wooden king is actually handled by.

Outputs, regenerated together so the launcher and store icons cannot drift:

    androidApp/src/main/res/mipmap-*/ic_launcher_background.webp  the board, with the king's
                                                                  shadow and reflection baked in
    androidApp/src/main/res/mipmap-*/ic_launcher_foreground.webp  the king alone, with alpha
    androidApp/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon silhouette (vector)
    store/icon-512.png                                            Play listing icon

Requires numpy and pillow. Takes a few minutes.  python3 tools/generate_launcher_icon.py
"""
import json
import math
import os
import struct

import numpy as np
from PIL import Image

F32 = np.float32

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLB = os.path.join(ROOT, 'app', 'src', 'commonMain', 'composeResources',
                   'files', 'models', 'chess.glb')


def read_glb():
    """Return chess.glb's JSON chunk and its binary chunk."""
    with open(GLB, 'rb') as fh:
        data = fh.read()
    total = struct.unpack('<III', data[:12])[2]
    off, chunks = 12, []
    while off < total:
        clen, _ = struct.unpack('<II', data[off:off + 8])
        chunks.append(data[off + 8:off + 8 + clen])
        off += 8 + clen
    return json.loads(chunks[0].decode('utf-8')), chunks[1]


def highlight_tones():
    """The coach's highlight colours, taken from the asset rather than picked by eye.

    What makes those quads coloured is the material's emissiveFactor, not its baseColor (which is
    white for all four) - see ChessSetConventions.HIGHLIGHT_NODE_NAMES. The factors are normalised
    to unit peak here so the icon keeps the app's hues at the icon's own brightness.
    """
    gltf, _ = read_glb()
    out = {}
    for m in gltf['materials']:
        if m['name'].startswith('highlight'):
            e = np.array(m.get('emissiveFactor', [0, 0, 0]), F32)
            out[m['name']] = e / max(float(e.max()), 1e-6)
    return out


def marble_tiles():
    """Crop one light and one dark marble square out of chess.glb's board texture.

    The board's 64 squares all share material "black-case", whose baseColor texture is a 4x4 grid
    of marble tiles. Tile (0,0) is black marble and tile (1,0) is white, which is all the icon
    needs. Returned linearised, because shading here is linear and the texture is authored sRGB.
    """
    gltf, binary = read_glb()
    material = next(m for m in gltf['materials'] if m['name'] == 'black-case')
    texture = gltf['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']]
    view = gltf['bufferViews'][gltf['images'][texture['source']]['bufferView']]
    start = view.get('byteOffset', 0)
    import io
    sheet = Image.open(io.BytesIO(binary[start:start + view['byteLength']])).convert('RGB')
    tile = sheet.size[0] // 4
    out = []
    for box in ((tile, 0, 2 * tile, tile), (0, 0, tile, tile)):
        arr = np.asarray(sheet.crop(box).resize((256, 256)), dtype=np.float32) / 255.0
        out.append((arr ** 2.2).astype(np.float32))
    return out


PITCH, DIST, FOV = 37.0, 2.50, 50.0
PIECE_H = 1.949                    # squares: chess.glb's king bounds (3.898) x PIECE_SCALE 0.5
# The body is a surface of revolution; the cross on top is NOT (revolving a cross profile yields
# a disc), so it is separate box geometry. Base half-width 0.45 and the total height are the
# asset's own king bounds.
KING_PROFILE = [(0.45,0.00),(0.45,0.06),(0.41,0.10),(0.31,0.145),(0.235,0.19),(0.185,0.32),
                (0.150,0.60),(0.152,0.78),(0.195,0.90),(0.295,0.975),(0.315,1.015),
                (0.235,1.055),(0.215,1.115),(0.255,1.27),(0.325,1.44),(0.355,1.515),
                (0.370,1.565),(0.320,1.598),(0.215,1.628),(0.115,1.650),(0.0,1.662)]
CROSS_V = (0.070, 0.052, 1.600, 1.949)   # half-x, half-z, y0, y1
CROSS_H = (0.205, 0.052, 1.705, 1.800)
LIGHT = np.array([-0.42, 0.80, 0.43], F32); LIGHT /= np.linalg.norm(LIGHT)
# One cited square to the king's left, in the coach's neutral blue.
HIGHLIGHTS = [((-1, 0), 'highlight')]

# ---------------------------------------------------------------- helpers
def norm(v):
    return v / np.linalg.norm(v, axis=-1, keepdims=True)

def hash3(p):
    """cheap deterministic value noise source"""
    q = np.sin(p @ np.array([[127.1, 311.7, 74.7],
                             [269.5, 183.3, 246.1],
                             [113.5, 271.9, 124.6]], F32)) * 43758.5453
    return q - np.floor(q)

def vnoise(p):
    """3-D value noise, trilinear."""
    i = np.floor(p); f = p - i
    w = f * f * (3 - 2 * f)
    acc = 0.0
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                c = i + np.array([dx, dy, dz], F32)
                h = hash3(c)[..., 0]
                wx = w[..., 0] if dx else 1 - w[..., 0]
                wy = w[..., 1] if dy else 1 - w[..., 1]
                wz = w[..., 2] if dz else 1 - w[..., 2]
                acc = acc + h * wx * wy * wz
    return acc

def fbm(p, oct=4, lac=2.03, gain=0.5):
    a, s, tot = 1.0, 1.0, 0.0
    for _ in range(oct):
        tot = tot + a * vnoise(p * s)
        s *= lac; a *= gain
    return tot

# ---------------------------------------------------------------- king SDF
_prof = np.array([(0.0, 0.0)] + KING_PROFILE, F32)

def sdf_profile(r, y):
    """signed distance in the (r, y) half-plane to the revolved profile outline"""
    p = np.stack([r, y], -1)
    a = _prof[:-1]; b = _prof[1:]
    e = b - a                                    # (S,2)
    w = p[..., None, :] - a                      # (...,S,2)
    t = np.clip((w * e).sum(-1) / (e * e).sum(-1), 0.0, 1.0)
    d = w - t[..., None] * e
    dist = np.sqrt((d * d).sum(-1)).min(-1)
    # winding sign
    cond1 = p[..., None, 1] >= a[:, 1]
    cond2 = p[..., None, 1] < b[:, 1]
    cond3 = (e[:, 0] * w[..., 1] - e[:, 1] * w[..., 0]) > 0
    allc = cond1 & cond2 & cond3
    nonc = (~cond1) & (~cond2) & (~cond3)
    flips = (allc | nonc).sum(-1)
    return np.where(flips % 2 == 1, -dist, dist)

WEAR = 1.0
def sdf_box(p, hx, hz, y0, y1, round_r=0.018):
    cy = (y0 + y1) / 2; hy = (y1 - y0) / 2 - round_r
    q = np.stack([np.abs(p[..., 0]) - (hx - round_r),
                  np.abs(p[..., 1] - cy) - hy,
                  np.abs(p[..., 2]) - (hz - round_r)], -1)
    outside = np.sqrt((np.maximum(q, 0.0) ** 2).sum(-1))
    inside = np.minimum(q.max(-1), 0.0)
    return outside + inside - round_r

def sdf(p):
    r = np.sqrt(p[..., 0] ** 2 + p[..., 2] ** 2)
    d = sdf_profile(r, p[..., 1])
    d = np.minimum(d, sdf_box(p, *CROSS_V))
    d = np.minimum(d, sdf_box(p, *CROSS_H))
    if WEAR:
        # knocks and chips: low-frequency dents, deepest on the rings that a piece actually
        # gets knocked on - the crown rim, the collar, and the foot.
        dent = fbm(p * 5.5, 2) - 0.5
        edge = (np.clip(1.0 - np.abs(p[..., 1] - 1.565) * 6.0, 0, 1)
                + np.clip(1.0 - np.abs(p[..., 1] - 0.05) * 9.0, 0, 1)
                + np.clip(1.0 - np.abs(p[..., 1] - 1.015) * 8.0, 0, 1))
        d = d + dent * (0.006 + 0.030 * np.clip(edge, 0, 1))
    return d

def sdf_normal(p, h=2e-3):
    e = np.array([[h,0,0],[0,h,0],[0,0,h]], F32)
    n = np.stack([sdf(p + e[i]) - sdf(p - e[i]) for i in range(3)], -1)
    return norm(n)

# ---------------------------------------------------------------- textures
MARBLE_L, MARBLE_D = marble_tiles()

def sample(tex, u, v):
    h, w, _ = tex.shape
    x = np.clip((u % 1.0) * (w - 1), 0, w - 1)
    y = np.clip((v % 1.0) * (h - 1), 0, h - 1)
    x0 = x.astype(np.int32); y0 = y.astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    return ((tex[y0, x0] * (1 - fx) + tex[y0, x1] * fx) * (1 - fy) +
            (tex[y1, x0] * (1 - fx) + tex[y1, x1] * fx) * fy)

# ---------------------------------------------------------------- camera & rays
def camera(size, pitch=PITCH, dist=DIST, fov=FOV, target=np.array([0, 0.92, 0], F32)):
    eye = target + np.array([0.0,
                             dist * math.sin(math.radians(pitch)),
                             dist * math.cos(math.radians(pitch))], F32)
    fwd = norm(target - eye)
    right = norm(np.cross(fwd, np.array([0, 1, 0], F32)))
    up = np.cross(right, fwd)
    row, col = np.meshgrid(np.arange(size, dtype=F32), np.arange(size, dtype=F32), indexing='ij')
    sx = (col + 0.5) / size * 2 - 1
    sy = 1 - (row + 0.5) / size * 2
    k = math.tan(math.radians(fov) / 2)
    d = norm(fwd + right * (sx * k)[..., None] + up * (sy * k)[..., None])
    return eye, d

def cylinder_range(o, d, radius=0.52, ytop=2.02):
    """entry/exit t of the king's bounding cylinder; nan where the ray misses"""
    a = d[..., 0] ** 2 + d[..., 2] ** 2
    b = 2 * (o[0] * d[..., 0] + o[2] * d[..., 2])
    c = o[0] ** 2 + o[2] ** 2 - radius ** 2
    disc = b * b - 4 * a * c
    hit = disc > 0
    sq = np.sqrt(np.maximum(disc, 0))
    t0 = (-b - sq) / (2 * a); t1 = (-b + sq) / (2 * a)
    with np.errstate(divide='ignore', invalid='ignore'):
        ty0 = (0.0 - o[1]) / d[..., 1]
        ty1 = (ytop - o[1]) / d[..., 1]
    tlo = np.minimum(ty0, ty1); thi = np.maximum(ty0, ty1)
    t0 = np.maximum(t0, np.maximum(tlo, 0.0)); t1 = np.minimum(t1, thi)
    return hit & (t1 > t0), t0, t1

def march(o, d, t0, t1, mask, steps=64):
    t = t0.copy()
    hit = np.zeros(mask.shape, bool)
    idx = np.where(mask)
    if len(idx[0]) == 0:
        return hit, t
    tt = t[idx]; dd = d[idx]; alive = np.ones(len(tt), bool)
    for _ in range(steps):
        p = o + dd[alive] * tt[alive][..., None]
        dist = sdf(p)
        tt_a = tt[alive] + np.maximum(dist, 1e-4) * 0.92
        done = (dist < 6e-4)
        out = tt_a > t1[idx][alive]
        cur = np.where(alive)[0]
        tt[cur] = tt_a
        h = np.zeros(len(tt), bool); h[cur[done]] = True
        hit[idx[0][h], idx[1][h]] = True
        keep = ~(done | out)
        newalive = np.zeros(len(tt), bool); newalive[cur[keep]] = True
        alive = newalive
        if not alive.any():
            break
    t[idx] = tt
    return hit, t

# ---------------------------------------------------------------- environment & materials
def srgb(*c):
    return np.array(c, F32) ** 2.2

ZENITH  = srgb(0.93, 0.92, 0.90)
HORIZON = srgb(0.60, 0.58, 0.55)
GROUND  = srgb(0.17, 0.16, 0.15)

def env(dirs):
    y = dirs[..., 1:2]
    up = np.clip(y, 0, 1) ** 0.6
    dn = np.clip(-y, 0, 1) ** 0.5
    return HORIZON + (ZENITH - HORIZON) * up - (HORIZON - GROUND) * dn

WOOD_DARK = srgb(0.34, 0.235, 0.125)   # grain lines
WOOD_MID  = srgb(0.60, 0.435, 0.235)   # the asset's "white" piece wood
WOOD_LITE = srgb(0.87, 0.745, 0.545)   # rubbed bare where hands hold it

def wood_albedo(p, wear):
    """Turned wood: growth rings run across the lathe axis, so the grain bands horizontally
    while the tool marks streak vertically."""
    theta = np.arctan2(p[..., 2], p[..., 0])
    rad = np.sqrt(p[..., 0] ** 2 + p[..., 2] ** 2)
    # the blank's growth rings are cylinders about the lathe axis: they band along the height on
    # a turned flank and show as concentric rings on every horizontal cut face.
    rings = fbm(np.stack([theta * 0.9, p[..., 1] * 5.5, rad * 7.5], -1), 4)
    rings = np.clip((rings - 0.40) * 2.6, 0, 1)
    streak = fbm(np.stack([theta * 26.0, p[..., 1] * 2.2, rad * 3.0 + 2.0], -1), 2)
    streak = np.clip((streak - 0.46) * 1.8, 0, 1)
    t = np.clip(rings * 0.80 + streak * 0.20, 0, 1)[..., None]
    base = WOOD_DARK + (WOOD_MID - WOOD_DARK) * t
    # Handling rubs the finish back toward bare wood. This has to brighten the grain rather than
    # lerp toward a flat colour: lerping compresses the grain contrast to nothing and the rubbed
    # rings end up reading as moulded plastic.
    w = np.clip(wear, 0, 0.62)[..., None]
    return base * (1.0 + w * 1.35) + (WOOD_LITE - WOOD_MID) * w * 0.35

def ao(p, n, radius=(0.03, 0.07, 0.14, 0.24)):
    occ = 0.0
    for i, r in enumerate(radius):
        d = sdf(p + n * r)
        occ = occ + (r - d) * (0.75 ** i)
    return np.clip(1.0 - occ * 1.5, 0.0, 1.0)

def soft_shadow(o, ldir, kmin=0.02, kmax=3.5, sharp=14.0):
    res = np.ones(o.shape[:-1], F32)
    t = np.full(o.shape[:-1], kmin, F32)
    for _ in range(26):
        h = sdf(o + ldir * t[..., None])
        res = np.minimum(res, np.clip(sharp * h / np.maximum(t, 1e-3), 0, 1))
        t = t + np.clip(h, 0.01, 0.20)
        if (t > kmax).all():
            break
    return np.clip(res, 0, 1)

def shade_king(p, n, view):
    # bump: fine scratches and tool marks
    h = 0.004
    e = np.array([[h,0,0],[0,h,0],[0,0,h]], F32)
    g = np.stack([fbm(p * 60 + e[i], 2) - fbm(p * 60 - e[i], 2) for i in range(3)], -1)
    n = norm(n + g * 0.55)

    up = np.clip(n[..., 1], 0, 1)
    occl = ao(p, n)
    # wear: where a piece is handled - the crown, the collar, the widest rings, and anywhere
    # that faces up and is not shielded. Crevices stay dark and hold grime.
    rings = (np.clip(1 - np.abs(p[..., 1] - 1.565) * 5.0, 0, 1) * 0.9   # crown rim
             + np.clip(1 - np.abs(p[..., 1] - 1.015) * 6.0, 0, 1) * 0.7  # collar
             + np.clip(1 - np.abs(p[..., 1] - 1.870) * 5.0, 0, 1) * 0.8  # the cross's arms
             + np.clip(1 - np.abs(p[..., 1] - 0.070) * 7.0, 0, 1) * 0.6)  # foot
    patchy = np.clip((fbm(p * 3.1, 3) - 0.38) * 3.6, 0, 1)
    wear = np.clip(rings * patchy * 1.05 + up * 0.30 * patchy, 0, 1) * occl

    scratch = np.clip((fbm(np.stack([np.arctan2(p[..., 2], p[..., 0]) * 30.0,
                                     p[..., 1] * 110.0,
                                     np.full_like(p[..., 1], 5.0)], -1), 2) - 0.62) * 7.0, 0, 1)
    alb = wood_albedo(p, np.clip(wear + scratch * 0.22, 0, 1))
    grime = np.clip((1 - occl) * 1.5, 0, 1)[..., None]
    alb = alb * (1 - grime * 0.72)

    ndl = np.clip((n * LIGHT).sum(-1), 0, 1)
    shadow = soft_shadow(p + n * 0.012, LIGHT)
    hv = norm(LIGHT - view)
    rough = 0.54 - wear * 0.36
    spec_p = np.clip(2.0 / np.maximum(rough, 0.05) ** 2, 4, 900)
    spec = np.clip((n * hv).sum(-1), 0, 1) ** spec_p * (0.18 + wear * 0.40)
    fres = 0.040 + 0.45 * (1 - np.clip(-(n * view).sum(-1), 0, 1)) ** 5

    amb = env(n) * (0.34 * occl[..., None])
    refl = env(norm(view - 2 * (view * n).sum(-1)[..., None] * n))
    col = (alb * (ndl * shadow)[..., None] * 1.12
           + alb * amb
           + refl * (fres * occl)[..., None] * 0.42
           + (spec * shadow)[..., None] * srgb(1.0, 0.96, 0.90))
    return col

# ---------------------------------------------------------------- board
TONES = highlight_tones()

def shade_board(p, view, t, pixel_angle, king_colour_fn):
    u = p[..., 0] + 0.5; v = p[..., 2] + 0.5      # origin = centre of the king's square
    su = np.floor(u); sv = np.floor(v)
    fu = u - su; fv = v - sv
    # the king stands on a dark square: a light wooden piece needs the separation, and polished
    # black marble is where the reflection actually reads
    light_sq = ((su.astype(np.int64) + sv.astype(np.int64)) % 2) != 0

    # vary the tile per square, or an 8x8 board reads as wallpaper
    var = (np.sin(su * 12.9898 + sv * 78.233) * 43758.5453) % 4
    fu_v = np.where(var >= 2, 1 - fu, fu)
    fv_v = np.where((var % 2) >= 1, 1 - fv, fv)
    swap = var >= 3
    a = np.where(swap, fv_v, fu_v); b = np.where(swap, fu_v, fv_v)
    tex_l = sample(MARBLE_L, a, b)
    tex_d = sample(MARBLE_D, a, b)
    alb = np.where(light_sq[..., None], tex_l, tex_d)
    # distance filtering: fade to each tile's mean once a pixel spans much of a square
    fp = np.clip(t * pixel_angle / np.maximum(np.abs(view[..., 1]), 0.08), 0, 4)
    blend = np.clip((fp - 0.25) / 0.85, 0, 1)[..., None]
    mean = np.where(light_sq[..., None], MARBLE_L.mean((0, 1)), MARBLE_D.mean((0, 1)))
    alb = alb * (1 - blend) + mean * blend

    # --- wear ---
    edge = np.minimum(np.minimum(fu, 1 - fu), np.minimum(fv, 1 - fv))
    seam = np.clip(1 - edge / 0.018, 0, 1)                       # grout line
    chip = np.clip(1 - edge / 0.075, 0, 1) * np.clip((fbm(np.stack([u * 9, v * 9, np.zeros_like(u)], -1), 3) - 0.40) * 5.0, 0, 1)
    scuff = np.clip((fbm(np.stack([u * 2.6, v * 2.6, np.full_like(u, 3.1)], -1), 4) - 0.44) * 3.4, 0, 1)
    scratch = np.clip((fbm(np.stack([u * 18 + v * 4, v * 130, np.full_like(u, 7.7)], -1), 2) - 0.56) * 7.0, 0, 1)
    stain = np.clip((fbm(np.stack([u * 1.5, v * 1.5, np.full_like(u, 11.0)], -1), 3) - 0.40) * 1.8, 0, 1)

    worn = np.clip(chip * 1.0 + scuff * 0.55, 0, 1)[..., None]
    exposed = np.clip(chip, 0, 1)[..., None]
    alb = alb + (srgb(0.78, 0.76, 0.72) - alb) * exposed * np.where(light_sq[..., None], 0.40, 0.34)
    alb = alb * (1 - (seam * 0.75)[..., None])
    alb = alb * (1 - (stain * 0.26)[..., None])
    alb = alb + (scratch * 0.16)[..., None]

    # --- normal: grout dips, scratches raise micro-relief ---
    n = np.zeros(p.shape, F32); n[..., 1] = 1.0
    hgt = lambda a, b: (np.clip(1 - np.minimum(np.minimum(a, 1 - a), np.minimum(b, 1 - b)) / 0.018, 0, 1))
    dx = (hgt(np.clip(fu + 0.004, 0, 1), fv) - hgt(np.clip(fu - 0.004, 0, 1), fv))
    dz = (hgt(fu, np.clip(fv + 0.004, 0, 1)) - hgt(fu, np.clip(fv - 0.004, 0, 1)))
    n = norm(n + np.stack([dx * 0.9, np.zeros_like(dx), dz * 0.9], -1)
             + np.stack([scratch * 0.10, np.zeros_like(dx), scratch * -0.08], -1))

    ndl = np.clip((n * LIGHT).sum(-1), 0, 1)
    shadow = soft_shadow(p + n * 0.015, LIGHT, sharp=9.0)

    # --- polished marble: mirror the king, blurred by wear ---
    r = norm(view - 2 * (view * n).sum(-1)[..., None] * n)
    refl = env(r)
    rp = p + n * 0.01
    hitm, rt0, rt1 = cylinder_range_at(rp, r)
    rhit, rt = march_from(rp, r, hitm, rt0, rt1)
    if rhit.any():
        q = rp[rhit] + r[rhit] * rt[rhit][..., None]
        kn = sdf_normal(q)
        kc = king_colour_fn(q, kn, r[rhit])
        # a rough surface blurs what it mirrors, and the blur grows with distance
        soft = np.clip(rt[rhit] / 2.2, 0, 1)[..., None]
        refl[rhit] = kc * (1 - soft * 0.55) + refl[rhit] * (soft * 0.55)
    gloss = np.clip(0.72 - worn[..., 0] * 0.55 - blend[..., 0] * 0.34, 0.04, 0.72)
    fres = 0.19 + 0.58 * (1 - np.clip(-(n * view).sum(-1), 0, 1)) ** 4
    mirror = (fres * gloss)[..., None]
    # the reflected king fades with distance, as a rough surface's blur does


    hv = norm(LIGHT - view)
    spec_p = np.clip(700 * (gloss + 0.15), 20, 900)
    spec = np.clip((n * hv).sum(-1), 0, 1) ** spec_p * (gloss * 1.5)

    col = (alb * (0.17 + 0.98 * ndl * shadow)[..., None]
           + refl * mirror
           + (spec * shadow)[..., None] * srgb(1.0, 0.97, 0.92))

    # the coach's highlighted square: an emissive, transmissive quad
    for (hx, hz), tone in HIGHLIGHTS:
        rgb = TONES[tone]
        on_hl = (su.astype(np.int64) == hx) & (sv.astype(np.int64) == hz)
        if on_hl.any():
            inner = np.clip((edge - 0.02) / 0.05, 0, 1)
            glow = (0.72 + 0.58 * inner)[..., None]
            # KHR_materials_transmission, not alpha blending: the marble reads through the quad
            tinted = col * 0.42 + rgb * glow * 1.55 + col * rgb * 0.8
            col = np.where(on_hl[..., None], tinted, col)
        prox = np.clip(1 - (np.abs(u - (hx + .5)) + np.abs(v - (hz + .5))) / 1.6, 0, 1)
        col = col + rgb * (prox ** 2.5 * 0.22)[..., None]
    return col

def cylinder_range_at(o, d, radius=0.52, ytop=2.02):
    """cylinder_range with a per-point ray origin"""
    a = d[..., 0] ** 2 + d[..., 2] ** 2
    b = 2 * (o[..., 0] * d[..., 0] + o[..., 2] * d[..., 2])
    c = o[..., 0] ** 2 + o[..., 2] ** 2 - radius ** 2
    disc = b * b - 4 * a * c
    sq = np.sqrt(np.maximum(disc, 0))
    with np.errstate(divide="ignore", invalid="ignore"):
        t0 = (-b - sq) / (2 * a); t1 = (-b + sq) / (2 * a)
        ty0 = (0.0 - o[..., 1]) / d[..., 1]; ty1 = (ytop - o[..., 1]) / d[..., 1]
    tlo = np.minimum(ty0, ty1); thi = np.maximum(ty0, ty1)
    t0 = np.maximum(np.nan_to_num(t0, nan=1e9), np.maximum(np.nan_to_num(tlo, nan=0.0), 0.01))
    t1 = np.minimum(np.nan_to_num(t1, nan=-1e9), np.nan_to_num(thi, nan=-1e9))
    return (disc > 0) & (t1 > t0), t0.astype(F32), t1.astype(F32)

def march_from(o, d, mask, t0, t1, steps=44):
    """sphere-trace from per-point origins; works on any leading shape"""
    flat_hit = np.zeros(mask.size, bool)
    flat_t = np.zeros(mask.size, F32)
    sel = np.flatnonzero(mask.reshape(-1))
    if sel.size == 0:
        return flat_hit.reshape(mask.shape), flat_t.reshape(mask.shape)
    oo = o.reshape(-1, 3)[sel]; dd = d.reshape(-1, 3)[sel]
    tt = t0.reshape(-1)[sel].copy(); tmax = t1.reshape(-1)[sel]
    done = np.zeros(len(tt), bool); alive = np.ones(len(tt), bool)
    for _ in range(steps):
        cur = np.flatnonzero(alive)
        dist = sdf(oo[cur] + dd[cur] * tt[cur][..., None])
        tt[cur] += np.maximum(dist, 1e-4) * 0.9
        h = dist < 8e-4
        done[cur[h]] = True
        alive[cur[h | (tt[cur] > tmax[cur])]] = False
        if not alive.any():
            break
    flat_hit[sel] = done; flat_t[sel] = tt
    return flat_hit.reshape(mask.shape), flat_t.reshape(mask.shape)

# ---------------------------------------------------------------- frame
def tonemap(c):
    c = np.clip(c, 0, None)
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14)   # ACES-ish
    return np.clip(c, 0, 1) ** (1 / 2.2)

def render(size=512, ss=2, pitch=PITCH, dist=DIST, fov=FOV, mode='full'):
    n = size * ss
    eye, d = camera(n, pitch=pitch, dist=dist, fov=fov)
    pixel_angle = 2 * math.tan(math.radians(fov) / 2) / n

    kmask, t0, t1 = cylinder_range(eye, d)
    khit, kt = march(eye, d, t0, t1, kmask)
    if mode == 'board':
        khit = np.zeros_like(khit)

    with np.errstate(divide='ignore', invalid='ignore'):
        bt = np.where(d[..., 1] < -1e-6, -eye[1] / d[..., 1], np.inf)
    bhit = np.isfinite(bt) & (bt > 0)
    board_first = bhit & (~khit | (bt < kt))

    col = np.tile(env(d), 1)
    if board_first.any():
        p = eye + d * bt[..., None]
        idx = np.where(board_first)
        sub = shade_board(p[idx], d[idx], bt[idx], pixel_angle, shade_king)
        col[idx] = sub
        # atmospheric falloff so the far field settles instead of shimmering
        fade = np.clip((bt[idx] - 6.0) / 26.0, 0, 1)[..., None]
        col[idx] = col[idx] * (1 - fade) + env(d[idx]) * 0.85 * fade
    if khit.any():
        idx = np.where(khit)
        p = eye + d * kt[..., None]
        col[idx] = shade_king(p[idx], sdf_normal(p[idx]), d[idx])

    img = tonemap(col)
    # a touch of grade: gentle contrast and a vignette, so the icon has a focal point
    yy, xx = np.mgrid[0:n, 0:n].astype(F32) / n * 2 - 1
    vig = 1.0 - 0.20 * np.clip((xx ** 2 + yy ** 2) * 0.75, 0, 1) ** 1.4
    img = np.clip((img - 0.5) * 1.06 + 0.5, 0, 1) * vig[..., None]

    rgba = np.concatenate([img, np.ones(img.shape[:2] + (1,), F32)], -1)
    if mode == 'king':
        rgba[..., 3] = khit.astype(F32)
    out = Image.fromarray((rgba * 255).astype(np.uint8))
    return out.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- themed-icon silhouette
def monochrome_xml():
    """The themed-icon layer, as a vector. Android tints this by alpha, so it is the king's
    outline alone — the board would flatten into one solid blob and read as nothing."""
    top = [(CROSS_V[0], 1.600), (CROSS_V[0], CROSS_H[2]), (CROSS_H[0], CROSS_H[2]),
           (CROSS_H[0], CROSS_H[3]), (CROSS_V[0], CROSS_H[3]), (CROSS_V[0], PIECE_H)]
    half = [(r, y) for r, y in KING_PROFILE if y <= 1.600] + top
    # place it inside the 66dp safe circle of a 108dp layer
    height, base_y, cx = 60.0, 84.0, 54.0
    scale = height / PIECE_H
    right = [(cx + r * scale, base_y - y * scale) for r, y in half]
    left = [(cx - r * scale, base_y - y * scale) for r, y in reversed(half)]
    pts = right + left
    d = ('M%.2f,%.2f ' % pts[0]) + ' '.join('L%.2f,%.2f' % p for p in pts[1:]) + 'Z'
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!--\n'
            '  GENERATED by tools/generate_launcher_icon.py.\n'
            '\n'
            '  Themed icons are tinted by alpha, so this layer is the king silhouette only.\n'
            '  Sized to the 66dp safe circle of the 108dp layer.\n'
            '-->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="#FF000000" android:pathData="%s"/>\n'
            '</vector>\n' % d)


# ---------------------------------------------------------------- outputs
# A launcher shows only the middle 72dp of a 108dp adaptive layer, so the layers are rendered
# with a proportionally wider field of view: the central 72/108 then frames exactly the shot the
# store icon uses, and the extra board around it is what the mask and the parallax eat into.
LAYER_FOV = 2 * math.degrees(math.atan(math.tan(math.radians(FOV) / 2) * 108.0 / 72.0))
DENSITIES = [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]


def main():
    res = os.path.join(ROOT, 'androidApp', 'src', 'main', 'res')

    mono = os.path.join(res, 'drawable', 'ic_launcher_monochrome.xml')
    with open(mono, 'w') as fh:
        fh.write(monochrome_xml())
    print('wrote androidApp/src/main/res/drawable/ic_launcher_monochrome.xml')

    for mode, name in (('board', 'ic_launcher_background'), ('king', 'ic_launcher_foreground')):
        master = render(DENSITIES[-1][1], ss=2, fov=LAYER_FOV, mode=mode)
        for bucket, px in DENSITIES:
            folder = os.path.join(res, 'mipmap-' + bucket)
            os.makedirs(folder, exist_ok=True)
            img = master if px == master.size[0] else master.resize((px, px), Image.LANCZOS)
            # WebP, matching the launcher rasters this project already shipped: a photographic
            # icon as PNG costs ~4x for no visible gain at these sizes.
            if mode == 'board':
                img = img.convert('RGB')
            img.save(os.path.join(folder, name + '.webp'), quality=92, method=6)
        print('wrote androidApp/src/main/res/mipmap-*/%s.webp' % name)

    store = os.path.join(ROOT, 'store')
    os.makedirs(store, exist_ok=True)
    # Play asks for a 32-bit PNG, so keep the (fully opaque) alpha channel
    render(512, ss=2).convert('RGBA').save(os.path.join(store, 'icon-512.png'), optimize=True)
    print('wrote store/icon-512.png')


if __name__ == '__main__':
    main()
