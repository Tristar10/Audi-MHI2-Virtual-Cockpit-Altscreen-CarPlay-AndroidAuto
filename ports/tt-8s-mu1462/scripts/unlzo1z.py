#!/usr/bin/env python3
"""Decompress an MHI2 'LZOZ' (LZO1Z) image. usage: unlzo1z.py in out"""
import struct, sys

def d1z(src, max_out):
    out = bytearray(); ip = 0; last = 0; state = 0
    t = src[0]
    if t > 17:
        ip = 1; t -= 17
        out += src[ip:ip+t]; ip += t
        state = 4 if t >= 4 else t
    while True:
        t = src[ip]; ip += 1
        if t >= 64:
            off = t & 0x1f
            if off >= 0x1c:
                dist = last; lit = t & 3
            else:
                b = src[ip]; ip += 1
                dist = 1 + (off << 6) + (b >> 2); last = dist; lit = b & 3
            length = (t >> 5) + 1
        elif t >= 32:
            length = t & 31
            if length == 0:
                while src[ip] == 0: length += 255; ip += 1
                length += 31 + src[ip]; ip += 1
            length += 2
            a, b = src[ip], src[ip+1]; ip += 2
            dist = 1 + (a << 6) + (b >> 2); last = dist; lit = b & 3
        elif t >= 16:
            high = (t & 8) << 11
            length = t & 7
            if length == 0:
                while src[ip] == 0: length += 255; ip += 1
                length += 7 + src[ip]; ip += 1
            length += 2
            a, b = src[ip], src[ip+1]; ip += 2
            d = high + (a << 6) + (b >> 2)
            if d == 0:
                return bytes(out), ip
            dist = d + 0x4000; last = dist; lit = b & 3
        else:
            if state == 0:
                length = t
                if length == 0:
                    while src[ip] == 0: length += 255; ip += 1
                    length += 15 + src[ip]; ip += 1
                length += 3
                out += src[ip:ip+length]; ip += length
                state = 4
                continue
            b = src[ip]; ip += 1
            if state == 4:
                dist = 0x701 + (t << 6) + (b >> 2); length = 3
            else:
                dist = 1 + (t << 6) + (b >> 2); length = 2
            last = dist; lit = b & 3
        pos = len(out) - dist
        if pos < 0: raise ValueError("lookbehind at out=%d dist=%d" % (len(out), dist))
        if dist >= length:
            out += out[pos:pos+length]
        else:
            for i in range(length): out.append(out[pos+i])
        if lit:
            out += src[ip:ip+lit]; ip += lit
        state = lit
        if len(out) > max_out: raise ValueError("output overrun")

data = open(sys.argv[1], 'rb').read()
chunk = struct.unpack_from('<I', data, 4)[0]
sizes = []
o = 8
while True:
    v = struct.unpack_from('<I', data, o)[0]; o += 4
    if v == 0: break
    sizes.append(v)
pos = 2048; total = 0
with open(sys.argv[2], 'wb') as f:
    for i, s in enumerate(sizes):
        b, used = d1z(data[pos:pos+s], chunk)
        if used != s: print('chunk', i, 'used', used, 'of', s)
        f.write(b); total += len(b); pos += s
        while pos < len(data) and data[pos] == 0xff: pos += 1
print('chunks', len(sizes), 'output', total, 'end', hex(pos))
