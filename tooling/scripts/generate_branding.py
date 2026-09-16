#!/usr/bin/env python3
"""Generates the app's launcher icon and Android TV banner from the owner's Luz artwork.

Source: tooling/branding/luz-icon-source.png (square icon tile with the play mark, "LUZ" and "IPTV").
Outputs (regenerate after changing the source; the results are committed):
  apps/android/tv/src/main/res/mipmap-*/ic_launcher.png   launcher/settings icon, 48-192 px
  apps/android/tv/src/main/res/drawable-*/tv_banner.png   Android TV home-screen banner, 320x180 dp

The banner reuses the play mark and the wordmark cut from the same artwork, so the branding stays identical.
Requires Pillow (development machine only; not shipped).
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tooling/branding/luz-icon-source.png"
RES = ROOT / "apps/android/tv/src/main/res"

# Regions of the source artwork (measured once; see ADR-0029).
# The rounded tile inside the artwork (the icon itself, without the darker page background).
TILE = (91, 81, 1163, 1172)
MARK = (429, 259, 841, 737)
WORDMARK = (360, 793, 897, 917)
SUBTITLE = (490, 963, 765, 1003)
TILE_COLOUR = (21, 22, 25)

ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 320x180 dp at each density. TVs use xhdpi/xxhdpi; the smaller ones keep the density folders complete.
BANNER_SIZES = {"mdpi": (320, 180), "hdpi": (480, 270), "xhdpi": (640, 360), "xxhdpi": (960, 540)}


def icon(source: Image.Image) -> None:
    """The tile with rounded, transparent corners: a launcher icon must not fill its whole square."""
    tile = source.crop(TILE).convert("RGBA")
    for density, size in ICON_SIZES.items():
        scaled = tile.resize((size, size), Image.LANCZOS)
        supersample = 4
        mask = Image.new("L", (size * supersample, size * supersample), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            (0, 0, size * supersample - 1, size * supersample - 1),
            radius=int(size * supersample * 0.22),
            fill=255,
        )
        scaled.putalpha(mask.resize((size, size), Image.LANCZOS))
        target = RES / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        scaled.save(target / "ic_launcher.png", optimize=True)


def cut(source: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    """Crops [box] and turns the artwork's own dark background into transparency, so crops blend into the banner."""
    piece = source.crop(box).convert("RGB")
    alpha = piece.convert("L").point(lambda value: 0 if value < 28 else min(255, int((value - 28) * 255 / 45)))
    piece = piece.convert("RGBA")
    piece.putalpha(alpha)
    return piece


def banner(source: Image.Image) -> None:
    mark = cut(source, MARK)
    word = cut(source, WORDMARK)
    subtitle = cut(source, SUBTITLE)
    for density, (width, height) in BANNER_SIZES.items():
        canvas = Image.new("RGBA", (width, height), TILE_COLOUR + (255,))
        mark_height = int(height * 0.56)
        mark_width = int(mark.width * mark_height / mark.height)
        placed_mark = mark.resize((mark_width, mark_height), Image.LANCZOS)
        word_width = int(width * 0.34)
        word_height = int(word.height * word_width / word.width)
        placed_word = word.resize((word_width, word_height), Image.LANCZOS)
        sub_width = int(word_width * 0.52)
        sub_height = int(subtitle.height * sub_width / subtitle.width)
        placed_sub = subtitle.resize((sub_width, sub_height), Image.LANCZOS)

        gap = int(width * 0.05)
        block = mark_width + gap + word_width
        left = (width - block) // 2
        canvas.alpha_composite(placed_mark, (left, (height - mark_height) // 2))
        text_top = (height - (word_height + placed_sub.height + int(height * 0.03))) // 2
        canvas.alpha_composite(placed_word, (left + mark_width + gap, text_top))
        canvas.alpha_composite(
            placed_sub,
            (left + mark_width + gap + (word_width - sub_width) // 2, text_top + word_height + int(height * 0.03)),
        )

        target = RES / f"drawable-{density}"
        target.mkdir(parents=True, exist_ok=True)
        canvas.convert("RGB").save(target / "tv_banner.png", optimize=True)


def main() -> int:
    source = Image.open(SOURCE).convert("RGB")
    icon(source)
    banner(source)
    print(f"branding: icons {sorted(ICON_SIZES.values())} px, banners {list(BANNER_SIZES.values())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
