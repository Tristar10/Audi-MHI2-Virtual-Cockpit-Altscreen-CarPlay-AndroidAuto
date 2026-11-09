#!/usr/bin/env python3
"""Walk a QNX6 filesystem image and extract named files. Read-only on the input.

usage: qnx6x.py image outdir name [name ...]
Writes a full listing to outdir/qnx6_listing.txt.
"""
import hashlib
import os
import struct
import sys

MAGIC = 0x68191122
SB_OFF = 0x2000
DATA_OFF = 0x3000


class FS:
    def __init__(self, path):
        self.f = open(path, "rb")
        self.f.seek(0, 2)
        self.size = self.f.tell()
        sb = self.read(SB_OFF, 512)
        magic = struct.unpack_from("<I", sb, 0)[0]
        if magic != MAGIC:
            raise SystemExit("bad superblock magic %x" % magic)
        self.bs = struct.unpack_from("<I", sb, 48)[0]
        self.num_inodes, _, self.num_blocks = struct.unpack_from("<III", sb, 52)
        second = DATA_OFF + self.num_blocks * self.bs
        if second + 512 <= self.size:
            sb2 = self.read(second, 512)
            if struct.unpack_from("<I", sb2, 0)[0] == MAGIC:
                if struct.unpack_from("<Q", sb2, 8)[0] > struct.unpack_from("<Q", sb, 8)[0]:
                    sb = sb2
                    print("using second superblock")
        print("blocksize", self.bs, "inodes", self.num_inodes, "blocks", self.num_blocks)
        self.inode_root = self.root_node(sb, 72)
        self.long_root = self.root_node(sb, 232)

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def block(self, n):
        return self.read(DATA_OFF + n * self.bs, self.bs)

    @staticmethod
    def root_node(sb, off):
        size = struct.unpack_from("<Q", sb, off)[0]
        ptrs = struct.unpack_from("<16I", sb, off + 8)
        levels = sb[off + 72]
        return size, ptrs, levels

    def walk_ptrs(self, ptrs, levels):
        """Yield data block numbers in file order. 0xffffffff is unallocated."""
        per = self.bs // 4
        if levels == 0:
            for p in ptrs:
                yield p
            return
        for p in ptrs:
            if p == 0xffffffff:
                continue
            table = struct.unpack("<%dI" % per, self.block(p))
            for b in self.walk_ptrs(table, levels - 1):
                yield b

    def blocks_for(self, size, ptrs, levels):
        need = (size + self.bs - 1) // self.bs
        out = []
        for b in self.walk_ptrs(ptrs, levels):
            if len(out) >= need:
                break
            out.append(b)
        return out

    def data(self, size, ptrs, levels):
        out = bytearray()
        for b in self.blocks_for(size, ptrs, levels):
            out += b"\0" * self.bs if b == 0xffffffff else self.block(b)
        return bytes(out[:size])

    def inode_table(self):
        size, ptrs, levels = self.inode_root
        self.inode_blocks = self.blocks_for(size, ptrs, levels)

    def inode(self, ino):
        i = (ino - 1) * 128
        b = self.inode_blocks[i // self.bs]
        raw = self.read(DATA_OFF + b * self.bs + i % self.bs, 128)
        size = struct.unpack_from("<Q", raw, 0)[0]
        mode = struct.unpack_from("<H", raw, 32)[0]
        ptrs = struct.unpack_from("<16I", raw, 36)
        levels = raw[100]
        return size, mode, ptrs, levels

    def long_name(self, idx):
        size, ptrs, levels = self.long_root
        blocks = self.blocks_for(size, ptrs, levels)
        raw = self.block(blocks[idx])
        n = struct.unpack_from("<H", raw, 0)[0]
        return raw[2:2 + n].decode("latin1")

    def entries(self, ino):
        size, mode, ptrs, levels = self.inode(ino)
        raw = self.data(size, ptrs, levels)
        for off in range(0, len(raw) - 31, 32):
            child = struct.unpack_from("<I", raw, off)[0]
            if child == 0:
                continue
            n = raw[off + 4]
            if n <= 27:
                name = raw[off + 5:off + 5 + n].decode("latin1")
            else:
                name = self.long_name(struct.unpack_from("<I", raw, off + 8)[0])
            if name in (".", ".."):
                continue
            yield child, name


def main(path, outdir, wanted):
    os.makedirs(outdir, exist_ok=True)
    fs = FS(path)
    fs.inode_table()
    stack = [(1, "")]
    seen = set()
    lines = []
    while stack:
        ino, prefix = stack.pop()
        if ino in seen:
            continue
        seen.add(ino)
        for child, name in fs.entries(ino):
            size, mode, ptrs, levels = fs.inode(child)
            full = prefix + "/" + name
            fmt = mode & 0o170000
            if fmt == 0o040000:
                stack.append((child, full))
            elif fmt == 0o100000:
                head = b""
                if size:
                    head = fs.data(min(size, fs.bs), ptrs, levels)[:8] if levels == 0 else b""
                    if levels:
                        head = fs.block(fs.blocks_for(size, ptrs, levels)[0])[:8]
                lines.append("%10d %s %s" % (size, head[:8].hex(), full))
                if name in wanted:
                    blob = fs.data(size, ptrs, levels)
                    dest = os.path.join(outdir, name)
                    with open(dest, "wb") as f:
                        f.write(blob)
                    print("EXTRACTED", full, size, head[:8], hashlib.sha256(blob).hexdigest())
    lines.sort(key=lambda s: s.split(" ")[-1])
    with open(os.path.join(outdir, "qnx6_listing.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("files listed:", len(lines))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], set(sys.argv[3:]))
