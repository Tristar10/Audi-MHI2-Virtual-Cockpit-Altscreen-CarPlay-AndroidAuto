#!/usr/bin/env python3
"""Build a hook-first dio_manager for MU1462 from the exact stock file.

Unlike the MU1438 delta, which moved one library to the end, this keeps every
stock library in its original relative order: the hook takes slot 0, the rest
shift down one, and the unused RPATH slot becomes the last NEEDED.

usage: patch_dio_mu1462.py stock output
"""
import hashlib
import struct
import sys

STOCK_SHA256 = "1974dbd95df5c33f81da4d8cf2c77b791e5a6466aa65c7d1912ec9ebd8c140e3"
DYN = 682504          # file offset of the dynamic table
NEEDED_COUNT = 18
STRTAB = 22664
RPATH_VAL = 13842
DT_NEEDED, DT_RPATH = 1, 15
HOOK = b"libaltscreen111.so\0"


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    if hashlib.sha256(data).hexdigest() != STOCK_SHA256:
        sys.exit("refusing: input is not the stock MU1462 dio_manager")

    entries = [struct.unpack_from("<II", data, DYN + i * 8) for i in range(NEEDED_COUNT + 1)]
    if any(t != DT_NEEDED for t, _ in entries[:NEEDED_COUNT]):
        sys.exit("refusing: expected %d NEEDED entries" % NEEDED_COUNT)
    if entries[NEEDED_COUNT] != (DT_RPATH, RPATH_VAL):
        sys.exit("refusing: RPATH entry not where expected")

    at = STRTAB + RPATH_VAL
    old = bytes(data[at:at + len(HOOK)])
    if not old.startswith(b"/home/jenkins/workspace"[:len(HOOK)]):
        sys.exit("refusing: RPATH string not as expected: %r" % old)
    data[at:at + len(HOOK)] = HOOK

    new = [(DT_NEEDED, RPATH_VAL)] + [(DT_NEEDED, v) for _, v in entries[:NEEDED_COUNT]]
    for i, (tag, val) in enumerate(new):
        struct.pack_into("<II", data, DYN + i * 8, tag, val)

    with open(dst, "xb") as f:
        f.write(data)
    print("written", dst, len(data), hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
