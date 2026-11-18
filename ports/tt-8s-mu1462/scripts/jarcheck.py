"""List stock classes a mod jar replaces, and every external class/method/field
reference in the mod jars that does not resolve against the stock HMI jar.

usage: python -I jarcheck.py stock.jar mod1.jar [mod2.jar ...]
"""
import struct, sys, zipfile

def parse(b):
    """Minimal class-file reader: returns (name, super, interfaces, methods, fields, refs)."""
    if b[:4] != b'\xca\xfe\xba\xbe':
        return None
    n = struct.unpack('>H', b[8:10])[0]
    cp = [None] * n
    i, k = 10, 1
    while k < n:
        t = b[i]
        if t == 1:
            l = struct.unpack('>H', b[i+1:i+3])[0]
            cp[k] = ('u', b[i+3:i+3+l].decode('utf-8', 'replace')); i += 3 + l
        elif t in (3, 4):
            cp[k] = ('i',); i += 5
        elif t in (5, 6):
            cp[k] = ('l',); i += 9; k += 1
        elif t in (7, 8, 16, 19, 20):
            cp[k] = (t, struct.unpack('>H', b[i+1:i+3])[0]); i += 3
        elif t in (9, 10, 11, 12, 17, 18):
            cp[k] = (t, struct.unpack('>H', b[i+1:i+3])[0], struct.unpack('>H', b[i+3:i+5])[0]); i += 5
        elif t == 15:
            cp[k] = (t,); i += 4
        else:
            raise ValueError('bad tag %d' % t)
        k += 1
    u = lambda x: cp[x][1]
    cls = lambda x: u(cp[x][1])
    this = cls(struct.unpack('>H', b[i+2:i+4])[0])
    sup_i = struct.unpack('>H', b[i+4:i+6])[0]
    sup = cls(sup_i) if sup_i else None
    ic = struct.unpack('>H', b[i+6:i+8])[0]
    itf = [cls(struct.unpack('>H', b[i+8+2*j:i+10+2*j])[0]) for j in range(ic)]
    i = i + 8 + 2 * ic
    def members(i):
        c = struct.unpack('>H', b[i:i+2])[0]; i += 2; out = []
        for _ in range(c):
            nm = u(struct.unpack('>H', b[i+2:i+4])[0]); ds = u(struct.unpack('>H', b[i+4:i+6])[0])
            ac = struct.unpack('>H', b[i+6:i+8])[0]; i += 8
            for _ in range(ac):
                l = struct.unpack('>I', b[i+2:i+6])[0]; i += 6 + l
            out.append((nm, ds))
        return out, i
    fields, i = members(i)
    methods, i = members(i)
    refs = []
    for e in cp:
        if e and e[0] in (9, 10, 11):
            owner = cls(e[1]); nt = cp[e[2]]
            refs.append(('F' if e[0] == 9 else 'M', owner, u(nt[1]), u(nt[2])))
        elif e and e[0] == 7:
            refs.append(('C', u(e[1]), None, None))
    return this, sup, itf, set(methods), set(fields), refs

def load(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith('.class'):
                p = parse(z.read(n))
                if p:
                    out[p[0]] = p
    return out

stock = load(sys.argv[1])
mods = {}
owner_jar = {}
for j in sys.argv[2:]:
    m = load(j)
    for c in m:
        owner_jar[c] = j.replace('\\', '/').split('/')[-1]
    mods.update(m)
print('stock classes replaced:')
for c in sorted(mods):
    if c in stock:
        print('  %-14s %s' % (owner_jar[c], c))
world = dict(stock); world.update(mods)

def has(owner, kind, name, desc, seen=None):
    seen = seen or set()
    if owner in seen or owner not in world:
        return owner not in world and owner.startswith('java/') and None
    seen.add(owner)
    c = world[owner]
    if (name, desc) in (c[3] if kind == 'M' else c[4]):
        return True
    for p in ([c[1]] if c[1] else []) + c[2]:
        r = has(p, kind, name, desc, seen)
        if r:
            return True
    return False

missing = set()
for c, p in mods.items():
    for kind, owner, name, desc in p[5]:
        if owner.startswith('['):
            continue
        if kind == 'C':
            if owner not in world:
                missing.add(('class', owner, '', owner_jar[c]))
            continue
        if owner not in world:
            missing.add(('class', owner, '', owner_jar[c]))
            continue
        r = has(owner, kind, name, desc)
        if r is False:
            missing.add(('member', owner, name + ' ' + desc, owner_jar[c]))
print('unresolved references: %d' % len(missing))
for m in sorted(missing):
    print('  %s %s %s  (%s)' % m)
