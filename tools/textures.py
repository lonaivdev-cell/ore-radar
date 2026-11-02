"""Draws Ore Radar's textures with the stdlib only; the same input always gives the same bytes.

    python3 tools/textures.py shared/resources/assets/oreradar

Writes, under that assets directory:
    textures/item/ore_radar.png              the radar, idle (1.0's design)
    textures/item/ore_radar_scanning.png     8-frame sweep (+ .mcmeta)
    textures/item/ore_radar_found.png        8-frame sweep with blips (+ .mcmeta)
    textures/block/charging_station_*.png    side, top, bottom, front, front_on (4-frame glow + .mcmeta)
    textures/gui/container/charging_station.png
                                             the station's menu, 176 x 166 on a 256 x 256 sheet, with the
                                             charge bar fill and the lit arrow as sprites (layout below)
    icon.png                                 the mod icon, 128 x 128

Every picture is original pixel art; nothing is copied from Minecraft.
"""
import math
import os
import struct
import sys
import zlib


def png(path, w, h, px):
    """Writes an RGBA PNG; px is a row-major list of (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in px[y * w:(y + 1) * w] for c in p) for y in range(h))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def mcmeta(path, frametime):
    with open(path + '.mcmeta', 'w') as f:
        f.write('{"animation": {"frametime": %d}}\n' % frametime)


def hexc(s, a=255):
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def shade(c, f):
    """c scaled by f (clamped), alpha kept."""
    return tuple(max(0, min(255, int(round(v * f)))) for v in c[:3]) + (c[3],)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def noise(x, y, seed):
    """Deterministic per-pixel value in [0, 1), for texture grain."""
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xffffffff
    n = ((n ^ (n >> 13)) * 1274126177) & 0xffffffff
    return ((n ^ (n >> 16)) & 0xffff) / 65536.0


class Canvas:
    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [fill] * (w * h)

    def put(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = c

    def get(self, x, y):
        return self.px[y * self.w + x]

    def rect(self, x0, y0, x1, y1, c):
        """Fills x0..x1, y0..y1 inclusive."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def save(self, path):
        png(path, self.w, self.h, self.px)


T = (0, 0, 0, 0)

# ---------------------------------------------------------------- radar (1.0's design, unchanged)

OUT, BODY, HI, LO = hexc('15181B'), hexc('353B41'), hexc('56606A'), hexc('262B30')
ANT, TIP_OFF, TIP_ON = hexc('A7B0B8'), hexc('5A5F64'), hexc('FF5A4E')
SCR, RING, GRID = hexc('0A1C12'), hexc('1F5234'), hexc('15382A')
SWEEP, TRAIL = hexc('7DFFB0'), hexc('2F9A60')
BLIP = hexc('E8F2FF')
CX, CY, R = 7.5, 8.5, 3.6


def radar_base(tip):
    c = Canvas(16, 16)
    for y in range(1, 4):
        c.put(12, y, ANT)
    c.put(12, 0, tip)
    # body with rounded corners and an outline
    for y in range(3, 16):
        for x in range(2, 14):
            if x in (2, 13) and y in (3, 15):
                continue
            edge = x in (2, 13) or y in (3, 15) or (x in (3, 12) and y in (3, 15))
            c.put(x, y, OUT if edge else BODY)
    for y in range(4, 15):
        c.put(3, y, HI)
    for x in range(4, 12):
        c.put(x, 4, HI)
    for y in range(5, 15):
        c.put(12, y, LO)
    # screen disc
    for y in range(4, 14):
        for x in range(3, 13):
            d = math.hypot(x + 0.5 - (CX + 0.5), y + 0.5 - (CY + 0.5))
            if d <= R + 0.6:
                c.put(x, y, RING if d > R - 0.4 else SCR)
    for x, y in ((7, 8), (8, 9), (7, 9), (8, 8)):
        c.put(x, y, GRID)
    # buttons
    c.put(5, 14, hexc('3FAF6A'))
    c.put(7, 14, hexc('D9A441'))
    c.put(10, 14, hexc('7C858D'))
    return c


def sweep(c, angle):
    for back, col in ((0.0, SWEEP), (0.45, TRAIL), (0.9, RING)):
        a = angle - back
        for i in range(1, 8):
            r = i * 0.5
            if r > R - 0.4:
                break
            c.put(int(math.floor(CX + 0.5 + math.cos(a) * r)), int(math.floor(CY + 0.5 + math.sin(a) * r)), col)


def strip(frames):
    px = []
    for f in frames:
        px += f.px
    return px


def radar_textures(textures):
    item = os.path.join(textures, 'item')
    idle = radar_base(TIP_OFF)
    idle.save(os.path.join(item, 'ore_radar.png'))
    scan, found = [], []
    for k in range(8):
        a = k * math.pi / 4 - math.pi / 2
        f = radar_base(TIP_ON if k % 4 < 2 else TIP_OFF)
        sweep(f, a)
        scan.append(f)
        g = radar_base(TIP_ON if k % 2 == 0 else TIP_OFF)
        sweep(g, a)
        if k % 2 == 0:
            g.put(9, 7, BLIP)
            g.put(6, 10, BLIP)
        else:
            g.put(9, 7, TRAIL)
        found.append(g)
    for name, frames in (('ore_radar_scanning', scan), ('ore_radar_found', found)):
        path = os.path.join(item, name + '.png')
        png(path, 16, 16 * len(frames), strip(frames))
        mcmeta(path, 2)
    return idle


# ---------------------------------------------------------------- charging station (block)

FRAME_DARK, FRAME = hexc('1C2024'), hexc('3A4148')
PLATE, PLATE_HI, PLATE_LO = hexc('5B646D'), hexc('7D8893'), hexc('454C54')
RIVET, RIVET_LO = hexc('A9B3BC'), hexc('2B3035')
COPPER, COPPER_HI, COPPER_LO = hexc('C46E3C'), hexc('E9965E'), hexc('8C4A27')
WINDOW_OFF, WINDOW_EDGE = hexc('0F1A20'), hexc('0A0F12')
BOLT_OFF = hexc('22323A')
GLOW = [hexc('2E8FA6'), hexc('48B9D1'), hexc('69DDF0'), hexc('8EF4FF')]
GLOW_CORE = hexc('E8FFFF')
LED_OFF, LED_RED, LED_GREEN = hexc('2A2224'), hexc('B8322B'), hexc('5BE37D')

# The bolt shown in the front window, as (x, y) cells inside the 16 x 16 face.
BOLT = [(8, 3), (9, 3), (7, 4), (8, 4), (7, 5), (6, 6), (7, 6), (8, 6), (9, 6), (8, 7), (7, 8), (8, 8), (7, 9)]


def station_plate(seed):
    """The steel face every side shares: a dark frame, a grained plate, light top-left edges."""
    c = Canvas(16, 16)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                col = FRAME_DARK
            elif x in (1, 14) or y in (1, 14):
                col = FRAME
            else:
                col = shade(PLATE, 0.94 + 0.12 * noise(x, y, seed))
            c.put(x, y, col)
    for i in range(2, 14):
        c.put(i, 2, PLATE_HI)
        c.put(2, i, PLATE_HI)
        c.put(i, 13, PLATE_LO)
        c.put(13, i, PLATE_LO)
    return c


def rivets(c, points):
    for x, y in points:
        c.put(x, y, RIVET)
        c.put(x + 1, y + 1, RIVET_LO)


def station_side():
    c = station_plate(1)
    # two copper coil bands across the plate
    for y0 in (5, 9):
        for x in range(3, 13):
            c.put(x, y0, COPPER_HI if x % 2 == 0 else COPPER)
            c.put(x, y0 + 1, COPPER_LO if x % 2 == 0 else COPPER)
    rivets(c, [(3, 3), (11, 3), (3, 11), (11, 11)])
    return c


def station_top():
    c = station_plate(2)
    # the radar dock: a dark recess with copper contacts at its ends
    c.rect(5, 4, 10, 11, WINDOW_EDGE)
    c.rect(6, 5, 9, 10, WINDOW_OFF)
    for x in (6, 9):
        c.put(x, 5, COPPER_HI)
        c.put(x, 10, COPPER)
    for y in range(5, 11):
        c.put(5, y, FRAME)
    rivets(c, [(3, 3), (11, 3), (3, 11), (11, 11)])
    return c


def station_bottom():
    c = station_plate(3)
    # a ventilation grille
    for y in range(5, 11, 2):
        for x in range(4, 12):
            c.put(x, y, FRAME_DARK)
    return c


def station_front(level):
    """The front face; level None is off, 0..3 the glow animation's brightness."""
    c = station_plate(4)
    # the window
    c.rect(3, 2, 12, 11, WINDOW_EDGE)
    lit = level is not None
    for y in range(3, 11):
        for x in range(4, 12):
            if lit:
                # brighter towards the bolt, with a slow scan line
                d = min(math.hypot(x - bx, y - by) for bx, by in BOLT)
                base = GLOW[max(0, level - 1)] if d > 1.5 else GLOW[level]
                col = shade(base, 0.55 + 0.45 * max(0.0, 1.0 - d / 4.0))
                if (y - 3) == (level * 2) % 8:
                    col = shade(col, 1.15)
            else:
                col = shade(WINDOW_OFF, 0.9 + 0.2 * noise(x, y, 5))
            c.put(x, y, col)
    for x, y in BOLT:
        c.put(x, y, GLOW_CORE if lit else BOLT_OFF)
    # status lights and a fuel hatch under the window
    for i, x in enumerate((5, 7, 9)):
        if not lit:
            col = LED_RED if i == 0 else LED_OFF
        else:
            col = LED_GREEN if i <= level % 3 or i == 0 else LED_OFF
        c.put(x, 13, col)
    for x in range(10, 13):
        c.put(x, 13, COPPER_LO)
    c.put(11, 12, COPPER)
    return c


def station_textures(textures):
    block = os.path.join(textures, 'block')
    station_side().save(os.path.join(block, 'charging_station_side.png'))
    station_top().save(os.path.join(block, 'charging_station_top.png'))
    station_bottom().save(os.path.join(block, 'charging_station_bottom.png'))
    station_front(None).save(os.path.join(block, 'charging_station_front.png'))
    frames = [station_front(k) for k in (1, 2, 3, 2)]
    path = os.path.join(block, 'charging_station_front_on.png')
    png(path, 16, 16 * len(frames), strip(frames))
    mcmeta(path, 4)


# ---------------------------------------------------------------- charging station (menu)
#
# Layout, in GUI pixels; the slot positions match ChargingStationMenu:
#   panel            0,0   176 x 166
#   fuel slot frame  43,34 18 x 18   (item at 44,35)
#   radar slot frame 115,34 18 x 18  (item at 116,35)
#   charge bar       frame 79,16 18 x 56; empty interior 80,17 16 x 54
#   arrows           fuel -> bar 63,39 13 x 7; bar -> radar 99,39 13 x 7
#   inventory        slots from 8,84 (3 rows) and the hotbar at 8,142
# Sprites on the same sheet:
#   bar fill         176,0  16 x 54  (blit its bottom part over the interior)
#   lit arrow        176,54 13 x 7   (over the bar -> radar arrow while charging)

GUI_BG, GUI_HI, GUI_LO, GUI_EDGE = hexc('C6C6C6'), hexc('FFFFFF'), hexc('555555'), hexc('000000')
SLOT_IN, SLOT_DARK, SLOT_LIGHT = hexc('8B8B8B'), hexc('373737'), hexc('FFFFFF')
GHOST = hexc('7A7A7A')
ARROW_OFF, ARROW_ON = hexc('8B8B8B'), hexc('5FE3FF')
BAR_BG = hexc('2B2B2B')


def gui_panel(c, w, h):
    """Vanilla-like beveled panel with rounded corners."""
    for y in range(h):
        for x in range(w):
            corner = (x in (0, w - 1) and y in (0, 1, h - 2, h - 1)) or (y in (0, h - 1) and x in (1, w - 2))
            if corner:
                continue
            if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                col = GUI_EDGE
            elif (x <= 2 or y <= 2) and not (x >= w - 3 or y >= h - 3):
                col = GUI_HI
            elif x >= w - 3 or y >= h - 3:
                col = GUI_LO if not (x <= 2 or y <= 2) else GUI_BG
            else:
                col = GUI_BG
            c.put(x, y, col)
    # inner corner pixels of the bevel
    c.put(1, 1, GUI_EDGE)
    c.put(w - 2, h - 2, GUI_EDGE)
    c.put(w - 3, h - 3, GUI_LO)
    c.put(2, 2, GUI_HI)


def gui_slot(c, x0, y0, ghost=None):
    """An 18 x 18 slot frame at x0, y0, optionally with a faint silhouette inside."""
    for i in range(18):
        c.put(x0 + i, y0, SLOT_DARK)
        c.put(x0, y0 + i, SLOT_DARK)
        c.put(x0 + i, y0 + 17, SLOT_LIGHT)
        c.put(x0 + 17, y0 + i, SLOT_LIGHT)
    c.rect(x0 + 1, y0 + 1, x0 + 16, y0 + 16, SLOT_IN)
    c.put(x0 + 17, y0, SLOT_IN)
    c.put(x0, y0 + 17, SLOT_IN)
    if ghost:
        for x, y in ghost:
            c.put(x0 + 1 + x, y0 + 1 + y, GHOST)


def arrow(c, x0, y0, col):
    """A 13 x 7 arrow pointing right."""
    for x in range(0, 9):
        for y in range(2, 5):
            c.put(x0 + x, y0 + y, col)
    for i in range(4):
        for y in range(i, 7 - i):
            c.put(x0 + 9 + i, y0 + y, col)


def radar_ghost():
    """The idle radar's outline, for the empty radar slot."""
    idle = radar_base(TIP_OFF)
    return [(x, y) for y in range(16) for x in range(16)
            if idle.get(x, y)[3] and any(not (0 <= x + dx < 16 and 0 <= y + dy < 16) or not idle.get(x + dx, y + dy)[3]
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]


def fuel_ghost():
    """A small heap of dust, for the empty fuel slot."""
    pts = []
    for y in range(9, 14):
        half = (y - 8) + 1
        for x in range(8 - half, 8 + half):
            if noise(x, y, 7) > 0.35:
                pts.append((x, y))
    return pts + [(4, 7), (11, 6), (9, 5)]


def gui_texture(textures):
    c = Canvas(256, 256)
    gui_panel(c, 176, 166)
    gui_slot(c, 43, 34, fuel_ghost())
    gui_slot(c, 115, 34, radar_ghost())
    # the charge bar, empty
    for i in range(18):
        c.put(79 + i, 16, SLOT_DARK)
        c.put(79 + i, 71, SLOT_LIGHT)
    for i in range(56):
        c.put(79, 16 + i, SLOT_DARK)
        c.put(96, 16 + i, SLOT_LIGHT)
    c.rect(80, 17, 95, 70, BAR_BG)
    for y in range(20, 70, 6):
        c.put(80, y, hexc('3A3A3A'))
        c.put(95, y, hexc('3A3A3A'))
    arrow(c, 63, 39, ARROW_OFF)
    arrow(c, 99, 39, ARROW_OFF)
    for row in range(3):
        for col in range(9):
            gui_slot(c, 7 + col * 18, 83 + row * 18)
    for col in range(9):
        gui_slot(c, 7 + col * 18, 141)
    # sprite: the bar fill, red at the bottom through yellow to cyan at the top (the item bar's colours)
    for y in range(54):
        t = 1.0 - y / 53.0
        h = t * 0.5
        sector = int(h * 6)
        f = h * 6 - sector
        up = int(round(255 * f))
        rgb = (255, up, 0) if sector == 0 else (255 - up, 255, 0) if sector == 1 else (0, 255, up) if sector == 2 else (0, 255, 255)
        for x in range(16):
            edge = 0.8 if x in (0, 15) else (1.0 if x not in (1, 2) else 1.12)
            stripe = 0.85 if (y + x // 2) % 6 == 0 else 1.0
            c.put(176 + x, y, shade(rgb + (255,), edge * stripe * 0.92))
    arrow(c, 176, 54, ARROW_ON)
    c.save(os.path.join(textures, 'gui', 'container', 'charging_station.png'))


# ---------------------------------------------------------------- mod icon

def icon(assets, idle):
    """The idle radar at 7x on a dark rounded tile, 128 x 128."""
    size, scale, off = 128, 7, 8
    c = Canvas(size, size)
    bg, edge = hexc('17232B'), hexc('2C4652')
    for y in range(size):
        for x in range(size):
            dx = max(0, abs(x - 63.5) - 52)
            dy = max(0, abs(y - 63.5) - 52)
            d = math.hypot(dx, dy)
            if d <= 11.5:
                c.put(x, y, edge if d > 9.5 else shade(bg, 0.9 + 0.2 * (1 - y / size)))
    for y in range(16):
        for x in range(16):
            col = idle.get(x, y)
            if col[3]:
                c.rect(off + x * scale, off + y * scale, off + x * scale + scale - 1, off + y * scale + scale - 1, col)
    c.save(os.path.join(assets, 'icon.png'))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    assets = sys.argv[1]
    textures = os.path.join(assets, 'textures')
    idle = radar_textures(textures)
    station_textures(textures)
    gui_texture(textures)
    icon(assets, idle)
    written = []
    for root, _, files in os.walk(assets):
        written += [os.path.relpath(os.path.join(root, f), assets) for f in files if f.endswith(('.png', '.mcmeta'))]
    print('wrote', len(written), 'files:', ' '.join(sorted(written)))


if __name__ == '__main__':
    main()
