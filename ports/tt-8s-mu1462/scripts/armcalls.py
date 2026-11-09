#!/usr/bin/env python3
"""Find ARM BL calls to a PLT stub by symbol and dump the preceding instructions.
usage: armcalls.py elfinfo.py binary symbol"""
import sys, struct, importlib.util
spec = importlib.util.spec_from_file_location('elfinfo', sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
e = m.Elf(sys.argv[2]); d = e.d; sym = sys.argv[3]
names = [s[0] for s in e.syms()]
if sym not in names: sys.exit('no symbol')
idx = names.index(sym)
# Find the JMPREL entry for this symbol, then its GOT slot
jmprel = e.v2o(e.tags[23]); sz = e.tags[2]
got = None
for i in range(0, sz, 8):
    off, info = struct.unpack_from('<II', d, jmprel + i)
    if info >> 8 == idx: got = off
print('GOT slot', hex(got))
# Scan text for PLT stub (add ip,pc,#.. / add ip,ip,#.. / ldr pc,[ip,#..]!) resolving to the slot
text = max(e.loads, key=lambda l: l[2] if l[1] == 0 else 0)
va0, o0, n = text
plt = None
for o in range(o0, o0 + n - 12, 4):
    a, b, c = struct.unpack_from('<III', d, o)
    if (a & 0xfffff000) == 0xe28fc000 and (b & 0xfffff000) == 0xe28cc000 and (c & 0xfffff000) == 0xe5bcf000:
        def rot(x): r = ((x >> 8) & 15) * 2; v = x & 255; return ((v >> r) | (v << (32 - r))) & 0xffffffff if r else v
        va = o - o0 + va0
        if va + 8 + rot(a) + rot(b) + (c & 0xfff) == got: plt = va
print('PLT', hex(plt) if plt else None)
for o in range(o0, o0 + n - 4, 4):
    w = struct.unpack_from('<I', d, o)[0]
    if (w & 0xff000000) == 0xeb000000:
        off = w & 0xffffff
        if off & 0x800000: off -= 1 << 24
        va = o - o0 + va0
        if va + 8 + off * 4 == plt:
            print('call at', hex(va))
            for k in range(-34, 1):
                ww = struct.unpack_from('<I', d, o + k * 4)[0]
                print('  %x: %08x' % (va + k * 4, ww))
