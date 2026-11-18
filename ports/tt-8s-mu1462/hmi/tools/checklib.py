"""Checks that our classes only call java.* methods/fields that exist in the car's class library.

usage: python -I checklib.py <out dir> <hmix dir> <class names...>
The car runs J9 with a Java 1.4-era library (extracted to hmix/java/...). javac --release 8
happily links against newer methods (StringBuilder, String.isEmpty, ...) that would throw
NoSuchMethodError on the car, so every java/* reference is resolved here, walking superclasses
and interfaces. Exit code 1 on any unresolved reference.
"""
import os
import struct
import sys


def parse(path):
    b = open(path, 'rb').read()
    n = struct.unpack('>H', b[8:10])[0]
    cp = [None] * n
    i, p = 1, 10
    while i < n:
        t = b[p]
        if t == 1:
            ln = struct.unpack('>H', b[p + 1:p + 3])[0]
            cp[i] = ('utf', b[p + 3:p + 3 + ln].decode('utf-8', 'replace'))
            p += 3 + ln
        elif t in (3, 4):
            p += 5
        elif t in (5, 6):
            p += 9
            i += 1
        elif t == 7:
            cp[i] = ('class', struct.unpack('>H', b[p + 1:p + 3])[0]); p += 3
        elif t == 8:
            p += 3
        elif t in (9, 10, 11):
            cp[i] = ({9: 'field', 10: 'method', 11: 'imethod'}[t],) + struct.unpack('>HH', b[p + 1:p + 5]); p += 5
        elif t == 12:
            cp[i] = ('nat',) + struct.unpack('>HH', b[p + 1:p + 5]); p += 5
        elif t == 15:
            p += 4
        elif t == 16:
            p += 3
        elif t == 18:
            p += 5
        else:
            raise ValueError('cp tag %d in %s' % (t, path))
        i += 1
    u = lambda k: cp[k][1]
    cname = lambda k: u(cp[k][1])
    access, this, sup, ni = struct.unpack('>HHHH', b[p:p + 8])
    p += 8
    ifaces = [cname(struct.unpack('>H', b[p + 2 * j:p + 2 * j + 2])[0]) for j in range(ni)]
    p += 2 * ni
    members = set()
    for _kind in ('f', 'm'):
        cnt = struct.unpack('>H', b[p:p + 2])[0]; p += 2
        for _ in range(cnt):
            _a, nm, ds, ac = struct.unpack('>HHHH', b[p:p + 8]); p += 8
            members.add((_kind, u(nm), u(ds)))
            for _ in range(ac):
                ln = struct.unpack('>I', b[p + 2:p + 6])[0]; p += 6 + ln
    refs = []
    for e in cp:
        if e and e[0] in ('field', 'method', 'imethod'):
            nat = cp[e[2]]
            refs.append(('f' if e[0] == 'field' else 'm', cname(e[1]), u(nat[1]), u(nat[2])))
    return {'name': cname(this), 'super': cname(sup) if sup else None, 'ifaces': ifaces,
            'members': members, 'refs': refs}


def main():
    out, hmix = sys.argv[1], sys.argv[2]
    cache = {}

    def lib(c):
        if c not in cache:
            f = os.path.join(hmix, *c.split('/')) + '.class'
            cache[c] = parse(f) if os.path.exists(f) else None
        return cache[c]

    def has(c, kind, nm, ds, seen=None):
        seen = seen or set()
        if c is None or c in seen:
            return False
        seen.add(c)
        k = lib(c)
        if k is None:
            return False
        if (kind, nm, ds) in k['members']:
            return True
        return has(k['super'], kind, nm, ds, seen) or any(has(i, kind, nm, ds, seen) for i in k['ifaces'])

    bad = 0
    checked = 0
    for cls in sys.argv[3:]:
        info = parse(os.path.join(out, *cls.split('/')) + '.class')
        for kind, owner, nm, ds in info['refs']:
            if not owner.startswith('java/'):
                continue
            checked += 1
            if owner.startswith('['):
                continue
            if lib(owner) is None:
                print('MISSING CLASS %s (used by %s)' % (owner, cls)); bad += 1
            elif not has(owner, kind, nm, ds):
                if kind == 'm' and nm == 'clone' and ds.startswith('()'):
                    continue
                print('MISSING %s.%s%s (used by %s)' % (owner, nm, ds, cls)); bad += 1
    print('library check: %d java.* references, %d missing' % (checked, bad))
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
