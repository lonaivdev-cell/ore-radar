"""xwd2png.py IN.xwd OUT.png -- stdlib-only XWD (ZPixmap, 24/32 bpp TrueColor) to PNG."""
import struct, sys, zlib

def main(src, dst):
    data = open(src, 'rb').read()
    hdr_size, = struct.unpack('>I', data[:4])
    endian = '>' if hdr_size < 0x10000 else '<'
    f = struct.unpack(endian + '25I', data[:100])
    (hdr_size, version, fmt, depth, width, height, xoff, byte_order, unit, bit_order, pad,
     bpp, bpl, vclass, rmask, gmask, bmask, bits_rgb, cmap_entries, ncolors) = f[:20]
    if fmt != 2 or bpp not in (24, 32):
        sys.exit(f'unsupported XWD: format={fmt} bpp={bpp}')
    off = hdr_size + ncolors * 12
    pix = data[off:off + bpl * height]
    if len(pix) < bpl * height:
        sys.exit('truncated XWD')
    shifts = []
    for m in (rmask, gmask, bmask):
        s = (m & -m).bit_length() - 1
        shifts.append((s, m >> s))
    bpx = bpp // 8
    order = 'little' if byte_order == 0 else 'big'
    raw = bytearray()
    fast = bpx == 4 and order == 'little' and (rmask, gmask, bmask) == (0xff0000, 0xff00, 0xff)
    for y in range(height):
        row = pix[y * bpl:y * bpl + width * bpx]
        raw.append(0)
        if fast:  # BGRX -> RGB
            out = bytearray(width * 3)
            out[0::3] = row[2::4]; out[1::3] = row[1::4]; out[2::3] = row[0::4]
            raw += out
        else:
            for x in range(width):
                v = int.from_bytes(row[x * bpx:(x + 1) * bpx], order)
                raw += bytes(((v >> s) & m) * 255 // m for s, m in shifts)
    def chunk(t, b):
        return struct.pack('>I', len(b)) + t + b + struct.pack('>I', zlib.crc32(t + b) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(raw), 6)) + chunk(b'IEND', b''))
    open(dst, 'wb').write(png)
    print(f'{dst} {width}x{height}')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
