import sys, struct, re, importlib.util
spec = importlib.util.spec_from_file_location('elfinfo', sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
e = m.Elf(sys.argv[2]); hook = open(sys.argv[3], encoding='utf-8', errors='replace').read()
defs = {n: (v, s, i) for n, v, s, i, x in e.syms() if n and x != 0}
names = sorted(set(re.findall(r'RESOLVE\(\w+, "(\w+)"\)', hook)) | set(re.findall(r'sym_next\("(\w+)"\)', hook)) | set(re.findall(r'dlsym\(RTLD_DEFAULT, "(\w+)"\)', hook)))
miss = [n for n in names if n not in defs]
print('needed', len(names), 'present in libairplay', len(names) - len(miss)); print('not in libairplay:', miss)
for n in ['AirPlayReceiverSessionSetup', 'AirPlayReceiverSessionStart', 'AirPlayReceiverSessionTearDown', 'AirPlayReceiverSessionPlatformControl', 'AirPlayReceiverSessionControl', 'AirPlayCopyServerInfo', 'AES_CBCFrame_Init', 'AirPlay_DeriveAESKeySHA512ForScreen']:
    if n not in defs: print(n, 'MISSING'); continue
    v, s, i = defs[n]; o = e.v2o(v & ~1); a, b = struct.unpack_from('<II', e.d, o)
    print('%-42s va=%#x size=%d thumb=%d first=%08x %08x' % (n, v, s, v & 1, a, b))
