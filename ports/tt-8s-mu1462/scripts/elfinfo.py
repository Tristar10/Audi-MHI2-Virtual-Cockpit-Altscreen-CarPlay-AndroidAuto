#!/usr/bin/env python3
"""Minimal ELF32-LE dynamic-section reader. Read-only.

usage: elfinfo.py file            print NEEDED, RPATH, SONAME, section summary
       elfinfo.py file --undef    print undefined dynamic symbols
       elfinfo.py file --def      print defined dynamic symbols
"""
import struct
import sys

PT_LOAD, PT_DYNAMIC = 1, 2
DT = {1: "NEEDED", 5: "STRTAB", 6: "SYMTAB", 10: "STRSZ", 14: "SONAME", 15: "RPATH",
      29: "RUNPATH", 4: "HASH"}


class Elf:
    def __init__(self, path):
        self.d = open(path, "rb").read()
        d = self.d
        if d[:4] != b"\x7fELF" or d[4] != 1:
            raise SystemExit("not ELF32: %r" % d[:8])
        phoff = struct.unpack_from("<I", d, 28)[0]
        phsize, phnum = struct.unpack_from("<HH", d, 42)
        self.loads = []
        self.dyn = None
        for i in range(phnum):
            t, off, va, pa, fsz, msz, fl, al = struct.unpack_from("<8I", d, phoff + i * phsize)
            if t == PT_LOAD:
                self.loads.append((va, off, fsz))
            elif t == PT_DYNAMIC:
                self.dyn = (off, fsz)
        self.entries = []
        off, size = self.dyn
        for i in range(0, size, 8):
            tag, val = struct.unpack_from("<II", d, off + i)
            self.entries.append((off + i, tag, val))
            if tag == 0:
                break
        self.tags = {}
        for _, t, v in self.entries:
            self.tags.setdefault(t, v)
        self.stroff = self.v2o(self.tags[5])

    def v2o(self, va):
        for v, o, s in self.loads:
            if v <= va < v + s:
                return va - v + o
        raise ValueError(hex(va))

    def s(self, i):
        o = self.stroff + i
        return self.d[o:self.d.index(b"\0", o)].decode("latin1")

    def symcount(self):
        if 4 in self.tags:
            return struct.unpack_from("<I", self.d, self.v2o(self.tags[4]) + 4)[0]
        return (self.stroff - self.v2o(self.tags[6])) // 16

    def syms(self):
        o = self.v2o(self.tags[6])
        for i in range(self.symcount()):
            name, val, size, info, other, shndx = struct.unpack_from("<IIIBBH", self.d, o + i * 16)
            yield self.s(name), val, size, info, shndx


def main():
    e = Elf(sys.argv[1])
    mode = sys.argv[2] if len(sys.argv) > 2 else ""
    if mode == "--undef":
        for n, v, s, i, x in e.syms():
            if n and x == 0:
                print(n)
    elif mode == "--def":
        for n, v, s, i, x in e.syms():
            if n and x != 0:
                print(n)
    else:
        for off, t, v in e.entries:
            if t in (1, 14, 15, 29):
                print("%-8s file+%d strtab+%d %s" % (DT[t], off, v, e.s(v)))
        print("dynamic entries:", len(e.entries), "at file offset", e.dyn[0], "strtab file offset", e.stroff, "symbols", e.symcount())


if __name__ == "__main__":
    main()
