#!/usr/bin/env python3
"""List a flat QNX IFS image and extract named files. Read-only on the input.

usage: ifsx.py image outdir name [name ...]
"""
import hashlib
import os
import struct
import sys


def main(path, outdir, wanted):
    data = open(path, "rb").read()
    base = data.find(b"imagefs")
    if base < 0:
        raise SystemExit("no imagefs signature")
    flags = data[base + 7]
    image_size, hdr_dir_size, dir_offset = struct.unpack_from("<III", data, base + 8)
    print("imagefs at", hex(base), "flags", hex(flags), "image_size", image_size,
          "hdr_dir_size", hdr_dir_size, "dir_offset", hex(dir_offset))
    off = base + dir_offset
    count = 0
    names = []
    while True:
        size = struct.unpack_from("<H", data, off)[0]
        if size == 0:
            break
        mode = struct.unpack_from("<I", data, off + 8)[0]
        fmt = mode & 0o170000
        if fmt == 0o100000:
            f_off, f_size = struct.unpack_from("<II", data, off + 24)
            name = data[off + 32:off + size].split(b"\0")[0].decode("latin1")
            names.append((name, f_size))
            if os.path.basename(name) in wanted:
                blob = data[base + f_off:base + f_off + f_size]
                dest = os.path.join(outdir, os.path.basename(name))
                os.makedirs(outdir, exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(blob)
                print("EXTRACTED", name, f_size, hashlib.sha256(blob).hexdigest())
        count += 1
        off += size
    print("files:", len(names), "entries:", count)
    with open(os.path.join(outdir, "ifs_listing.txt"), "w", encoding="utf-8") as f:
        for n, s in sorted(names):
            f.write("%10d %s\n" % (s, n))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], set(sys.argv[3:]))
