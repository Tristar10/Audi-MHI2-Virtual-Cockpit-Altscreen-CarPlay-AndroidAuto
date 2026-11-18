import re,sys,io
src,dst=sys.argv[1],sys.argv[2]
T0,T1=float(sys.argv[3]),float(sys.argv[4])
ev=[]
for line in io.open(src,encoding='utf-8',errors='replace'):
    p=line.rstrip('\n').split(' ',2)
    if len(p)<3 or not p[0].isdigit(): continue
    t=int(p[0])/1000.0
    if t<T0 or t>T1: continue
    ev.append((t,p[1],p[2]))
out=['# Stock navigation replay: what the TT\'s own nav sent on the run3 route',
     '# (Spring Cypress exit, right on Spring-Cypress Rd, right on Boudreaux Rd, left on Grand Pkwy).',
     '# Sent through tt-zoom v15 exactly as recorded, with long straight stretches shortened.',
     'say stock replay start','active 1','eta 1500']
state=1; last_t=None; n_dtm=0; lastdest=None; total=0
def wait(ms):
    global total
    if ms>=50: out.append('wait %d'%ms); total+=ms
for t,tag,v in ev:
    gap=0 if last_t is None else (t-last_t)*1000
    cmd=None
    if tag=='rgi':
        m=re.search(r'\[(.*)\]',v); cmd='icon '+m.group(1) if m else None
    elif tag=='maneuverState':
        state=int(v); cmd='state '+v; out.append('say maneuver state '+v)
    elif tag=='dtm':
        m=re.search(r'showDistance=(\w+),distance=(\d+),showBargraph=(\w+),bargraph=(\d+)',v)
        n_dtm+=1
        keep = (state>=3 and n_dtm%2==0) or (n_dtm%6==0)
        if m.group(3)=='true' and int(m.group(2))<60: keep=True
        if not keep: continue
        cmd='turn %s %s'%(m.group(2),m.group(4)) if m.group(3)=='true' else 'turn %s'%m.group(2)
    elif tag=='turnTo':
        cmd='next '+v.rsplit(' valid=',1)[0].strip()
    elif tag=='street':
        s=v.strip()
        if s and s!='offroad': cmd='road '+s
    elif tag=='nextDest':
        m=re.search(r'distanceToNextDest=(\d+)',v); d=int(m.group(1))
        if lastdest is None or abs(lastdest-d)>=300: lastdest=d; cmd='dest %d'%d
    if cmd is None: continue
    wait(min(gap, 500 if state>=3 else 250))
    out.append(cmd); last_t=t
out+=['say stock replay end, repeating in 5 s','wait 5000','loop']
io.open(dst,'w',encoding='utf-8',newline='\n').write('\n'.join(out)+'\n')
print(len(out),'lines, about %.0f s'%(total/1000.0))
