"""Draws the radar screen's sprite sheet with the stdlib only; the same input always gives the same bytes.

    python3 tools/gui_textures.py shared/resources/assets/oreradar

Writes textures/gui/radar.png under that assets directory: a 256 x 256 sheet in the vanilla-like grey
beveled container style of the station's menu (textures.py draws that one, and this script reuses its
panel and slot routines so the two screens match). The client's GuiSprites reads these regions:

    region            u, v      size     use
    panel             0, 0      32 x 32  nine-slice, border 4
    inset (light)     32, 0     16 x 16  nine-slice, border 1: the scrollbar's track
    display (dark)    48, 0     16 x 16  nine-slice, border 2: the preview and the charge bar
    slot              64, 0     18 x 18
    tracked slot      82, 0     18 x 18  cyan frame: the entry the radar tracks
    scroller          100, 0    12 x 15  and its disabled look at 112, 0
    charge fill       124, 0    6 x 64   full (cyan, top) to empty (red, bottom); stretched to the bar
    selected tab      0, 32     31 x 21  reaches 3 pixels into the panel's border to join it
    tab               32, 32    29 x 21  drawn under the panel's left edge
    icons             0..128, 64  16 x 16  all, favourites, recent, settings, outlines, arrow, beep,
                                         power, and a red slash for a toggle that is off
    star              144, 64   8 x 8    the favourite mark in a slot's corner

Every picture is original pixel art; nothing is copied from Minecraft.
"""
import math
import os
import sys

from textures import Canvas, gui_panel, gui_slot, hexc, shade, GUI_BG, GUI_HI, GUI_LO, GUI_EDGE, SLOT_IN, SLOT_DARK, SLOT_LIGHT

DISPLAY_FILL, DISPLAY_BEZEL = hexc('1F2428'), hexc('101315')
TAB_BG, TAB_HI, TAB_LO = hexc('A8A8A8'), hexc('DADADA'), hexc('4A4A4A')
CYAN, CYAN_HI, CYAN_TINT = hexc('3FCBEA'), hexc('B8F6FF'), hexc('7FA2AA')
INK = hexc('202326')


def paste(dst, src, x0, y0):
    for y in range(src.h):
        for x in range(src.w):
            c = src.get(x, y)
            if c[3]:
                dst.put(x0 + x, y0 + y, c)


def line(c, xa, ya, xb, yb, col, width=1.0, x0=0, y0=0):
    """A line of the given width between two points (cell coordinates), filled by distance to the segment."""
    dx, dy = xb - xa, yb - ya
    length2 = dx * dx + dy * dy or 1
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            t = max(0.0, min(1.0, ((px - xa) * dx + (py - ya) * dy) / length2))
            if math.hypot(px - (xa + t * dx), py - (ya + t * dy)) <= width / 2:
                c.put(x0 + x, y0 + y, col)


def polygon(points, x, y):
    """Point-in-polygon (even-odd) for the pixel centre x + 0.5, y + 0.5."""
    px, py = x + 0.5, y + 0.5
    inside = False
    for i in range(len(points)):
        (xa, ya), (xb, yb) = points[i], points[i - 1]
        if (ya > py) != (yb > py) and px < xa + (py - ya) * (xb - xa) / (yb - ya):
            inside = not inside
    return inside


def outlined(c, mask, fill, edge, x0, y0, shade_top=None):
    """Fills a mask (set of (x, y)) and rims it with edge where it meets transparency."""
    for (x, y) in mask:
        border = any((x + dx, y + dy) not in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        col = edge if border else fill
        if not border and shade_top is not None and (x - 1, y - 1) not in mask:
            col = shade_top
        c.put(x0 + x, y0 + y, col)


# ---------------------------------------------------------------- panels, slots, tabs

def panel(c):
    p = Canvas(32, 32)
    gui_panel(p, 32, 32)
    paste(c, p, 0, 0)


def inset(c, x0, y0):
    for y in range(16):
        for x in range(16):
            if (x, y) in ((15, 0), (0, 15)):
                col = SLOT_IN
            elif x == 0 or y == 0:
                col = SLOT_DARK
            elif x == 15 or y == 15:
                col = SLOT_LIGHT
            else:
                col = SLOT_IN
            c.put(x0 + x, y0 + y, col)


def display(c, x0, y0):
    inset(c, x0, y0)
    for y in range(1, 15):
        for x in range(1, 15):
            c.put(x0 + x, y0 + y, DISPLAY_BEZEL if x == 1 or y == 1 or x == 14 or y == 14 else DISPLAY_FILL)


def tracked_slot(c, x0, y0):
    gui_slot(c, x0, y0)
    for i in range(1, 17):
        for x, y in ((x0 + i, y0 + 1), (x0 + i, y0 + 16), (x0 + 1, y0 + i), (x0 + 16, y0 + i)):
            c.put(x, y, CYAN)
    c.rect(x0 + 2, y0 + 2, x0 + 15, y0 + 15, CYAN_TINT)
    for x, y in ((x0 + 1, y0 + 1), (x0 + 16, y0 + 1), (x0 + 1, y0 + 16), (x0 + 16, y0 + 16)):
        c.put(x, y, CYAN_HI)


def scroller(c, x0, y0, enabled):
    body = GUI_BG if enabled else hexc('9A9A9A')
    hi = GUI_HI if enabled else hexc('C8C8C8')
    lo = GUI_LO if enabled else hexc('6A6A6A')
    for y in range(15):
        for x in range(12):
            if x == 0 or y == 0:
                col = hi
            elif x == 11 or y == 14:
                col = lo
            else:
                col = body
            c.put(x0 + x, y0 + y, col)
    c.put(x0 + 11, y0, body)
    c.put(x0, y0 + 14, body)
    if enabled:
        for gy in (5, 7, 9):
            for x in range(3, 9):
                c.put(x0 + x, y0 + gy, lo)
                c.put(x0 + x, y0 + gy + 1, hi)


def charge_fill(c, x0, y0, w, h):
    """Cyan at the top (full) through green and yellow to red at the bottom: the item bar's colours."""
    for y in range(h):
        t = 1.0 - y / (h - 1)
        hue = t * 0.5
        sector = int(hue * 6)
        f = hue * 6 - sector
        up = int(round(255 * f))
        rgb = ((255, up, 0) if sector == 0 else (255 - up, 255, 0) if sector == 1 else (0, 255, up) if sector == 2
               else (0, 255, 255))
        for x in range(w):
            edge = 0.78 if x in (0, w - 1) else (1.1 if x == 1 else 1.0)
            stripe = 0.86 if y % 4 == 0 else 1.0
            c.put(x0 + x, y0 + y, shade(rgb + (255,), edge * stripe * 0.94))


def tab(c, x0, y0, w, h, bg, hi, lo):
    """A side tab with rounded left corners; open on the right, where it meets the panel."""
    for y in range(h):
        for x in range(w):
            if (x, y) in ((0, 0), (1, 0), (0, 1), (0, h - 1), (1, h - 1), (0, h - 2)):
                continue
            if x == 0 or y == 0 or y == h - 1 or (x, y) in ((1, 1), (1, h - 2)):
                col = GUI_EDGE
            elif y >= h - 3:
                col = lo
            elif x <= 2 or y <= 2:
                col = hi
            else:
                col = bg
            c.put(x0 + x, y0 + y, col)


# ---------------------------------------------------------------- icons (16 x 16)

def icon_all(c, x0, y0):
    """Four small ore blocks: everything the radar can find."""
    specks = [hexc('D8AF93'), hexc('F5D541'), hexc('5DECF5'), hexc('E23A2E')]
    stone, stone_hi, stone_lo = hexc('8E8E8E'), hexc('B4B4B4'), hexc('5E5E5E')
    for k, (bx, by) in enumerate(((1, 1), (9, 1), (1, 9), (9, 9))):
        for y in range(6):
            for x in range(6):
                if x == 0 or y == 0:
                    col = stone_hi
                elif x == 5 or y == 5:
                    col = stone_lo
                else:
                    col = stone
                c.put(x0 + bx + x, y0 + by + y, col)
        for sx, sy in ((1, 2), (3, 1), (2, 4), (4, 3)):
            c.put(x0 + bx + sx, y0 + by + sy, specks[k])
    for bx, by in ((1, 1), (9, 1), (1, 9), (9, 9)):
        for i in range(-1, 7):
            for x, y in ((bx + i, by - 1), (bx + i, by + 6), (bx - 1, by + i), (bx + 6, by + i)):
                if 0 <= x < 16 and 0 <= y < 16 and (x, y) not in ((bx - 1, by - 1), (bx + 6, by - 1), (bx - 1, by + 6), (bx + 6, by + 6)):
                    c.put(x0 + x, y0 + y, INK)


def star_points(cx, cy, outer, inner):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        r = outer if i % 2 == 0 else inner
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def icon_star(c, x0, y0, size=16):
    centre = size / 2
    pts = star_points(centre, centre + 0.6 * size / 16, size * 0.49, size * 0.21)
    mask = {(x, y) for y in range(size) for x in range(size) if polygon(pts, x, y)}
    outlined(c, mask, hexc('FFD84A'), hexc('8A5A00'), x0, y0, shade_top=hexc('FFF1A8'))


def icon_recent(c, x0, y0):
    """A clock with an arrow running back round its rim."""
    rim, face, hand = hexc('2E3236'), hexc('EEF2F4'), hexc('2E3236')
    cx = cy = 8.0
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= 5.2:
                c.put(x0 + x, y0 + y, face)
            elif d <= 6.6:
                ang = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
                # leave a gap at the upper left for the arrowhead
                if not (-2.6 < ang < -1.7):
                    c.put(x0 + x, y0 + y, hexc('3C7FD0') if d > 5.9 else rim)
    for k in range(4):
        c.put(x0 + 8, y0 + 4 + k, hand)
    for k in range(3):
        c.put(x0 + 8 + k, y0 + 8, hand)
    # arrowhead pointing anticlockwise, at the gap
    for x, y in ((2, 3), (3, 3), (4, 3), (5, 3), (3, 4), (4, 4), (5, 4), (4, 5), (5, 5), (5, 6), (5, 2)):
        c.put(x0 + x, y0 + y, hexc('3C7FD0'))


def icon_gear(c, x0, y0):
    body, edge, hi = hexc('9DA4AA'), hexc('2E3236'), hexc('D3D8DC')
    mask = set()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            tooth = (math.cos(a * 8) > 0.25)
            if d <= (7.2 if tooth else 5.4) and d >= 2.2:
                mask.add((x, y))
    outlined(c, mask, body, edge, x0, y0, shade_top=hi)


def icon_outlines(c, x0, y0):
    """A wireframe cube, as the outlines draw a vein."""
    front = [(2.5, 5.5), (10.5, 5.5), (10.5, 13.5), (2.5, 13.5)]
    back = [(5.5, 2.5), (13.5, 2.5), (13.5, 10.5), (5.5, 10.5)]
    for col, width in ((hexc('0E3440'), 2.3), (hexc('9DF2FF'), 1.0)):
        for i in range(4):
            line(c, *back[i], *back[(i + 1) % 4], col, width, x0, y0)
        for i in range(4):
            line(c, *front[i], *back[i], col, width, x0, y0)
        for i in range(4):
            line(c, *front[i], *front[(i + 1) % 4], col, width, x0, y0)


def icon_arrow(c, x0, y0):
    """The HUD's arrow, pointing up."""
    pts = [(8, 1.5), (14, 13.5), (8, 10), (2, 13.5)]
    mask = {(x, y) for y in range(16) for x in range(16) if polygon(pts, x, y)}
    outlined(c, mask, hexc('E8F2FF'), hexc('1D2A33'), x0, y0, shade_top=hexc('FFFFFF'))


def icon_beep(c, x0, y0):
    """A speaker with two sound waves."""
    body, edge = hexc('5E666D'), hexc('1D2125')
    pts = [(1.5, 5.5), (4.5, 5.5), (8.5, 2), (8.5, 14), (4.5, 10.5), (1.5, 10.5)]
    mask = {(x, y) for y in range(16) for x in range(16) if polygon(pts, x, y)}
    outlined(c, mask, body, edge, x0, y0, shade_top=hexc('9AA3AA'))
    wave = hexc('3FB86A')
    for r in (3.6, 6.2):
        for y in range(16):
            for x in range(16):
                d = math.hypot(x + 0.5 - 8.5, y + 0.5 - 8)
                if abs(d - r) <= 0.75 and x + 0.5 > 9.4 and abs(y + 0.5 - 8) < r * 0.9:
                    c.put(x0 + x, y0 + y, wave)


def icon_power(c, x0, y0):
    """The power symbol: a ring open at the top and a bar through the gap."""
    ring, edge = hexc('49C46E'), hexc('17331F')
    mask = set()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8.5
            d = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dx, -dy))
            if 4.0 <= d <= 6.6 and abs(ang) > 38:
                mask.add((x, y))
    for y in range(1, 9):
        for x in (7, 8):
            mask.add((x, y))
    outlined(c, mask, ring, edge, x0, y0, shade_top=hexc('9EF0B6'))


def icon_off(c, x0, y0):
    """A red slash for a toggle that is off; its dark rim keeps it readable on any icon."""
    line(c, 2.5, 2.5, 13.5, 13.5, hexc('3A0B08'), 3.4, x0, y0)
    line(c, 2.5, 2.5, 13.5, 13.5, hexc('FF4A3D'), 1.8, x0, y0)


# ---------------------------------------------------------------- the sheet

def sheet(path):
    c = Canvas(256, 256)
    panel(c)
    inset(c, 32, 0)
    display(c, 48, 0)
    gui_slot(c, 64, 0)
    tracked_slot(c, 82, 0)
    scroller(c, 100, 0, True)
    scroller(c, 112, 0, False)
    charge_fill(c, 124, 0, 6, 64)
    tab(c, 0, 32, 31, 21, GUI_BG, GUI_HI, GUI_LO)
    tab(c, 32, 32, 29, 21, TAB_BG, TAB_HI, TAB_LO)
    icon_all(c, 0, 64)
    icon_star(c, 16, 64)
    icon_recent(c, 32, 64)
    icon_gear(c, 48, 64)
    icon_outlines(c, 64, 64)
    icon_arrow(c, 80, 64)
    icon_beep(c, 96, 64)
    icon_power(c, 112, 64)
    icon_off(c, 128, 64)
    small = Canvas(8, 8)
    icon_star(small, 0, 0, 8)
    paste(c, small, 144, 64)
    c.save(path)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    path = os.path.join(sys.argv[1], 'textures', 'gui', 'radar.png')
    sheet(path)
    print('wrote', path)


if __name__ == '__main__':
    main()
