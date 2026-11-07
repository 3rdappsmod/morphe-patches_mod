#!/usr/bin/env python3
"""Draws the Danpoong icon: three maple leaves on a rounded navy tile."""

import math
from PIL import Image, ImageDraw

SIZE = 512
SS = 4
S = SIZE * SS

NAVY_TOP = (33, 45, 70)
NAVY_BOTTOM = (13, 19, 32)
LEAF_AMBER = (248, 187, 90)
LEAF_RED = (185, 43, 37)
VEIN = (255, 244, 222)

# The 23-point maple leaf of Canada's flag, normalized to a 1 x 1 box with the tip at the top
# and the stem at the bottom middle.
LEAF = [
    (0.500, 0.000), (0.548, 0.085), (0.593, 0.068), (0.579, 0.153), (0.669, 0.126),
    (0.748, 0.174), (0.696, 0.249), (0.767, 0.249), (0.753, 0.288), (0.841, 0.336),
    (0.813, 0.379), (0.889, 0.463), (0.731, 0.463), (0.699, 0.502), (0.738, 0.563),
    (0.648, 0.550), (0.598, 0.598), (0.530, 0.567), (0.510, 0.567), (0.525, 0.735),
    (0.475, 0.735), (0.490, 0.567), (0.470, 0.567), (0.402, 0.598), (0.352, 0.550),
    (0.262, 0.563), (0.301, 0.502), (0.269, 0.463), (0.111, 0.463), (0.187, 0.379),
    (0.159, 0.336), (0.247, 0.288), (0.233, 0.249), (0.304, 0.249), (0.252, 0.174),
    (0.331, 0.126), (0.421, 0.153), (0.407, 0.068), (0.452, 0.085),
]
PIVOT = (0.500, 0.735)


def vertical_gradient(size, top, bottom):
    img = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(size - 1, 1)
        color = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        draw.line([(0, y), (size, y)], fill=color)
    return img


def place(base_x, base_y, height, rotation_deg):
    """Moves the leaf's stem base to (base_x, base_y), scaled and rotated about it."""
    rad = math.radians(rotation_deg)
    cos_r, sin_r = math.cos(rad), math.sin(rad)
    points = []
    for x, y in LEAF:
        dx = (x - PIVOT[0]) * height
        dy = (y - PIVOT[1]) * height
        points.append((base_x + dx * cos_r - dy * sin_r, base_y + dx * sin_r + dy * cos_r))
    return points


def midrib(base_x, base_y, height, rotation_deg):
    """A light stroke up the middle of the blade, from the stem base to near the tip."""
    rad = math.radians(rotation_deg)
    cos_r, sin_r = math.cos(rad), math.sin(rad)
    points = []
    for i in range(61):
        t = i / 60.0
        dy = (-0.60 * height) * t
        points.append((base_x + 0 * cos_r - dy * sin_r, base_y + 0 * sin_r + dy * cos_r))
    return points


def draw_leaf(tile, base_x, base_y, height, rot):
    polygon = place(base_x, base_y, height, rot)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).polygon(polygon, fill=255)

    leaf = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    leaf.paste(vertical_gradient(S, LEAF_AMBER, LEAF_RED), (0, 0), mask)

    width = max(2, int(S * 0.0065))
    ImageDraw.Draw(leaf).line(midrib(base_x, base_y, height, rot), fill=VEIN, width=width)
    tile.alpha_composite(Image.composite(leaf, Image.new("RGBA", (S, S), (0, 0, 0, 0)), mask))

    # A hairline of the tile colour so overlapping leaves stay distinct.
    ImageDraw.Draw(tile).line(polygon + [polygon[0]], fill=NAVY_BOTTOM + (255,),
                              width=max(2, int(S * 0.005)), joint="curve")


def main():
    tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    margin = int(S * 0.05)
    radius = int(S * 0.225)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [margin, margin, S - margin, S - margin], radius=radius, fill=255
    )
    tile.paste(vertical_gradient(S, NAVY_TOP, NAVY_BOTTOM), (0, 0), mask)

    # A small fan: two leaves behind at the sides, the largest one in front and centred.
    for cx_f, base_f, height_f, rot in (
        (0.295, 0.815, 0.470, -15.0),
        (0.705, 0.815, 0.470, 15.0),
        (0.500, 0.735, 0.560, 0.0),
    ):
        draw_leaf(tile, S * cx_f, S * base_f, S * height_f, rot)

    icon = tile.resize((SIZE, SIZE), Image.LANCZOS)
    icon.save("docs/icons/avatar.png", "PNG")
    icon.resize((192, 192), Image.LANCZOS).save("patches-bundle.png", "PNG")
    print("wrote docs/icons/avatar.png and patches-bundle.png")


if __name__ == "__main__":
    main()
