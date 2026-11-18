import re,sys,io
sys.stdout=io.TextIOWrapper(sys.stdout.buffer,encoding="utf-8")
st=None;last=None
for line in open(sys.argv[1],encoding='utf-8',errors='replace'):
    p=line.split(' ',2)
    if len(p)<2 or not p[0].isdigit(): continue
    t=int(p[0])/1000.0; tag=p[1]; rest=p[2].strip() if len(p)>2 else ''
    if tag=='dtm':
        m=re.search(r'showDistance=(\w+),distance=(\d+),showBargraph=(\w+),bargraph=(\d+)',rest)
        d=(m.group(1)[0],int(m.group(2)),m.group(3)[0],int(m.group(4)))
        last=d
        # print only when bargraph state flips or every bargraph change
        if d[2]=='t': print('%8.1f   dtm dist=%d bar=%d'%(t,d[1],d[3]))
    elif tag in('rgi','maneuverState','turnTo','lanes','turnToInfo'):
        print('%8.1f %s %s   [last dtm %s]'%(t,tag,rest[:120],last))
    elif tag=='nextManeuver':
        m=re.search(r'distanceToElement="([^"]*)".*?routeInfoElementType=(\d+).*?streetIconText="([^"]*)",streetIconID=(\d+),exitNumber="([^"]*)",turnToStreet="([^"]*)".*?maneuverDescriptor\[\d+\]=\{(.*?)\},trafficInfo.*?signPostInfo=([^,]*(?:,[^=]*)?)',rest)
        print('%8.1f RIE %s'%(t,m.groups() if m else rest[:300]))
