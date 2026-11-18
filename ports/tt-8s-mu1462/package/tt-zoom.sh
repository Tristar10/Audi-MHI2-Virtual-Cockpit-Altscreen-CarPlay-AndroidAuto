#!/bin/sh
# Scroll-wheel zoom for the CarPlay cluster map, Audi TT MU1462 (v7: zoom + classic/sport marker, live switch).
# Adds tt-zoom.jar to the HMI jar directory; nothing stock is replaced on flash.
# The HMI loads it at the next MMI restart.
#
#   sh /tmp/tt-zoom.sh --check  --parked
#   sh /tmp/tt-zoom.sh --install --parked
#   sh /tmp/tt-zoom.sh --remove  --parked
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
case "${1:-}" in --check|--install|--remove) action=$1;; *) echo 'Usage: --check|--install|--remove --parked'; exit 2;; esac
test "${2:-}" = --parked || { echo 'Park safely and add --parked.'; exit 2; }

JARS=/mnt/app/eso/hmi/lsd/jars
DEST=$JARS/tt-zoom.jar
JAR_SHA=3c724a820edd6504a479e26dcee5393a2ed8a022425d1c3fc41d18e7fa8f0879
STOCK_JXE=44d0dfe86146f4f6f87819921088aa282025aebfcc940c7288804fc476ff21b7
tool=/tmp/altscreen-sha256
test -x "$tool" || { echo 'Copy altscreen-sha256 to /tmp and chmod 755 it first.'; exit 1; }
rw=0
cleanup(){ if test "$rw" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi; }
trap cleanup 0
trap 'exit 1' 1 2 15

if test "$action" = --remove; then
    test -e "$DEST" || { echo 'tt-zoom.jar is not installed.'; exit 0; }
    mount -uw /mnt/app; rw=1
    rm -f "$DEST"
    echo 'Removed. Restart the MMI to go back to the stock wheel behaviour.'
    exit 0
fi

fail=0
ok(){ echo "OK    $1"; }
bad(){ echo "FAIL  $1"; fail=1; }
test -d "$JARS" && ok "jar directory $JARS" || bad "no $JARS"
"$tool" "$STOCK_JXE" /ifs/lsd.jxe 2>/dev/null && ok 'HMI image is MU1462 stock' || bad 'HMI image differs from MU1462'
"$tool" "$JAR_SHA" "$D/tt-zoom.jar" 2>/dev/null && ok 'tt-zoom.jar intact' || bad 'tt-zoom.jar missing or damaged'
test -e /mnt/app/root/lib-target/libaltscreen111.so && ok 'AltScreen hook installed' || bad 'AltScreen hook not installed'
for other in mu1438-cluster.jar rgd_hook.jar carplay_hook.jar; do
    test ! -e "$JARS/$other" || bad "$other is present - another CarPlay HMI mod"
done
test "$fail" = 0 || { echo 'Stopped: nothing was changed.'; exit 1; }
if test "$action" = --check; then echo 'All checks passed. Nothing was changed.'; exit 0; fi

mount -uw /mnt/app; rw=1
cp "$D/tt-zoom.jar" "$DEST.new"
chmod 755 "$DEST.new"
"$tool" "$JAR_SHA" "$DEST.new"
mv "$DEST.new" "$DEST"
"$tool" "$JAR_SHA" "$DEST"
echo "Installed. Restart the MMI while parked; the jar loads at HMI start. Wheel log: /tmp/tt-zoom.log"
echo "If the MMI does not come back, over SSH: sh $D/tt-zoom.sh --remove --parked, then restart."
