# cluster_runtime (MU1462 build, caf2fa48) -> live sport shift.
# Every 8 decoded frames: re-read the decoder attributes; source crop x = 453 if
# /tmp/mu1438-cluster-sport exists else 0; if it differs from the live value,
# rewrite crop (x,0,1440-x,540) -> screen (0,0,1440-x,540) and SetAttribs.
import sys,struct,hashlib
sys.path.insert(0,sys.argv[1])
from capstone import *
SHIFT=int(sys.argv[4]) if len(sys.argv)>4 else 453
B=0x100000
src=open(sys.argv[2],'rb').read(); b=bytearray(src)
assert hashlib.sha256(src).hexdigest().startswith('caf2fa48')
md=Cs(CS_ARCH_ARM,CS_MODE_ARM)
def dis(a,n): return [(i.address,i.mnemonic,i.op_str) for i in md.disasm(bytes(b[a-B:a-B+4*n]),a)]
def W(a): return struct.unpack('<I',src[a-B:a-B+4])[0]
# sanity: original instructions where we patch
o={x[0]:(x[1],x[2]) for x in dis(0x101b3c,17)+dis(0x101cd0,25)}
assert o[0x101b3c]==('ldr','sb, [sl, #0x10]') and o[0x101b78]==('bl','#0x100af8') and o[0x101b7c]==('b','#0x10198c')
assert o[0x101cd0]==('add','r1, sp, #0x3c') and o[0x101d2c]==('bl','#0x100af8') and o[0x101d30]==('b','#0x1017c8')
assert b[0x24c0:0x24c6]==b'frames'
R=dict(r0=0,r1=1,r2=2,r3=3,r8=8,sb=9,sl=10,sp=13)
def br(cond,link,a,t): return (cond<<28)|((0xB if link else 0xA)<<24)|(((t-(a+8))>>2)&0xFFFFFF)
AL,EQ,NE=0xE,0x0,0x1
def ldr(t,n,i): return 0xE5900000|R[n]<<16|R[t]<<12|i
def st(t,n,i): return 0xE5800000|R[n]<<16|R[t]<<12|i
def stb(t,n,i): return 0xE5C00000|R[n]<<16|R[t]<<12|i
def movw(d,i,c=AL): return (c<<28)|0x03000000|(i>>12)<<16|R[d]<<12|(i&0xfff)
def movt(d,i): return 0xE3400000|(i>>12)<<16|R[d]<<12|(i&0xfff)
def movi(d,enc,c=AL): return (c<<28)|0x03A00000|R[d]<<12|enc
def cmpi(n,i): return 0xE3500000|R[n]<<16|i
def cmpr(n,m): return 0xE1500000|R[n]<<16|R[m]
def tst(n,i): return 0xE3100000|R[n]<<16|i
def addsp(d,i): return 0xE28D0000|R[d]<<12|i
def blx(m): return 0xE12FFF30|R[m]
ENC1440=W(0x101c84)&0xFFF; ENC540=W(0x101c88)&0xFFF
def rsb(d,n,enc): return 0xE2600000|R[n]<<16|R[d]<<12|enc
LOOP=0x10198c; ACCESS=0x100c90; CAVE=0x101cd8; STR=0x1024c0
code={}
def emit(a,words):
    for k,w in enumerate(words): code[a+4*k]=w
def mvne(d,enc): return movi(d,enc,NE)
STATE=0x1c   # r8=0x1037c8 -> 0x1037e4, alignment padding before recovery_start
def streq(t,n,i): return 0x05800000|R[n]<<16|R[t]<<12|i
R['r4']=4
a=0x101b3c   # block A replaces the frames= log
emit(a,[movw('r0',STR&0xffff), movt('r0',STR>>16), movi('r1',0), br(AL,1,a+0xc,ACCESS),
        cmpi('r0',0), movw('r4',SHIFT), mvne('r4',0),
        ldr('r3','r8',STATE), cmpr('r3','r4'), br(EQ,0,a+0x24,LOOP),
        ldr('r0','r8',8), addsp('r1',0x78), ldr('r3','r8',0x40), blx('r3'),
        cmpi('r0',0), br(NE,0,a+0x3c,LOOP), br(AL,0,a+0x40,CAVE)])
# prepare_renderer succeeded (r0==0): applied shift = 0, skip the elapsed-time log
emit(0x101cd0,[st('r0','r8',STATE), br(AL,0,0x101cd4,0x1017c8)])
c=CAVE
emit(c,[movi('r1',0), movi('r0',1), movi('r3',ENC540),
        st('r4','sp',0x84), st('r1','sp',0x88), st('r3','sp',0x90),
        st('r1','sp',0x94), st('r1','sp',0x98), st('r3','sp',0xa0),
        rsb('r3','r4',ENC1440), st('r3','sp',0x8c), st('r3','sp',0x9c),
        stb('r0','sp',0x80), stb('r0','sp',0xb4), stb('r1','sp',0xb5),
        ldr('r0','r8',8), addsp('r1',0x78), ldr('r3','r8',0x44), blx('r3'),
        cmpi('r0',0), streq('r4','r8',STATE), br(AL,0,c+0x54,LOOP), 0xE320F000])
assert max(code)<=0x101d30 and len(code)==17+2+23
for a,w in code.items(): b[a-B:a-B+4]=struct.pack('<I',w)
s=b'/tmp/mu1438-cluster-sport\0'; b[0x24c0:0x24c0+len(s)]=s
for x in dis(0x101b3c,17)+[('--','','')]+dis(0x101cd0,25): print('%#08x: %-6s %s'%x if x[0]!='--' else '--')
diff=[i for i in range(len(src)) if src[i]!=b[i]]
print('changed bytes',len(diff),hex(min(diff)),hex(max(diff)),'shift',SHIFT)
open(sys.argv[3],'wb').write(b); print(hashlib.sha256(b).hexdigest())
