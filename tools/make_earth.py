#!/usr/bin/env python3
"""Builds the globe's day imagery for the desktop app from NASA's Blue Marble.

Source: Blue Marble: Next Generation, July 2004, with topography and
bathymetry (NASA Earth Observatory, Reto Stöckli; public domain). The eight
500 m pieces (A1..D2, each 90 x 90 degrees, 21600 x 21600 px) are downloaded
once into the cache folder and checked against the SHA-256 sums below.

Output: earth-day.pack, a pyramid of 512 x 512 JPEG tiles in plate carree
(equirectangular) layout. Level L is 2^(L+1) x 2^L tiles; tile (0, 0) is the
north-west corner at 180 W, 90 N. Levels 0-6 go up to 65536 x 32768 px for
the whole Earth, about 600 m per pixel at the equator (--max-level 5 gives
the Linux app's 32768 px pack).

Pack format (little-endian): tile data, then an index of
(level u8, pad u8, x u16, y u16, pad u16, offset u64, size u32) per tile,
then a footer: index offset u64, tile count u32, b"DXGP", version u32.

Needs Pillow and about 2.5 GB of free memory at level 6.

  python3 tools/make_earth.py --cache third_party/earth --out assets-build
"""
import argparse
import hashlib
import io
import os
import struct
import sys
import urllib.request

from PIL import Image

Image.MAX_IMAGE_PIXELS = None

# Columns A-D run west to east from 180 W; row 1 is the north, 2 the south.
PIECES = ["A1", "B1", "C1", "D1", "A2", "B2", "C2", "D2"]
LAYERS = {
    # Blue Marble: Next Generation, July 2004, topography and bathymetry.
    "day": {
        "base": ("https://assets.science.nasa.gov/content/dam/science/esd/eo/images/bmng/"
                 "bmng-topography-bathymetry/july/"),
        "name": "world.topo.bathy.200407.3x21600x21600.%s.jpg",
        "out": "earth-day.pack",
        "sha256": {
            "A1": "cda310b5a180c7a7da95a605bc3a3a3fa082ecf870e0d9ea48994813216bcb70",
            "B1": "15d8ceb55676e6b3e7fdcd72217d1edd665b5e7f7396c606e7b98c427b28558e",
            "C1": "ee8490ab1eb35d620d8d1ad8e69b3234c0b050e4eddb80e7232a2d165e475aa0",
            "D1": "47480a4e98289d830adc689c32356771a83cd55a003cdbf32db7ade2a6609b56",
            "A2": "1f0b66979774a6a5704d6e597024e80b9e1fe71ab64ca871d195763ae625ad02",
            "B2": "37ce4c49ff093781eb1da10c14876cea317dfdf4d93af44d8e0c3b394bcd86d7",
            "C2": "3237e61c6ed8eaab15d7cdf797b6de3ee5c62314aa05381478b76dabcba2660b",
            "D2": "6883c2d0141b8165e0c336a3df7535bd494d56e75b0d1bce6d25719644237ea5",
        },
    },
    # Black Marble 2016, colour, 500 m (NASA Earth Observatory, Joshua
    # Stevens, Suomi NPP VIIRS data from Miguel Roman, NASA GSFC).
    "night": {
        "base": ("https://assets.science.nasa.gov/content/dam/science/esd/eo/images/"
                 "imagerecords/144000/144898/"),
        "name": "BlackMarble_2016_%s.jpg",
        "out": "earth-night.pack",
        "sha256": {
            "A1": "f9e9a0fc1c6227bfb174639e5ff70d4f081810b4c72834a1ccdb9687150436f2",
            "B1": "d0ef6a0b15032d277235d59c336e82c6fbe9693cfa26aaa72fec92962574d11e",
            "C1": "446fcc72755ebc3625f96d39f2bcb247016038b74c90c655a65991e6de71b3b5",
            "D1": "449fdd6b2f6eec087f56b5a0dcee5ab3213ea0ade75ec746f38cea76f3acd442",
            "A2": "ed5c047b606caca73a829c912c8388db07ecfa6670baf1358fd9e538ef773428",
            "B2": "f56f36e2b76993b34dcf83163d8c8a23255251031597337833c9eb2400c5aa9b",
            "C2": "f52cb69c0eadb2efa0868472cca59ac5461df5c4a0ce27846bff1e60cbafbe44",
            "D2": "295a0b58aefc581ee3bcc8e513ce4e1a475057ab3de9dc94ccf5c0fe39be9972",
        },
    },
}
TILE = 512
MAX_LEVEL = 6            # 65536 px around the equator, about 600 m a pixel
QUALITY = 88


def fetch(cache, layer, piece):
    name = layer["name"] % piece
    path = os.path.join(cache, name)
    if not os.path.exists(path):
        print("downloading", name, flush=True)
        tmp = path + ".part"
        urllib.request.urlretrieve(layer["base"] + name, tmp)
        os.replace(tmp, path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    got = h.hexdigest()
    want = layer["sha256"].get(piece)
    if want and got != want:
        sys.exit("SHA-256 mismatch for %s: %s" % (piece, got))
    if not want:
        print("  %s sha256 %s" % (piece, got))
    return path


class Pack:
    def __init__(self, path):
        self.f = open(path, "wb")
        self.index = []

    def add(self, level, x, y, img):
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=QUALITY, optimize=True, progressive=False)
        data = buf.getvalue()
        self.index.append((level, x, y, self.f.tell(), len(data)))
        self.f.write(data)

    def close(self):
        at = self.f.tell()
        for level, x, y, off, size in sorted(self.index):
            self.f.write(struct.pack("<BBHHHQI", level, 0, x, y, 0, off, size))
        self.f.write(struct.pack("<QI4sI", at, len(self.index), b"DXGP", 1))
        self.f.close()


def cut(pack, level, img, x0, y0):
    """Cuts img into tiles, the first being tile (x0, y0) of the level."""
    w, h = img.size
    for j in range(h // TILE):
        for i in range(w // TILE):
            box = (i * TILE, j * TILE, (i + 1) * TILE, (j + 1) * TILE)
            pack.add(level, x0 + i, y0 + j, img.crop(box))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cache", default="third_party/earth")
    ap.add_argument("--out", default="assets-build")
    ap.add_argument("--layer", choices=sorted(LAYERS), default="day")
    ap.add_argument("--max-level", type=int, default=MAX_LEVEL,
                    help="top level, 1-6 (default %d)" % MAX_LEVEL)
    args = ap.parse_args()
    top = args.max_level
    if not 1 <= top <= 6:
        sys.exit("--max-level must be 1 to 6")
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)

    layer = LAYERS[args.layer]
    paths = {p: fetch(args.cache, layer, p) for p in PIECES}
    pack = Pack(os.path.join(args.out, layer["out"]))

    # Each piece is 90 x 90 degrees: a quarter of the width and half the
    # height. At level L it is 2^(L-1) tiles square, so levels 1 to top are
    # cut piece by piece; only level 0 (the whole Earth in 2 x 1 tiles)
    # needs the pieces put together. That keeps memory to about one source
    # piece, even at level 6 (16384 px a piece).
    level0 = Image.new("RGB", (2 * TILE, TILE))
    for n, piece in enumerate(PIECES):
        col, row = "ABCD".index(piece[0]), int(piece[1]) - 1
        print("piece %s (%d of 8)" % (piece, n + 1), flush=True)
        side = TILE << (top - 1)
        img = Image.open(paths[piece])
        # Decode at a reduced size straight from the JPEG when that is still
        # at least the size needed: a fraction of the time and memory.
        scale = 1
        while scale < 8 and 21600 // (scale * 2) >= side:
            scale *= 2
        img.draft("RGB", (21600 // scale, 21600 // scale))
        img = img.convert("RGB")
        level = top
        while level >= 1:
            side = TILE << (level - 1)
            img = img.resize((side, side), Image.LANCZOS)
            cut(pack, level, img, col * (side // TILE), row * (side // TILE))
            level -= 1
        level0.paste(img.resize((TILE // 2, TILE // 2), Image.LANCZOS),
                     (col * TILE // 2, row * TILE // 2))
        del img
    cut(pack, 0, level0, 0, 0)
    pack.close()
    print("wrote %s: %d tiles, %.1f MB" % (pack.f.name, len(pack.index),
                                          os.path.getsize(pack.f.name) / 1e6))


if __name__ == "__main__":
    main()
