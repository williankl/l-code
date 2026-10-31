"""Draws the Lemonade Code app icon (Claude Design, "Lemonade Code Icon", concept 1c "Wedge prompt").

A prompt chevron followed by half a lemon slice, lime on olive. Small sizes use the design's
simplified drawings so they still read at 16 and 32 px.

    python3 apps/desktop/icons/make_icons.py

Writes, next to this script: lemonade-code.svg (the full-size drawing), lemonade-code.png
(Linux), lemonade-code.icns (macOS, on Apple's icon grid) and lemonade-code.ico (Windows), plus
the window icon in src/desktopMain/resources. Needs Pillow.
"""

from pathlib import Path

from PIL import Image, ImageDraw

LIME = "#E1E51A"
OLIVE = "#28310B"

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent / "src" / "desktopMain" / "resources"

# Shapes in the design's 100 x 100 viewBox, per drawing. Half discs are (radius, centre x),
# always on the right of their centre; strokes are (points, width, colour).
FULL = {
    "discs": [(32, 50, LIME), (28, 50, OLIVE), (25, 50, LIME)],
    "strokes": [
        ([(50, 50), (71.7, 37.5)], 3, OLIVE),
        ([(50, 50), (71.7, 62.5)], 3, OLIVE),
        ([(20, 36), (34, 50), (20, 64)], 8, LIME),
    ],
}
# The design thickens the segment lines at 64 px.
MEDIUM = {
    "discs": FULL["discs"],
    "strokes": [(points, 3.5 if colour == OLIVE else width, colour) for points, width, colour in FULL["strokes"]],
}
SMALL = {
    "discs": [(34, 48, LIME)],
    "strokes": [([(16, 34), (32, 50), (16, 66)], 11, LIME)],
}
TINY = {
    "discs": [(36, 48, LIME)],
    "strokes": [([(14, 32), (32, 50), (14, 68)], 14, LIME)],
}

# Corner radius of the tile: 57 px on the design's 256 px tile.
RADIUS = 57 / 256 * 100
SUPERSAMPLE = 8


def drawing_for(size: int) -> dict:
    if size <= 20:
        return TINY
    if size <= 40:
        return SMALL
    if size <= 96:
        return MEDIUM
    return FULL


def render(size: int, inset: float = 0.0) -> Image.Image:
    """The icon at [size] px; [inset] is the empty margin around the tile, as a share of [size]."""
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    margin = big * inset
    unit = (big - 2 * margin) / 100

    def at(x: float, y: float) -> tuple:
        return (margin + x * unit, margin + y * unit)

    draw.rounded_rectangle([at(0, 0), at(100, 100)], radius=RADIUS * unit, fill=OLIVE)
    drawing = drawing_for(size)
    for radius, centre_x, colour in drawing["discs"]:
        draw.pieslice([at(centre_x - radius, 50 - radius), at(centre_x + radius, 50 + radius)], -90, 90, fill=colour)
    for points, width, colour in drawing["strokes"]:
        scaled = [at(x, y) for x, y in points]
        draw.line(scaled, fill=colour, width=round(width * unit), joint="curve")
        cap = width * unit / 2
        for x, y in (scaled[0], scaled[-1]):
            draw.ellipse([x - cap, y - cap, x + cap, y + cap], fill=colour)
    return image.resize((size, size), Image.LANCZOS)


def svg() -> str:
    discs = "".join(
        f'<path d="M{x},{50 - r} A{r},{r} 0 0 1 {x},{50 + r} Z" fill="{c}"/>' for r, x, c in FULL["discs"]
    )
    strokes = "".join(
        f'<polyline points="{" ".join(f"{x},{y}" for x, y in p)}" fill="none" stroke="{c}" stroke-width="{w}" '
        f'stroke-linecap="round" stroke-linejoin="round"/>'
        for p, w, c in FULL["strokes"]
    )
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
        f'<rect width="100" height="100" rx="{RADIUS:.2f}" fill="{OLIVE}"/>{discs}{strokes}</svg>\n'
    )


def main() -> None:
    (HERE / "lemonade-code.svg").write_text(svg())
    render(512).save(HERE / "lemonade-code.png")
    RESOURCES.mkdir(parents=True, exist_ok=True)
    render(256).save(RESOURCES / "lemonade-code.png")

    # macOS: the tile sits on Apple's grid, 824 of 1024 px, with room for the system shadow.
    mac_inset = 100 / 1024
    mac_sizes = [16, 32, 64, 128, 256, 512, 1024]
    mac = [render(s, mac_inset) for s in mac_sizes]
    mac[-1].save(HERE / "lemonade-code.icns", append_images=mac[:-1])

    windows_sizes = [16, 24, 32, 48, 64, 128, 256]
    windows = [render(s) for s in windows_sizes]
    windows[-1].save(
        HERE / "lemonade-code.ico",
        sizes=[(s, s) for s in windows_sizes],
        append_images=windows[:-1],
    )


if __name__ == "__main__":
    main()
