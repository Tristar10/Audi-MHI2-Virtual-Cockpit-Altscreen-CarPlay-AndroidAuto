#!/usr/bin/env python3
"""Assemble the MU1462 stage-1 payload. usage: assemble.py scratch outdir"""
import hashlib
import os
import struct
import sys

S, OUT = sys.argv[1], sys.argv[2]
sha = lambda b: hashlib.sha256(b).hexdigest()
read = lambda p: open(p, "rb").read()
os.makedirs(OUT, exist_ok=True)
A = S + "/dl_aberbic/src/carplay/bin/"

# The renderer ships with output device 1; this firmware sets
# NVSS_VIDEO_OUTPUT_DEVICE=0, and the car has one display.
rt = bytearray(read(A + "cluster_runtime"))
assert sha(rt) == "dd80861e6d7587a85338e9c241dfc032fd3a593b333499c2db85b84fc3973b87"
OLD = struct.pack("<II", 1, 0) + bytes([100, 1, 60, 0]) + struct.pack("<III", 8, 0, 0)
assert rt.count(OLD) == 1 and rt.find(OLD) == 0x21e0
rt[0x21e0:0x21e4] = struct.pack("<I", 0)

# Declare the TT context: MMI layer over the video layer, like stock context 10.
sup = read(A + "cluster_supervisor.sh")
assert sha(sup) == "10401cfb088bf1cb57652f1489e43af7b56796c46ffbc3aada9b95a17ea4beec"
MARK = b"child_pid=\ntrap"
assert sup.count(MARK) == 1 and b"\r" not in sup
ADD = (b"# TT single display: HMI (16) stays above the CarPlay map (60).\n"
       b"i=0\n"
       b"while test \"$i\" -lt 10; do\n"
       b"    /eso/bin/apps/dmdt dc 901 16 60 >/dev/null 2>&1 && break\n"
       b"    i=$((i + 1))\n"
       b"    sleep 1\n"
       b"done\n")
sup = sup.replace(MARK, ADD + MARK)

files = {
    "altscreen-dio_manager": read(S + "/port/dio_manager.mu1462.patched"),
    "altscreen-libaltscreen111.so": read(A + "libaltscreen111.so"),
    "altscreen-cluster_runtime": bytes(rt),
    "altscreen-cluster_supervisor.sh": sup,
    "altscreen-sha256": read(S + "/dl_aberbic/components/altscreen-sha256"),
}
assert sha(files["altscreen-libaltscreen111.so"]) == "9a8c01ec8323d5de7695e212ff6168b0bb7df99d8554c9971f5449c5a21d6256"
assert sha(files["altscreen-sha256"]) == "c5609239ca978c0e29926c879d8ea3459b0ca29e6691cc72ad484feda570da9d"

t = read(S + "/port/install.template.sh").decode("ascii")
assert "\r" not in t
sub = {
    "STOCK_JXE": sha(read(S + "/fw/50/ifs/lsd.jxe")),
    "STOCK_AIRPLAY": sha(read(S + "/fw/50/app/libairplay.so")),
    "STOCK_IAP2": sha(read(S + "/fw/50/app/ipod-drvr-iap2.so")),
    "STOCK_DIO": sha(read(S + "/fw/50/app/dio_manager")),
    "NEW_DIO": sha(files["altscreen-dio_manager"]),
    "NEW_LIB": sha(files["altscreen-libaltscreen111.so"]),
    "NEW_RT": sha(files["altscreen-cluster_runtime"]),
    "NEW_SUP": sha(files["altscreen-cluster_supervisor.sh"]),
}
for k, v in sub.items():
    t = t.replace("@@%s@@" % k, v)
assert "@@" not in t
files["altscreen-mu1462-install.sh"] = t.encode("ascii")

for name, blob in files.items():
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(blob)
    print("%-36s %8d %s" % (name, len(blob), sha(blob)))
for k, v in sub.items():
    print(k, v)
