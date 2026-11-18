"""Deterministic jar: fixed timestamps, sorted entries, so the same input always gives the same sha256.
usage: python3 -I mkjar.py <class dir> <out jar>   (prints the sha256)"""
import hashlib
import os
import sys
import zipfile

src, out = sys.argv[1], sys.argv[2]
names = sorted(os.path.relpath(os.path.join(r, f), src).replace(os.sep, '/')
               for r, _, fs in os.walk(src) for f in fs)
with zipfile.ZipFile(out, 'w') as z:
    zi = zipfile.ZipInfo('META-INF/MANIFEST.MF', (2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    z.writestr(zi, b'Manifest-Version: 1.0\r\n\r\n')
    for n in names:
        zi = zipfile.ZipInfo(n, (2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, open(os.path.join(src, *n.split('/')), 'rb').read())
print(hashlib.sha256(open(out, 'rb').read()).hexdigest())
