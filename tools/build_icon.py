"""Soundtrail's icon geometry, kept in one place.

The adaptive icon — what every API 26+ launcher actually draws — used to come from two
hand-written vectors whose music-note path data was malformed: a cubic `c` carrying 22 numbers,
where Android's PathParser reads whole groups of six. A path Android cannot parse is a drawable a
launcher cannot draw, and the fallback is the system's placeholder icon.

Here the mark is generated instead: circles, rectangles and bezier control points produce the
path data, the very same path data is rasterised into the mipmap PNGs, and every path string is
checked for well-formed command groups before anything is written. The proportions come from the
512px artwork that shipped in `mipmap-nodpi` (head circle 53.8dp wide, tip 1.5 radii below the
head's centre), refitted to the adaptive-icon safe circle of 66dp.

Run from the repository root:

    python3 tools/build_icon.py
"""
import math
import pathlib
import re
import xml.etree.ElementTree as ET

# ----- palette (mirrors ui/Theme.kt) ---------------------------------------------------
BACKGROUND = "#101114"
LIME = "#D9F67A"
CORAL = "#FF645E"

# ----- canvas: 108dp, the adaptive-icon standard ---------------------------------------
SIZE = 108.0
CX = 54.0
HEAD_CY = 47.5
HEAD_R = 25.5
TIP_Y = 85.5

STEM_X = 55.4
STEM_W = 3.2
STEM_TOP = 33.5
NOTE_HEAD_Y = 53.5
NOTE_HEAD_X = 51.0
NOTE_HEAD_R = 6.2
CORAL_R = 2.6
FLAG = ((55.4, 33.5), (63.5, 35.6), (65.5, 41.0), (59.0, 46.0))

# Two strokes for the sound ripples. The gap between them is what keeps them legible at
# launcher size: any closer and they merge into one smudge on a 40dp icon.
WAVES = (
    (((43.0, 66.0), (54.0, 69.6), (65.0, 66.0)), 4.0),
    (((48.5, 75.4), (54.0, 77.4), (59.5, 75.4)), 3.4),
)


# ----- geometry ------------------------------------------------------------------------

def tangent_points():
    """Where the two straight sides leave the head circle on their way to the tip."""
    dx, dy = 0.0, TIP_Y - HEAD_CY
    d = math.hypot(dx, dy)
    half = math.asin(HEAD_R / d)
    base = math.atan2(dy, dx)
    points = [(CX + HEAD_R * math.cos(base - half), HEAD_CY + HEAD_R * math.sin(base - half)),
              (CX + HEAD_R * math.cos(base + half), HEAD_CY + HEAD_R * math.sin(base + half))]
    points.sort()
    return points


P_LEFT, P_RIGHT = tangent_points()
TIP = (CX, TIP_Y)


def _inside_convex(x, y, points, inset=0.0):
    """Convex-polygon test; `inset` shrinks the polygon by that many units."""
    count = len(points)
    centre_x = sum(p[0] for p in points) / count
    centre_y = sum(p[1] for p in points) / count
    for index in range(count):
        ax, ay = points[index]
        bx, by = points[(index + 1) % count]
        ex, ey = bx - ax, by - ay
        length = math.hypot(ex, ey)
        nx, ny = -ey / length, ex / length
        if (centre_x - ax) * nx + (centre_y - ay) * ny < 0:
            nx, ny = -nx, -ny
        if (x - ax) * nx + (y - ay) * ny < inset:
            return False
    return True


def inside_pin(x, y, inset=0.0):
    """True when (x, y) is inside the pin: the head circle, or the triangle below it."""
    if (x - CX) ** 2 + (y - HEAD_CY) ** 2 <= (HEAD_R - inset) ** 2:
        return True
    return _inside_convex(x, y, [P_LEFT, TIP, P_RIGHT], inset)

# ----- path data -----------------------------------------------------------------------

def pin_path():
    return (f"M{P_LEFT[0]:.2f},{P_LEFT[1]:.2f}"
            f"A{HEAD_R:.2f},{HEAD_R:.2f} 0 1 1 {P_RIGHT[0]:.2f},{P_RIGHT[1]:.2f}"
            f"L{CX:.2f},{TIP_Y:.2f}Z")


def circle_path(cx, cy, r):
    return (f"M{cx - r:.2f},{cy:.2f}"
            f"a{r:.2f},{r:.2f} 0 1 0 {2 * r:.2f},0"
            f"a{r:.2f},{r:.2f} 0 1 0 {-2 * r:.2f},0Z")


def rect_path(x, y, w, h, radius=0.0):
    if radius <= 0:
        return f"M{x:.2f},{y:.2f}h{w:.2f}v{h:.2f}h{-w:.2f}Z"
    r = radius
    return (f"M{x + r:.2f},{y:.2f}h{w - 2 * r:.2f}"
            f"a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f}v{h - 2 * r:.2f}"
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f}h{-(w - 2 * r):.2f}"
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f}v{-(h - 2 * r):.2f}"
            f"a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f}Z")


def quad_path(start, control, end):
    return f"M{start[0]:.2f},{start[1]:.2f}Q{control[0]:.2f},{control[1]:.2f} {end[0]:.2f},{end[1]:.2f}"


def stem_path():
    return rect_path(STEM_X - STEM_W / 2, STEM_TOP, STEM_W, NOTE_HEAD_Y - STEM_TOP + 1.4, 1.4)


def flag_path():
    (sx, sy), c1, c2, (ex, ey) = FLAG
    return (f"M{sx:.2f},{sy:.2f}"
            f"C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {ex:.2f},{ey:.2f}"
            f"L{sx:.2f},{ey + 1.6:.2f}Z")


def tile_path(size=108.0, radius=24.0):
    r = radius
    return (f"M{r:.2f},0h{size - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f}"
            f"v{size - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f}"
            f"h{-(size - 2 * r):.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f}"
            f"v{-(size - 2 * r):.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f}Z")


# ----- the mark in paint order ---------------------------------------------------------

def bold_note():
    """The note, drawn heavier: a stem, a bigger head and a wider flag."""
    stem = rect_path(STEM_X - 2.4, 36.0, 4.8, NOTE_HEAD_Y - 36.0, 2.2)
    head = circle_path(NOTE_HEAD_X, NOTE_HEAD_Y, 7.6)
    flag = "M55.40,36.00C64.80,38.60 66.60,45.20 58.60,49.60L55.40,51.20Z"
    return stem, head, flag


def mark():
    """(path data, paint, colour). `paint` is "fill", ("fill", fillType) or ("stroke", width)."""
    layers = [
        (pin_path(), "fill", LIME),
        (stem_path(), "fill", BACKGROUND),
        (circle_path(NOTE_HEAD_X, NOTE_HEAD_Y, NOTE_HEAD_R), "fill", BACKGROUND),
        (flag_path(), "fill", BACKGROUND),
        (circle_path(NOTE_HEAD_X, NOTE_HEAD_Y, CORAL_R), "fill", CORAL),
    ]
    for (start, control, end), width in WAVES:
        layers.append((quad_path(start, control, end), ("stroke", width), BACKGROUND))
    return layers


def monochrome_mark():
    """Android 13+ themed icons: the pin with a bold note knocked out of it.

    Two rules shape this layer. The cut-out needs every subpath inside a *single* path element
    with fillType="evenOdd" — a shape nested in another becomes a hole, whereas separate elements
    would just paint white over white. And the system tints the whole thing one flat colour, so
    the note has to be heavier than the colour version to survive at 48dp; the fine ripples are
    dropped for the same reason.
    """
    combined = " ".join((pin_path(),) + bold_note())
    return [(combined, ("fill", "evenOdd"), "#FFFFFFFF")]


# Notification small icons are drawn at 24dp and tinted by the system, so the mark has to fill
# much more of its box than the launcher icon does. The pin is 63.5dp tall, so this is the
# largest scale that still leaves a margin inside the 108dp viewport.
NOTIFICATION_SCALE = 1.6


def notification_mark():
    """Status-bar icon: one flat silhouette, white on transparent, in the 108dp viewport.

    The system replaces every colour in a notification's small icon with a single tint, so this
    is the pin with the note knocked out and nothing else — the ripples would vanish into the
    tint, and the app icon's dark tile would become a solid blob in the status bar.
    """
    return [(" ".join((pin_path(),) + bold_note()), ("fill", "evenOdd"), "#FFFFFFFF")]


# ----- path-data validation ------------------------------------------------------------

ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def validate(path_data, label):
    for cmd, args in re.findall(r"([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)", path_data):
        need = ARITY[cmd.upper()]
        if need == 0:
            continue
        found = len(NUMBER.findall(args))
        if found == 0 or found % need:
            raise SystemExit(f"{label}: '{cmd}' carries {found} numbers; needs groups of {need}")
    return path_data


def xml_escape(value):
    return value.replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;")


def vector_document(paint_layers, width_dp=108.0, scale=None, pivot=54.0):
    """An Android vector drawable for `paint_layers`.

    `scale` wraps the paths in a group scaled about the canvas centre, which is how the
    notification icon grows the mark to fill a 24dp status-bar slot without duplicating geometry.
    """
    indent = "    "
    prefix, suffix = "", ""
    if scale is not None and scale != 1.0:
        prefix = ('    <group android:scaleX="%g" android:scaleY="%g" '
                  'android:pivotX="%g" android:pivotY="%g">\n' % (scale, scale, pivot, pivot))
        indent = "        "
        suffix = "    </group>\n"
    lines = []
    for data, paint, colour in paint_layers:
        attributes = []
        if isinstance(paint, tuple) and paint[0] == "fill":
            attributes.append('android:fillType="%s"' % paint[1])
        attributes.append('android:fillColor="%s"' % colour)
        if isinstance(paint, tuple) and paint[0] == "stroke":
            attributes = [f'android:strokeColor="{colour}"',
                          f'android:strokeWidth="{paint[1]}"',
                          'android:strokeLineCap="round"',
                          'android:strokeLineJoin="round"']
        data = validate(data, "pathData")
        lines.append("%s<path %s\n%s    android:pathData=\"%s\" />"
                     % (indent, " ".join(attributes), indent, data))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/build_icon.py — edit the geometry there, not here. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width_dp:g}dp"\n'
            f'    android:height="{width_dp:g}dp"\n'
            f'    android:viewportWidth="{SIZE:g}"\n'
            f'    android:viewportHeight="{SIZE:g}">\n'
            + prefix + "\n".join(lines) + "\n" + suffix + "</vector>\n")


# ----- rasterising the same path data --------------------------------------------------

def _arc_points(x0, y0, rx, ry, large, sweep, x1, y1, steps=96):
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    lam = (dx2 / rx) ** 2 + (dy2 / ry) ** 2
    if lam > 1:
        rx *= math.sqrt(lam)
        ry *= math.sqrt(lam)
    sign = -1.0 if large == sweep else 1.0
    num = rx * rx * ry * ry - rx * rx * dy2 * dy2 - ry * ry * dx2 * dx2
    den = rx * rx * dy2 * dy2 + ry * ry * dx2 * dx2
    co = sign * math.sqrt(max(0.0, num / den)) if den else 0.0
    cxp, cyp = co * rx * dy2 / ry, -co * ry * dx2 / rx
    cx, cy = cxp + (x0 + x1) / 2.0, cyp + (y0 + y1) / 2.0

    def angle(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        length = math.hypot(ux, uy) * math.hypot(vx, vy)
        value = max(-1.0, min(1.0, dot / length if length else 1.0))
        a = math.acos(value)
        return -a if ux * vy - uy * vx < 0 else a

    theta = angle(1, 0, (dx2 - cxp) / rx, (dy2 - cyp) / ry)
    delta = angle((dx2 - cxp) / rx, (dy2 - cyp) / ry, (-dx2 - cxp) / rx, (-dy2 - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    if sweep and delta < 0:
        delta += 2 * math.pi
    return [(cx + rx * math.cos(theta + delta * i / steps),
             cy + ry * math.sin(theta + delta * i / steps)) for i in range(1, steps + 1)]


def _cubic_points(p0, p1, p2, p3, steps=64):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def flatten(path_data):
    """Subpaths as point lists, covering the commands this module emits."""
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?",
                        path_data)
    subpaths, current, cursor, start = [], [], (0.0, 0.0), (0.0, 0.0)
    index, cmd = 0, None
    previous_control = None      # for the smooth variants (S/T)
    previous_kind = None

    def number():
        nonlocal index
        value = float(tokens[index])
        index += 1
        return value

    while index < len(tokens):
        token = tokens[index]
        if re.match(r"[A-Za-z]", token):
            cmd = token
            index += 1
            if cmd.upper() == "Z":
                if current:
                    current.append(start)
                    subpaths.append(current)
                    current, cursor = [], start
                continue
        relative = cmd.islower()
        upper = cmd.upper()

        def point(a, b):
            return (cursor[0] + a, cursor[1] + b) if relative else (a, b)

        if upper == "M":
            x, y = point(number(), number())
            if current:
                subpaths.append(current)
            current, cursor, start = [(x, y)], (x, y), (x, y)
        elif upper == "L":
            x, y = point(number(), number())
            current.append((x, y))
            cursor = (x, y)
        elif upper == "H":
            value = number()
            x = cursor[0] + value if relative else value
            current.append((x, cursor[1]))
            cursor = (x, cursor[1])
        elif upper == "V":
            value = number()
            y = cursor[1] + value if relative else value
            current.append((cursor[0], y))
            cursor = (cursor[0], y)
        elif upper in ("C", "S"):
            values = [number() for _ in range(6 if upper == "C" else 4)]
            if upper == "C":
                p1, p2, p3 = ((values[i], values[i + 1]) for i in (0, 2, 4))
            else:
                # Smooth cubic: the first control point mirrors the previous one.
                if previous_kind in ("C", "S") and previous_control is not None:
                    p1 = (2 * cursor[0] - previous_control[0], 2 * cursor[1] - previous_control[1])
                else:
                    p1 = cursor
                p2, p3 = (values[0], values[1]), (values[2], values[3])
            if relative:
                p1, p2, p3 = ((p1[0] + cursor[0], p1[1] + cursor[1]),
                              (p2[0] + cursor[0], p2[1] + cursor[1]),
                              (p3[0] + cursor[0], p3[1] + cursor[1]))
            current += _cubic_points(cursor, p1, p2, p3)
            previous_control, previous_kind = p2, upper
            cursor = p3
        elif upper == "Q":
            values = [number() for _ in range(4)]
            control, end = (values[0], values[1]), (values[2], values[3])
            if relative:
                control = (control[0] + cursor[0], control[1] + cursor[1])
                end = (end[0] + cursor[0], end[1] + cursor[1])
            for step in range(1, 33):
                t = step / 32
                u = 1 - t
                current.append((u * u * cursor[0] + 2 * u * t * control[0] + t * t * end[0],
                                u * u * cursor[1] + 2 * u * t * control[1] + t * t * end[1]))
            cursor = end
        elif upper == "A":
            rx, ry, _, large, sweep, x, y = [number() for _ in range(7)]
            if relative:
                x, y = x + cursor[0], y + cursor[1]
            current += _arc_points(cursor[0], cursor[1], rx, ry, int(large) == 1,
                                   int(sweep) == 1, x, y)
            cursor = (x, y)
        else:
            raise SystemExit(f"unsupported command {cmd}")
    if current:
        subpaths.append(current)
    return subpaths


def _colour(value):
    """'#RRGGBB' or an (r, g, b) / (r, g, b, a) tuple to an RGBA tuple."""
    if not isinstance(value, str):
        channels = tuple(value)
        return channels if len(channels) == 4 else channels + (255,)
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def render(paint_layers, size, background=None, mask="none", supersample=4, tile=False,
           scale=None):
    """Scanline-fill / stroked render of the same path data, at `size` px.

    `scale` mirrors the <group> transform a vector document can carry, so a preview matches what
    Android draws.
    """
    from PIL import Image, ImageDraw
    big = int(size * supersample)
    factor = big / SIZE
    zoom = scale if scale is not None else 1.0

    def place(x, y):
        return (54 + (x - 54) * zoom) * factor, (54 + (y - 54) * zoom) * factor
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    pixels = canvas.load()

    def put(x, y, rgb):
        if 0 <= x < big and 0 <= y < big:
            pixels[x, y] = rgb

    if background:
        canvas.paste(_colour(background), (0, 0, big, big))

    if tile:
        # Legacy launcher icon: the dark rounded-square tile behind the mark.
        tile_layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        ImageDraw.Draw(tile_layer).rounded_rectangle(
            [0, 0, big - 1, big - 1], radius=int(SIZE * 0.22 * factor), fill=_colour(BACKGROUND))
        canvas.alpha_composite(tile_layer)

    for data, paint, colour in paint_layers:
        rgb = _colour(colour)
        if isinstance(paint, tuple) and paint[0] == "stroke":
            radius = max(1, int(round(paint[1] * factor / 2)))
            points = [place(x, y) for x, y in flatten(data)[0]]
            for index in range(len(points) - 1):
                (x0, y0), (x1, y1) = points[index], points[index + 1]
                steps = max(2, int(math.hypot(x1 - x0, y1 - y0) * 2))
                for step in range(steps + 1):
                    t = step / steps
                    cx, cy = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
                    for dy in range(-radius, radius + 1):
                        span = math.sqrt(max(0.0, radius * radius - dy * dy))
                        for x in range(int(cx - span), int(cx + span) + 1):
                            put(x, int(cy) + dy, rgb)
        else:
            even_odd = isinstance(paint, tuple) and len(paint) > 1 and paint[1] == "evenOdd"
            groups = []
            for sub in flatten(data):
                points = [place(x, y) for x, y in sub]
                edges = []
                for index in range(len(points)):
                    x0, y0 = points[index]
                    x1, y1 = points[(index + 1) % len(points)]
                    if y0 != y1:
                        edges.append((x0, y0, x1, y1))
                if edges:
                    groups.append((edges, min(p[1] for p in points), max(p[1] for p in points)))
            if even_odd:
                # One crossing list across every subpath, so a shape inside another is a hole.
                groups = [([edge for edges, _, _ in groups for edge in edges],
                           min(top for _, top, _ in groups), max(bottom for _, _, bottom in groups))]
            for edges, group_top, group_bottom in groups:
                top = max(0, int(group_top))
                bottom = min(big - 1, int(group_bottom) + 1)
                for y in range(top, bottom + 1):
                    crossings = sorted(x0 + (y - y0) * (x1 - x0) / (y1 - y0)
                                       for x0, y0, x1, y1 in edges
                                       if (y0 <= y < y1) or (y1 <= y < y0))
                    for index in range(0, len(crossings) - 1, 2):
                        for x in range(max(0, int(crossings[index])),
                                       min(big, int(crossings[index + 1]) + 1)):
                            put(x, y, rgb)

    if mask == "circle":
        circle_mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(circle_mask).ellipse([0, 0, big - 1, big - 1], fill=255)
        canvas.putalpha(Image.composite(canvas.getchannel("A"),
                                        Image.new("L", (big, big), 0), circle_mask))
    return canvas.resize((int(size), int(size)), Image.LANCZOS)


# ----- entry point ---------------------------------------------------------------------

def write_resources(root, preview_dir=None):
    res = pathlib.Path(root) / "app/src/main/res"
    (res / "drawable").mkdir(parents=True, exist_ok=True)

    (res / "drawable/ic_soundtrail_foreground.xml").write_text(
        vector_document(mark()), encoding="utf-8")
    (res / "drawable/ic_soundtrail_monochrome.xml").write_text(
        vector_document(monochrome_mark()), encoding="utf-8")
    legacy = [(tile_path(), "fill", BACKGROUND)] + mark()
    (res / "drawable/ic_soundtrail.xml").write_text(
        vector_document(legacy), encoding="utf-8")
    # Status-bar icon: 24dp on screen, so the mark is scaled up inside the 108 canvas.
    (res / "drawable/ic_soundtrail_notification.xml").write_text(
        vector_document(notification_mark(), width_dp=24.0, scale=NOTIFICATION_SCALE),
        encoding="utf-8")
    (res / "mipmap-anydpi-v26/ic_soundtrail.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by tools/build_icon.py — edit the geometry there, not here. -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/soundtrail_icon_background" />\n'
        '    <foreground android:drawable="@drawable/ic_soundtrail_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_soundtrail_monochrome" />\n'
        '</adaptive-icon>\n', encoding="utf-8")

    legacy_layers = [(tile_path(), "fill", BACKGROUND)] + mark()
    for folder, pixels in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                           ("xxhdpi", 144), ("xxxhdpi", 192), ("nodpi", 512)):
        target = res / f"mipmap-{folder}/ic_soundtrail.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        image = render(legacy_layers, pixels, mask="none", supersample=4, tile=True)
        image.save(target)

    if preview_dir:
        out = pathlib.Path(preview_dir)
        out.mkdir(parents=True, exist_ok=True)
        render(mark(), 512, background=BACKGROUND, mask="circle").save(out / "adaptive_circle_512.png")
        render(mark(), 512, background=BACKGROUND, mask="none").save(out / "adaptive_square_512.png")
        render(monochrome_mark(), 192, background="#2B4A7D", mask="circle").save(
            out / "themed_192.png")
        render(legacy_layers, 48, mask="none", supersample=8, tile=True).resize(
            (192, 192), 0).save(out / "legacy_48_zoom.png")
        render(mark(), 40, background=BACKGROUND, mask="circle").resize(
            (160, 160), 0).save(out / "adaptive_circle_40_zoom.png")


def check(root, verbose=True):
    """Fail when the committed resources do not match what this file generates.

    A malformed path is not a cosmetic problem: Android's vector parser reads whole command
    groups, so data it cannot parse leaves the drawable wrong or undrawable. This is the guard
    that would have caught the icon that shipped with the placeholder.
    """
    import sys
    root = pathlib.Path(root)
    problems = []

    # 1. Every pathData in the resources, generated or hand-written, must be well formed.
    for xml_path in sorted((root / "app/src/main/res").rglob("*.xml")):
        try:
            document = ET.parse(xml_path)
        except ET.ParseError as error:
            problems.append(f"{xml_path.relative_to(root)}: not valid XML ({error})")
            continue
        for element in document.getroot().iter():
            for key, value in element.attrib.items():
                if key.endswith("pathData"):
                    try:
                        validate(value, str(xml_path.relative_to(root)))
                    except SystemExit as error:
                        problems.append(str(error))

    # 2. The drawables on disk must be exactly what the geometry above produces.
    expected_drawables = {
        "drawable/ic_soundtrail_foreground.xml": mark(),
        "drawable/ic_soundtrail_monochrome.xml": monochrome_mark(),
        "drawable/ic_soundtrail.xml": [(tile_path(), "fill", BACKGROUND)] + mark(),
    }
    expected_notification = vector_document(notification_mark(), width_dp=24.0,
                                            scale=NOTIFICATION_SCALE)
    path = root / "app/src/main/res/drawable/ic_soundtrail_notification.xml"
    if (path.read_text(encoding="utf-8") if path.exists() else "") != expected_notification:
        problems.append("drawable/ic_soundtrail_notification.xml is out of date — run: "
                        "python3 tools/build_icon.py")
    for name, layers in expected_drawables.items():
        path = root / "app/src/main/res" / name
        want = vector_document(layers)
        have = path.read_text(encoding="utf-8") if path.exists() else ""
        if want != have:
            problems.append(f"{name} is out of date — run: python3 tools/build_icon.py")

    # 3. The mipmap PNGs must match the same geometry, pixel for pixel (needs Pillow).
    try:
        from PIL import Image
    except ImportError:
        if verbose:
            print("note: Pillow is not installed, so the PNGs were not re-checked")
        Image = None
    if Image is not None:
        legacy = [(tile_path(), "fill", BACKGROUND)] + mark()
        for folder, pixels in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                               ("xxhdpi", 144), ("xxxhdpi", 192), ("nodpi", 512)):
            path = root / f"app/src/main/res/mipmap-{folder}/ic_soundtrail.png"
            if not path.exists():
                problems.append(f"mipmap-{folder}/ic_soundtrail.png is missing")
                continue
            rendered = render(legacy, pixels, supersample=4, tile=True)
            with Image.open(path) as committed:
                if committed.size != (pixels, pixels):
                    problems.append(f"mipmap-{folder}/ic_soundtrail.png is {committed.size}, "
                                    f"expected {(pixels, pixels)}")
                elif committed.convert("RGBA").tobytes() != rendered.convert("RGBA").tobytes():
                    problems.append(f"mipmap-{folder}/ic_soundtrail.png does not match the "
                                    f"geometry — run: python3 tools/build_icon.py")

    if problems:
        print("icon resources are not up to date:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    if verbose:
        print("icon resources match tools/build_icon.py")
    return 0


if __name__ == "__main__":
    import sys
    root = pathlib.Path(__file__).resolve().parents[1]
    if "--check" in sys.argv:
        raise SystemExit(check(root))
    write_resources(root)
    print("wrote drawables, adaptive icon, and mipmap PNGs")
    print(f"pin: head ({CX}, {HEAD_CY}) r{HEAD_R}, tip ({CX}, {TIP_Y})")
    print(f"tangents: ({P_LEFT[0]:.2f}, {P_LEFT[1]:.2f}) ({P_RIGHT[0]:.2f}, {P_RIGHT[1]:.2f})")

    # The safe circle: a launcher may mask anything beyond 33dp from the centre, so every
    # visible part of the mark has to sit inside it.
    problems = []
    samples = [(CX, HEAD_CY - HEAD_R), TIP, (CX - HEAD_R, HEAD_CY), (CX + HEAD_R, HEAD_CY)]
    for (start, control, end), width in WAVES:
        for index in range(33):
            t = index / 32
            u = 1 - t
            x = u * u * start[0] + 2 * u * t * control[0] + t * t * end[0]
            y = u * u * start[1] + 2 * u * t * control[1] + t * t * end[1]
            samples.append((x, y + width / 2))
    samples += [(NOTE_HEAD_X, NOTE_HEAD_Y), (STEM_X, STEM_TOP),
                (FLAG[3][0], FLAG[3][1]), (FLAG[0][0], FLAG[0][1])]
    for x, y in samples:
        distance = math.hypot(x - 54, y - 54)
        if distance > 33.0:
            problems.append(f"({x:.1f}, {y:.1f}) is {distance:.1f}dp from centre (safe radius 33dp)")
    if problems:
        raise SystemExit("mark leaves the safe circle:\n  " + "\n  ".join(problems))
    print(f"safe-circle check: all {len(samples)} sampled outlines within 33dp")

    # Ink must stay inside the pin, or the note and ripples would look stuck onto it.
    for (start, control, end), width in WAVES:
        for index in range(33):
            t = index / 32
            u = 1 - t
            x = u * u * start[0] + 2 * u * t * control[0] + t * t * end[0]
            y = u * u * start[1] + 2 * u * t * control[1] + t * t * end[1]
            if not inside_pin(x, y, inset=width / 2 + 0.4):
                raise SystemExit(f"ripple point ({x:.1f}, {y:.1f}) pokes outside the pin")
    print("containment check: ripples sit inside the pin with margin")
