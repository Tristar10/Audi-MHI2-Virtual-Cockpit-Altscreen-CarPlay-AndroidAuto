#!/bin/sh
# Route-guidance data test for Audi TT MHI2_US_AU43x_P5180 / MU1462.
# Puts chefranov's unmodified librgd_hook.so in front of mm-ipod so the
# iPhone's turn-by-turn data is written to /dev/shmem/rgd_hook.log.
# Nothing is sent to the cluster yet. Writes to flash only with --install
# or --restore.
#
#   sh /tmp/rgd-test.sh --check   --parked
#   sh /tmp/rgd-test.sh --install --parked
#   sh /tmp/rgd-test.sh --restore --parked
#
# Quick off switch without a flash write: touch /tmp/rgd_disable, replug.
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/sbin
export PATH
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
case "${1:-}" in --check|--install|--restore) action=$1;; *) echo 'Usage: --check|--install|--restore --parked'; exit 2;; esac
test "${2:-}" = --parked || { echo 'Park safely and add --parked.'; exit 2; }
test -d /mnt/app/eso/hmi/lsd || { echo 'Run this on the MMX head unit (root@mmx).'; exit 1; }

SBIN=/mnt/app/armle/usr/sbin
REAL=$SBIN/rgd_real/mm-ipod
DIR=/mnt/app/root/rgd
HOOK=$DIR/librgd_hook.so
ORIG=$DIR/mm-ipod.orig
DRV=/mnt/app/armle/lib/dll/ipod-drvr-iap2.so
STOCK_MMIPOD=d5af4a342705bd7ac3498a080e24faf5702c6fc2960895f58aa2800268126464
STOCK_DRV=7b9a1a6beef7bc893bb5a824486f4ad5fc34912a0b894a6d24d171ab324a8cb7
HOOK_SHA=55470830e7972b55bb7b5d5a00fd8fe0802f5fe21018415d839ac57bef556a99

test -x /tmp/altscreen-sha256 || { echo 'Copy altscreen-sha256 to /tmp first.'; exit 1; }
tool=/tmp/altscreen-sha256
is(){ test "$("$tool" "$2" 2>/dev/null)" = "$1"; }
rw=0
cleanup(){ if test "$rw" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi; }
trap cleanup 0
trap 'exit 1' 1 2 15

write_shim(){
    : > "$1"
    print -r -- '#!/bin/sh' >> "$1"
    print -r -- '# Route-guidance test shim. The real binary keeps the name mm-ipod' >> "$1"
    print -r -- '# because the hook gates on it. Off: touch /tmp/rgd_disable, replug.' >> "$1"
    print -r -- "if [ ! -e /tmp/rgd_disable ] && [ ! -e $DIR/disable ]; then" >> "$1"
    print -r -- "    LD_PRELOAD=$HOOK" >> "$1"
    print -r -- '    export LD_PRELOAD' >> "$1"
    print -r -- 'fi' >> "$1"
    print -r -- "exec $REAL \"\$@\"" >> "$1"
}

if test "$action" = --restore; then
    test -f "$ORIG" || { echo "No backup at $ORIG - nothing to restore."; exit 1; }
    is "$STOCK_MMIPOD" "$ORIG" || { echo 'Backup does not match the stock mm-ipod - stopping.'; exit 1; }
    mount -uw /mnt/app; rw=1
    cp "$ORIG" "$SBIN/mm-ipod.new"
    chmod 755 "$SBIN/mm-ipod.new"
    mv "$SBIN/mm-ipod.new" "$SBIN/mm-ipod"
    is "$STOCK_MMIPOD" "$SBIN/mm-ipod"
    rm -f "$REAL"
    rmdir "$SBIN/rgd_real" 2>/dev/null || :
    echo "Restored the stock mm-ipod. Backup and hook left in $DIR."
    echo 'Unplug and replug the iPhone.'
    exit 0
fi

fail=0
ok(){ echo "OK    $1"; }
bad(){ echo "FAIL  $1"; fail=1; }
if is "$STOCK_MMIPOD" "$SBIN/mm-ipod"; then ok 'mm-ipod is the stock binary'
elif test -f "$REAL"; then bad 'shim already installed (use --restore first)'
else bad 'mm-ipod is not the stock MU1462 binary'; fi
is "$STOCK_DRV" "$DRV" && ok 'iAP2 driver is stock' || bad 'iAP2 driver differs from MU1462'
is "$HOOK_SHA" "$D/librgd_hook.so" && ok 'librgd_hook.so is the published v1.1.10 file' || bad 'librgd_hook.so missing or not the v1.1.10 file'
test ! -e "$SBIN/rgd_real" && ok 'no earlier shim directory' || bad "$SBIN/rgd_real already exists"
test "$fail" = 0 || { echo 'Stopped: nothing was changed.'; exit 1; }
if test "$action" = --check; then echo 'All checks passed. Nothing was changed.'; exit 0; fi

mount -uw /mnt/app; rw=1
mkdir -p "$DIR" "$SBIN/rgd_real"
cp "$SBIN/mm-ipod" "$ORIG"; is "$STOCK_MMIPOD" "$ORIG"
cp "$SBIN/mm-ipod" "$REAL"; chmod 755 "$REAL"; is "$STOCK_MMIPOD" "$REAL"
cp "$D/librgd_hook.so" "$HOOK"; chmod 755 "$HOOK"; is "$HOOK_SHA" "$HOOK"
write_shim "$SBIN/mm-ipod.new"
chmod 755 "$SBIN/mm-ipod.new"
mv "$SBIN/mm-ipod.new" "$SBIN/mm-ipod"
echo "Installed. Stock mm-ipod saved at $ORIG."
echo 'Unplug and replug the iPhone, start a route, then: cat /dev/shmem/rgd_hook.log'
