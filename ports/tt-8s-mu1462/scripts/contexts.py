#!/usr/bin/env python3
"""Turn javap output of defineContexts into a context -> displayables table.

usage: contexts.py dc.txt
"""
import re
import sys

PUSH = re.compile(r"\d+: (iconst_m1|iconst_(\d)|bipush\s+(-?\d+)|sipush\s+(-?\d+))")


def push_value(line):
    m = PUSH.search(line)
    if not m:
        return None
    if m.group(1) == "iconst_m1":
        return -1
    for g in (2, 3, 4):
        if m.group(g) is not None:
            return int(m.group(g))
    return None


def main(path):
    table = {}
    pushes = []
    collecting = False
    idx = None
    for line in open(path, encoding="utf-8", errors="replace"):
        v = push_value(line)
        if v is not None:
            pushes.append(v)
            continue
        if "new " in line and "DisplayContext" in line and "anewarray" not in line:
            idx = pushes[-1] if pushes else None
            pushes = []
            collecting = True
            continue
        if collecting and "invokespecial" in line and "DisplayContext" in line:
            # pushes: id, length, then (index, value) pairs
            cid, n = pushes[0], pushes[1]
            vals = pushes[3::2][:n]
            table[idx] = vals
            collecting = False
            pushes = []
    for k in sorted(table):
        print("%3d: %s" % (k, table[k]))
    print("contexts:", len(table))
    for name, did in (("smartphone 59", 59), ("mapviewer 19", 19), ("route tile 20", 20)):
        print(name, "in", [k for k in sorted(table) if did in table[k]])


if __name__ == "__main__":
    main(sys.argv[1])
