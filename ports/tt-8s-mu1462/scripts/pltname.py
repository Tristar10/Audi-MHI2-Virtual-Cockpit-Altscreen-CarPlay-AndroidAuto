#!/usr/bin/env python3
"""Name the PLT targets of ARM BL instructions and print string literals loaded nearby.
usage: pltname.py elfinfo.py binary startva endva"""
import sys, struct, importlib.util
spec = importlib.util.spec_from_file_location('elfinfo', sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
e = m.Elf(sys.argv[2]); d = e.d
names = [s[0] for s in e.syms()]
jmprel = e.v2o(e.tags[23]); sz = e.tags[2]
got2 = {}
for i in range(0, sz, 8):
    off, info = struct.unpack_from('<II', d, jmprel + i)
    got2[off] = names[info >> 8]
va0, o0, n = max(e.loads, key=lambda l: l[2] if l[1] == 0 else 0)
def rot(x):
    r = ((x >> 8) & 15) * 2; v = x & 255
    return ((v >> r) | (v << (32 - r))) & 0xffffffff if r else v
plt = {}
for o in range(o0, o0 + n - 12, 4):
    a, b, c = struct.unpack_from('<III', d, o)
    if (a & 0xfffff000) == 0xe28fc000 and (b & 0xfffff000) == 0xe28cc000 and (c & 0xfffff000) == 0xe5bcf000:
        va = o - o0 + va0; plt[va] = got2.get(va + 8 + rot(a) + rot(b) + (c & 0xfff))
for va in range(int(sys.argv[3], 16), int(sys.argv[4], 16), 4):
    w = struct.unpack_from('<I', d, va - va0 + o0)[0]
    s = '%x: %08x' % (va, w)
    if (w & 0xff000000) == 0xeb000000:
        off = w & 0xffffff
        if off & 0x800000: off -= 1 << 24
        t = va + 8 + off * 4; s += '  bl %x %s' % (t, plt.get(t, ''))
    elif (w & 0xffff0000) == 0xe59f0000 or (w & 0xfff00000) == 0xe5900000 and (w >> 16 & 15) == 15:
        pass
    if (w & 0x0fff0000) == 0x059f0000:
        lit = va + 8 + (w & 0xfff); v = struct.unpack_from('<I', d, lit - va0 + o0)[0]; s += '  ldr r%d,=%#x' % ((w >> 12) & 15, v)
    print(s)
