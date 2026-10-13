"""Draws the mod icon: a golden village bell with the yellow "!" of an errand, pixel art on a green tile.

Writes src/main/resources/assets/villagersremember/icon.png (128 px) and art/icon-512.png.
Run: python tools/gen_icon.py
"""
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

# 32 x 32. Letters: . transparent, g/G/d tile greens, o outline, y/Y/b bell golds, w highlight, ! marker, k marker edge
ART = """
................................
..gggggggggggggggggggggggggggg..
.gGGGGGGGGGGGGGGGGGGGGGGGGGGGGg.
.gGGGGGGGGGGGGGGGGGGGGGGGkkkkGg.
.gGGGGGGGGGGGGooooGGGGGGGk!!kGg.
.gGGGGGGGGGGGGoyyoGGGGGGGk!!kGg.
.gGGGGGGGGGGGGoyyoGGGGGGGk!!kGg.
.gGGGGGGGGGGooooooooGGGGGk!!kGg.
.gGGGGGGGGGoyywYYYYboGGGGk!!kGg.
.gGGGGGGGGoyywYYYYYYboGGGkkkkGg.
.gGGGGGGGGoywYYYYYYYboGGGk!!kGg.
.gGGGGGGGoyywYYYYYYYYboGGkkkkGg.
.gGGGGGGGoywYYYYYYYYYboGGGGGGGg.
.gGGGGGGGoywYYYYYYYYYboGGGGGGGg.
.gGGGGGGGoywYYYYYYYYYboGGGGGGGg.
.gGGGGGGoyywYYYYYYYYYYboGGGGGGg.
.gGGGGGGoywYYYYYYYYYYYboGGGGGGg.
.gGGGGGGoywYYYYYYYYYYYboGGGGGGg.
.gGGGGGoyywYYYYYYYYYYYYboGGGGGg.
.gGGGGGoywYYYYYYYYYYYYYboGGGGGg.
.gGGGGoyywYYYYYYYYYYYYYYboGGGGg.
.gGGGGooooooooooooooooooooGGGGg.
.gGGGGobbbbbbbbbbbbbbbbbboGGGGg.
.gGGGGooooooooooooooooooooGGGGg.
.gGGGGGGGGGGGGoyyoGGGGGGGGGGGGg.
.gGGGGGGGGGGGGoyyoGGGGGGGGGGGGg.
.gGGGGGGGGGGGGGooGGGGGGGGGGGGGg.
.gdGGGGGGGGGGGGGGGGGGGGGGGGGGdg.
.gddGGGGGGGGGGGGGGGGGGGGGGGGddg.
..gddddddddddddddddddddddddddg..
...gggggggggggggggggggggggggg...
................................
"""

COLORS = {
    ".": (0, 0, 0, 0),
    "g": (29, 74, 36, 255),
    "G": (58, 125, 64, 255),
    "d": (44, 99, 50, 255),
    "o": (61, 40, 10, 255),
    "y": (232, 176, 40, 255),
    "Y": (250, 205, 70, 255),
    "b": (184, 120, 22, 255),
    "w": (255, 244, 190, 255),
    "!": (255, 235, 59, 255),
    "k": (60, 50, 0, 255),
}


def main():
    rows = [r for r in ART.strip("\n").split("\n")]
    assert len(rows) == 32 and all(len(r) == 32 for r in rows), [len(r) for r in rows]
    img = Image.new("RGBA", (32, 32))
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            img.putpixel((x, y), COLORS[c])
    out = os.path.join(ROOT, "src/main/resources/assets/villagersremember/icon.png")
    img.resize((128, 128), Image.NEAREST).save(out)
    os.makedirs(os.path.join(ROOT, "art"), exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(ROOT, "art/icon-512.png"))
    print("ok")


if __name__ == "__main__":
    main()
