#!/usr/bin/env python3
"""Makes the small JPEG fixtures for ExifTest and PhotosTest (and a WebP,
for ThumbsTest): each a tiny
image (Pillow) with an EXIF segment written here by hand, so each case has
exactly the tags it tests, in either byte order.

    python3 make-fixtures.py OUT_DIR

website-exif.json is then what the website's readExif (app/atproto/exif.ts)
gives for each, run in Node with TZ=America/Toronto (see ExifTest).
"""
import io
import struct
import sys

from PIL import Image

ASCII, SHORT, LONG, RATIONAL = 2, 3, 4, 5
SIZE = {ASCII: 1, SHORT: 2, LONG: 4, RATIONAL: 8}


def tiff(ifd0, exif=None, gps=None, le=True):
    """A TIFF structure: IFD0 (with pointers to the Exif and GPS IFDs when
    given), each IFD's out-of-line values after it."""
    e = '<' if le else '>'
    ifds = [list(ifd0)]
    if exif is not None:
        ifds[0].append((0x8769, LONG, ['EXIF']))
        ifds.append(list(exif))
    if gps is not None:
        ifds[0].append((0x8825, LONG, ['GPS']))
        ifds.append(list(gps))

    def value(t, v):
        if t == ASCII:
            return v.encode('latin-1') + b'\0', len(v) + 1
        if t == SHORT:
            return b''.join(struct.pack(e + 'H', x) for x in v), len(v)
        if t == LONG:
            return b''.join(struct.pack(e + 'I', x if isinstance(x, int) else 0) for x in v), len(v)
        if t == RATIONAL:
            return b''.join(struct.pack(e + 'II', n, d) for n, d in v), len(v)

    # Lay out: each IFD then its data; offsets from the TIFF header.
    sizes = []
    for ifd in ifds:
        data = sum(len(value(t, v)[0]) for _, t, v in ifd if len(value(t, v)[0]) > 4)
        sizes.append(2 + 12 * len(ifd) + 4 + data)
    starts = [8]
    for s in sizes[:-1]:
        starts.append(starts[-1] + s)
    where = {'EXIF': starts[1] if exif is not None else 0,
             'GPS': starts[-1] if gps is not None else 0}

    out = bytearray(b'II*\0' if le else b'MM\0*')
    out += struct.pack(e + 'I', 8)
    for ifd, start in zip(ifds, starts):
        ifd = sorted(ifd, key=lambda x: x[0])
        head = bytearray(struct.pack(e + 'H', len(ifd)))
        data = bytearray()
        data_at = start + 2 + 12 * len(ifd) + 4
        for tag, t, v in ifd:
            v = [where[x] if isinstance(x, str) else x for x in v] if t == LONG else v
            raw, count = value(t, v)
            head += struct.pack(e + 'HHI', tag, t, count)
            if len(raw) <= 4:
                head += raw.ljust(4, b'\0')
            else:
                head += struct.pack(e + 'I', data_at + len(data))
                data += raw
        head += struct.pack(e + 'I', 0)
        out += head + data
    return bytes(out)


def app1(t):
    body = b'Exif\0\0' + t
    return b'\xff\xe1' + struct.pack('>H', len(body) + 2) + body


def jpeg(segment, w=32, h=24, colour=(40, 120, 60)):
    img = Image.new('RGB', (w, h), colour)
    for x in range(w // 2):
        for y in range(h // 2):
            img.putpixel((x, y), (220, 30, 30))
    b = io.BytesIO()
    img.save(b, 'JPEG', quality=80)
    data = b.getvalue()
    assert data[2:4] == b'\xff\xe0'
    rest = data[4 + struct.unpack('>H', data[4:6])[0]:]
    # As a camera writes it: SOI, then APP1 (no JFIF).
    return b'\xff\xd8' + (segment or b'') + rest


def dms(deg):
    d = int(deg)
    m = int((deg - d) * 60)
    s = round(((deg - d) * 60 - m) * 60 * 10000)
    return [(d, 1), (m, 1), (s, 10000)]


CASES = {
    # GPS (N, W), DateTimeOriginal with OffsetTimeOriginal, GPS time too,
    # Orientation 6 (rotated: drawn 90° clockwise).
    'gps-offset.jpg': app1(tiff(
        [(0x0112, SHORT, [6]), (0x010F, ASCII, 'Pixel')],
        exif=[(0x9003, ASCII, '2026:07:02 10:15:30'), (0x9011, ASCII, '-07:00')],
        gps=[(1, ASCII, 'N'), (2, RATIONAL, dms(47.4875)), (3, ASCII, 'W'), (4, RATIONAL, dms(121.7234)),
             (7, RATIONAL, [(17, 1), (15, 1), (3000, 100)]), (0x1d, ASCII, '2026:07:02')],
    )),
    # Big-endian; GPS (S, E); local time without offset: GPS time wins.
    'gps-time-be.jpg': app1(tiff(
        [(0x0112, SHORT, [1])],
        exif=[(0x9003, ASCII, '2026:01:15 08:00:00')],
        gps=[(1, ASCII, 'S'), (2, RATIONAL, dms(33.8568)), (3, ASCII, 'E'), (4, RATIONAL, dms(151.2153)),
             (7, RATIONAL, [(21, 1), (0, 1), (125, 10)]), (0x1d, ASCII, '2026:01:14')],
        le=False,
    )),
    # DateTimeOriginal only: local time, read in the phone's zone.
    'local.jpg': app1(tiff(
        [(0x0112, SHORT, [3])],
        exif=[(0x9003, ASCII, '2026:03:08 02:30:00')],
    )),
    # No Exif IFD: IFD0's DateTime, in the phone's zone.
    'ifd0-datetime.jpg': app1(tiff([(0x0132, ASCII, '2026:11:01 14:05:09')])),
    # GPS time alone (no DateTime at all).
    'gps-time-only.jpg': app1(tiff(
        [],
        gps=[(7, RATIONAL, [(5, 1), (6, 1), (7, 1)]), (0x1d, ASCII, '2026:08:20')],
    )),
    # GPS at exactly 0, 0: no fix, not the Gulf of Guinea.
    'zero-gps.jpg': app1(tiff(
        [], exif=[(0x9003, ASCII, '2026:05:05 12:00:00'), (0x9011, ASCII, '+02:00')],
        gps=[(1, ASCII, 'N'), (2, RATIONAL, [(0, 1), (0, 1), (0, 1)]), (3, ASCII, 'E'), (4, RATIONAL, [(0, 1), (0, 1), (0, 1)])],
    )),
    # No EXIF at all.
    'no-exif.jpg': None,
    # Orientation 8 (drawn turned a quarter anticlockwise) and nothing else:
    # its aspectRatio swaps width and height.
    'orientation-8.jpg': app1(tiff([(0x0112, SHORT, [8])])),
}


def main(out):
    for name, seg in CASES.items():
        with open(f'{out}/{name}', 'wb') as f:
            f.write(jpeg(seg))
    # Broken: IFD0 points past the segment's end. Orientation can't be read
    # either; the segment is kept as it is.
    t = bytearray(tiff([(0x0112, SHORT, [8])]))
    t[4:8] = struct.pack('<I', 5000)
    with open(f'{out}/broken.jpg', 'wb') as f:
        f.write(jpeg(app1(bytes(t))))
    # A WebP, as the indexer's thumbnails are (getPhoto): 40 x 30.
    img = Image.new('RGB', (40, 30), (40, 120, 60))
    img.save(f'{out}/thumbnail.webp', 'WEBP', quality=80)


if __name__ == '__main__':
    main(sys.argv[1])
