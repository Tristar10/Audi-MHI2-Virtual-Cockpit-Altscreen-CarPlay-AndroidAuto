#!/bin/sh
# TT CarPlay toolkit (Audi TT MU1462). One command for everything.
# After setup, from the SSH login folder:   sh tt.sh help
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
K=/mnt/app/root/tt
JARS=/mnt/app/eso/hmi/lsd/jars
JAR=$JARS/tt-zoom.jar
V12=5026c7c44e180352c78e1f21627331735ea108a7c5445ed0eb367a78e6a45a21
V14=ae246f4c4b9e4bc0e1235a74a119be4481ef3534f9c371833092fafa9e5df5e1
V15=f13fdd3ab74f3f85fe70900b51da0186eab0b0ef837314f7e7d4be9e0f202d08
V16=cc4110d51cf639a6502f6dfb5bcaa676bb101a553c365b123b4bd51ad78242b4
V17=651454c8695f514b0627c7f0818422820ff44245fd3dd4c1483487fa58fd1ec9
V18=a403e83dc210a4ae580c547bf63c0bf235934483b1e4096efdd5ade427e3003f
V19=a549814352b72762f1639c0343eeab829f954aa2ff92e078d6fea40fd30fb4e1
V20=146a39998585e62b59eff8e714c3e3e92271a15fb72530cd3aafddeb9000c44b
V21=43a59e05a7b5f8821428d2410a1e96ee142bddfa063fcc02af981d827da47e09
V22=1619db75cd413a614d28d17a646524cf3e51ea1492f971a29eb71452e739f763
V23=74b71d9013d088ca8a38f6b035a75da15a1c00b519a53713299aee967b7056e7
V24=a0f1c832d7ddb6e776a3d6985cc857ee5093878235cae778da675deb6c757917
V25=5ea2b40ec7f48cb62c0f0ce3f0b2dbb28ed74adec2264dddc5ae8afe3b981573
V26=42bb2f9cc16ef62c904fb9542ec0dedb86361062ea7388bcda0d16689bdc2f38
V27=f980ba096d9805ebd9d8245974a99525e0d1f48cd3f68534c892bff3cf356adf
T=$K/altscreen-sha256

say(){ echo "$*"; }
rw(){ mount -uw /mnt/app || { say 'Cannot make /mnt/app writable.'; exit 1; }; }
ro(){ sync; mount -ur /mnt/app 2>/dev/null; }
which_jar(){
    h=$("$T" "$JAR" 2>/dev/null)
    case "$h" in
        $V12) echo v12;; $V14) echo v14;; $V15) echo v15;; $V16) echo v16;; $V17) echo v17;; $V18) echo v18;; $V19) echo v19;; $V20) echo v20;; $V21) echo v21;; $V22) echo v22;; $V23) echo v23;; $V24) echo v24;; $V25) echo v25;; $V26) echo v26;; $V27) echo v27;; "") echo none;; *) echo "unknown ($h)";;
    esac
}

case "${1:-help}" in
setup)
    # Run from /tmp after copying the kit there. Safe to run again for a kit update.
    test -f /tmp/tt-zoom-v27.jar || { say 'Copy the kit files to /tmp first.'; exit 1; }
    rw
    mkdir -p "$K/logs"
    for f in altscreen-sha256 tt-zoom-v27.jar stock-replay.txt catalogue.txt komo-test.txt panel-test.txt kdk-test.txt fit-test.txt; do
        cp "/tmp/$f" "$K/$f" || { say "missing /tmp/$f"; ro; exit 1; }
    done
    for f in tt-zoom-v12.jar tt-zoom-v14.jar tt-zoom-v15.jar tt-zoom-v16.jar tt-zoom-v17.jar tt-zoom-v18.jar tt-zoom-v19.jar tt-zoom-v20.jar tt-zoom-v21.jar tt-zoom-v22.jar tt-zoom-v23.jar tt-zoom-v24.jar tt-zoom-v25.jar tt-zoom-v26.jar; do
        test -f "/tmp/$f" && cp "/tmp/$f" "$K/$f"
    done
    cp /tmp/tt.sh /mnt/app/root/tt.sh
    chmod 755 "$T" /mnt/app/root/tt.sh
    ok=1
    "$T" $V27 "$K/tt-zoom-v27.jar" || ok=0
    ro
    if test $ok = 1; then say 'Kit installed. From now on just type:  sh tt.sh help'
    else say 'A kit file is damaged - copy the kit again and rerun setup.'; exit 1; fi
    ;;
use)
    v=${2:-}
    case "$v" in v12) h=$V12;; v14) h=$V14;; v15) h=$V15;; v16) h=$V16;; v17) h=$V17;; v18) h=$V18;; v19) h=$V19;; v20) h=$V20;; v21) h=$V21;; v22) h=$V22;; v23) h=$V23;; v24) h=$V24;; v25) h=$V25;; v26) h=$V26;; v27) h=$V27;; *) say 'Usage: sh tt.sh use v27   (or v26 ... v12)'; exit 2;; esac
    test -f "$K/tt-zoom-$v.jar" || { say "$v is not in the kit on the car."; exit 1; }
    "$T" $h "$K/tt-zoom-$v.jar" || { say "Stored $v jar is damaged."; exit 1; }
    rw
    cp "$K/tt-zoom-$v.jar" "$JAR.new" && chmod 755 "$JAR.new" && mv "$JAR.new" "$JAR"
    "$T" $h "$JAR" && r=0 || r=1
    ro
    test $r = 0 && say "Installed $v. Now restart the MMI." || say 'Copy failed - nothing changed.'
    ;;
remove)
    rw; rm -f "$JAR"; ro; say 'tt-zoom.jar removed. Restart the MMI for stock behaviour.'
    ;;
play)
    case "${2:-stock}" in
        stock) cp "$K/stock-replay.txt" /tmp/tt-rgi-play.txt && say 'Stock-nav replay playing (about 2 min, repeats). Stop: sh tt.sh stop';;
        fit) cp "$K/fit-test.txt" /tmp/tt-rgi-play.txt && say 'v27 fit test playing: grid 3 x 30 s (small view, car tab, map tab), then the panel 60 s; repeats. Stop: sh tt.sh stop';;
        kdk) cp "$K/kdk-test.txt" /tmp/tt-rgi-play.txt && say 'Banner-spot test 2 playing (about 110 s, repeats). Stop: sh tt.sh stop';;
        panel) cp "$K/panel-test.txt" /tmp/tt-rgi-play.txt && say 'Turn panel demo playing (about 2 min, repeats). Stop: sh tt.sh stop';;
        komo) cp "$K/komo-test.txt" /tmp/tt-rgi-play.txt && say 'KOMO test playing (about 100 s, repeats). Stop: sh tt.sh stop';;
        arrows) cp "$K/catalogue.txt" /tmp/tt-rgi-play.txt && say 'Arrow catalogue playing (about 3 min). Stop: sh tt.sh stop';;
        *) say 'Usage: sh tt.sh play fit | kdk | panel | komo | stock | arrows'; exit 2;;
    esac
    ;;
stop)
    rm -f /tmp/tt-rgi-play.txt; say 'Playback stopped; iPhone feed resumes in a few seconds.'
    ;;
mode)
    case "${2:-}" in
        default) rm -f /tmp/tt-rgi-noactive /tmp/tt-rgi-kdk /tmp/tt-rgi-nokdk /tmp/tt-rgi-nokdk-small /tmp/tt-rgi-nopad /tmp/tt-rgi-off /tmp/tt-panel-off; say 'Mode: default (our panel inside the stock banner, every view).';;
        nokdk) touch /tmp/tt-rgi-nokdk; say 'Mode: no stock banner (v26 behaviour: small view only).';;
        kdk) rm -f /tmp/tt-rgi-nokdk; touch /tmp/tt-rgi-kdk; say 'Mode: stock banner in every view.';;
        nokdksmall) touch /tmp/tt-rgi-nokdk-small; say 'Mode: no stock banner in the small view either.';;
        nopanel) touch /tmp/tt-panel-off; say 'Mode: nopanel (our turn panel off).';;
        noactive) touch /tmp/tt-rgi-noactive; say 'Mode: never report route active.';;
        nopad) touch /tmp/tt-rgi-nopad; say 'Arrows: bare 2-number shape.';;
        off) touch /tmp/tt-rgi-off; say 'Cockpit feed off.';;
        on) rm -f /tmp/tt-rgi-off; say 'Cockpit feed on.';;
        *) say 'Usage: sh tt.sh mode default|nokdk|kdk|nopanel|noactive|nopad|off|on'; exit 2;;
    esac
    ;;
panel)
    # Live position/size of our turn panel per view; read on every redraw (no restart).
    P=/tmp/tt-panel-pos
    case "${2:-}" in
        kdk-small|kdk-large|left|right|small|flap)
            test -n "${4:-}" || { say 'Usage: sh tt.sh panel kdk-small|kdk-large X Y [SCALE]'; exit 2; }
            touch $P; grep -v "^$2 " $P > $P.new 2>/dev/null; echo "$2 $3 $4 ${5:-1}" >> $P.new; mv $P.new $P
            say "Panel $2 view: x=$3 y=$4 scale=${5:-1} (applies at the next update)";;
        reset) rm -f $P; say 'Panel position back to defaults.';;
        show) cat $P 2>/dev/null || say '(defaults: kdk-small 991 152 1, kdk-large 1105 110 1)';;
        *) say 'Usage: sh tt.sh panel kdk-small|kdk-large X Y [SCALE] | reset | show'; exit 2;;
    esac
    ;;
report)
    say "== jar: $(which_jar)    (v27 is the current one)"
    say '== switches:'
    ls /tmp/tt-rgi-* 2>/dev/null || say '(none)'
    say '== iPhone and cockpit (last 8):'
    grep -E 'rgi on=|hmi|combi|listening|hook (dis)?connected' /tmp/tt-rgd.log 2>/dev/null | tail -8
    say '== playback (last 3):'
    grep -E 'play|panel' /tmp/tt-rgd.log 2>/dev/null | tail -6
    say '== nav log:'
    if test -f /tmp/tt-nav.log; then
        say "$(wc -l < /tmp/tt-nav.log) lines; last 5 non-street:"
        grep -v -E ' street | dtm | nextDest | direction ' /tmp/tt-nav.log | tail -8
        say '== guidance view answers:'
        grep -E 'komo(Result|RgSelResult|Type|Enabled|Visible|Gfx)' /tmp/tt-nav.log | tail -8
    else say 'none'; fi
    ;;
save)
    n=1; while test -e "$K/logs/run$n"; do n=$((n+1)); done
    rw; mkdir -p "$K/logs/run$n"
    for f in /tmp/tt-rgd.log /tmp/tt-rgd.log.1 /tmp/tt-nav.log /tmp/tt-zoom.log /tmp/tt-rgd.txt; do test -f "$f" && cp "$f" "$K/logs/run$n/"; done
    ro
    say "Saved to $K/logs/run$n (kept across MMI restarts). On the Mac:"
    say "scp -O -r -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa root@10.173.189.1:$K/logs ~/Downloads/tt-logs"
    ;;
*)
    say 'sh tt.sh report              what is installed and what it is doing (paste this)'
    say 'sh tt.sh use v27             switch version (v27 ... v12), then restart the MMI'
    say 'sh tt.sh play fit|kdk|panel|komo|stock  parked tests'
    say 'sh tt.sh play arrows | stop  arrow catalogue / stop playback'
    say 'sh tt.sh mode default|nokdk|kdk|nopanel|noactive|nopad|off|on'
    say 'sh tt.sh panel kdk-small|kdk-large X Y [S]  move/resize the panel live (reset | show)'
    say 'sh tt.sh save                keep the logs across MMI restarts'
    say 'sh tt.sh remove              take tt-zoom.jar out (restart the MMI)'
    ;;
esac
