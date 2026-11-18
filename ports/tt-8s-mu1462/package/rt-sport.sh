#!/bin/sh
# Live Sport shift for the CarPlay cluster map (TT MU1462).
# The patched cluster_runtime moves the picture 453 px left while
# /tmp/mu1438-cluster-sport exists, and back when it is removed.
#   sh /tmp/rt-sport.sh --install --parked
#   sh /tmp/rt-sport.sh --restore --parked
# Replug the iPhone afterwards.
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
mode=${1:-}
test "${2:-}" = --parked || { echo 'Usage: rt-sport.sh --install|--restore --parked'; exit 2; }
STOCK=caf2fa48c341fd7bd1a860a1660c5eaad86b906c707a0abe3ff769a368855357
SPORT=24bf08142cc43a18330550ca448f4635e10d5ab3ed38475e80d450a469991a7f
RT=/mnt/app/root/mu1438-cluster/cluster_runtime
BAK=$RT.stock
tool=/tmp/altscreen-sha256
test -x "$tool" || { echo 'Copy altscreen-sha256 to /tmp and chmod 755 it first.'; exit 1; }
has(){ "$tool" "$1" "$2" >/dev/null 2>&1; }
case $mode in
    --install) SRC=$D/cluster_runtime-sport; want=$SPORT;;
    --restore) SRC=$BAK; want=$STOCK
               has $STOCK "$SRC" || SRC=$D/altscreen-cluster_runtime;;
    *) echo "Unknown mode: $mode"; exit 2;;
esac
has "$want" "$SRC" || { echo "$SRC missing or damaged"; exit 1; }
if has "$want" "$RT"; then echo "Already installed ($mode)."; exit 0; fi
has $STOCK "$RT" || has $SPORT "$RT" || { echo "$RT is not a known runtime - not touching it"; exit 1; }
rw=0
cleanup(){ if test "$rw" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi; }
trap cleanup 0
trap 'exit 1' 1 2 15
mount -uw /mnt/app; rw=1
if test "$mode" = --install && ! has $STOCK "$BAK"; then
    has $STOCK "$RT" && cp "$RT" "$BAK" && has $STOCK "$BAK"
fi
cp "$SRC" "$RT.new"
chmod 755 "$RT.new"
has "$want" "$RT.new"
mv "$RT.new" "$RT"
has "$want" "$RT"
if test "$mode" = --install; then echo 'Sport-shift runtime installed. Use hook preset h320. Replug the iPhone.'
else echo 'Stock runtime restored. Replug the iPhone.'; fi
