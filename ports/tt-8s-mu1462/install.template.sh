#!/bin/sh
# AltScreen for Audi TT MHI2_US_AU43x_P5180 / MU1462 - stage 1 (native hook only).
# Based on aberbic/Audi-MHI2-Virtual-Cockpit-Altscreen-CarPlay-AndroidAuto (GPL-3.0).
# Does not touch the HMI. Writes to flash only with --install or --restore.
#
#   sh altscreen-mu1462-install.sh --check   --parked
#   sh altscreen-mu1462-install.sh --install --parked
#   sh <backup dir>/altscreen-mu1462-install.sh --restore --parked
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/sbin
export PATH
unset LD_PRELOAD || :
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
case "${1:-}" in --check|--install|--restore) action=$1;; *) echo 'Usage: --check|--install|--restore --parked'; exit 2;; esac
test "${2:-}" = --parked || { echo 'Park safely and add --parked.'; exit 2; }
test -d /mnt/app/eso/hmi/lsd && test -f /ifs/lsd.jxe || { echo 'Run this on the MMX head unit (root@mmx).'; exit 1; }

BIN=/mnt/app/eso/bin/apps/dio_manager
LIB=/mnt/app/root/lib-target/libaltscreen111.so
RT=/mnt/app/root/mu1438-cluster/cluster_runtime
SUP=/mnt/app/root/mu1438-cluster/cluster_supervisor.sh
PF_DIR=/mnt/system/etc
BLOCK='block drop in quick on carplay0 all'
RULE='pass in quick on carplay0 proto tcp from any to any port = 6031 allow-opts'

STOCK_JXE=@@STOCK_JXE@@
STOCK_AIRPLAY=@@STOCK_AIRPLAY@@
STOCK_IAP2=@@STOCK_IAP2@@
STOCK_DIO=@@STOCK_DIO@@
NEW_DIO=@@NEW_DIO@@
NEW_LIB=@@NEW_LIB@@
NEW_RT=@@NEW_RT@@
NEW_SUP=@@NEW_SUP@@

tool=/tmp/altscreen-sha.$$
cp "$D/altscreen-sha256" "$tool"
chmod 755 "$tool"
rw_app=0
rw_sys=0
backup=
cleanup(){
 rc=$?
 if test "$rw_sys" = 1; then sync || :; mount -ur /mnt/system || echo 'WARNING: /mnt/system still writable'; fi
 if test "$rw_app" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi
 rm -f "$tool" /tmp/altscreen-pf.$$.* /tmp/altscreen-ps.$$ || :
 if test "$rc" != 0 && test -n "$backup"; then echo "FAILED. Recovery snapshot kept at: $backup"; fi
}
trap cleanup 0
trap 'exit 1' 1 2 15

# Copy src to dst with RULE inserted before the carplay0 block line.
# Prints how many block lines were found.
build_pf(){
 n=0
 : > "$2"
 while IFS= read -r line || test -n "$line"; do
  case $line in "$BLOCK"*) printf '%s\n' "$RULE" >> "$2"; n=$((n+1));; esac
  printf '%s\n' "$line" >> "$2"
 done < "$1"
 echo "$n"
}

pf_list(){
 for f in "$PF_DIR"/pf.conf "$PF_DIR"/pf.*.conf; do test -f "$f" && echo "$f"; done
 return 0
}

if test "$action" = --restore; then
 case "$D" in /mnt/app/root/altscreen-backups/*) ;; *) echo 'Run the copy saved in the backup directory.'; exit 1;; esac
 backup=$D
 while IFS='|' read -r name dest mode old new; do
  if test "$old" != absent; then "$tool" "$old" "$D/$name.before"; fi
 done < "$D/manifest"
 mount -uw /mnt/app; rw_app=1
 mount -uw /mnt/system; rw_sys=1
 while IFS='|' read -r name dest mode old new; do
  if test "$old" = absent; then
   if test -e "$dest"; then mv "$dest" "$D/$name.removed"; fi
  else
   cp "$D/$name.before" "$dest.altscreen-restore"
   chmod "$mode" "$dest.altscreen-restore"
   "$tool" "$old" "$dest.altscreen-restore"
   mv "$dest.altscreen-restore" "$dest"
   "$tool" "$old" "$dest"
  fi
 done < "$D/manifest"
 echo 'Restored. Restart the MMI to finish.'
 exit 0
fi

fail=0
ok(){ echo "OK    $1"; }
bad(){ echo "FAIL  $1"; fail=1; }
is(){ test "$("$tool" "$2" 2>/dev/null)" = "$1"; }

is "$STOCK_JXE" /ifs/lsd.jxe && ok 'HMI image is MU1462 stock' || bad 'HMI image hash differs from MU1462'
is "$STOCK_AIRPLAY" /mnt/app/eso/lib/libairplay.so && ok 'libairplay is stock' || bad 'libairplay hash differs'
is "$STOCK_IAP2" /mnt/app/armle/lib/dll/ipod-drvr-iap2.so && ok 'iAP2 driver is stock' || bad 'iAP2 driver hash differs'
current=$("$tool" "$BIN")
if test "$current" = "$STOCK_DIO"; then ok 'dio_manager is stock'
elif test "$current" = "$NEW_DIO"; then bad 'dio_manager is already patched (already installed?)'
else bad 'dio_manager hash differs'; fi
for p in altscreen-dio_manager:"$NEW_DIO" altscreen-libaltscreen111.so:"$NEW_LIB" altscreen-cluster_runtime:"$NEW_RT" altscreen-cluster_supervisor.sh:"$NEW_SUP"; do
 is "${p#*:}" "$D/${p%%:*}" && ok "payload ${p%%:*}" || bad "payload ${p%%:*} damaged or missing"
done
test -e "$LIB" && bad "$LIB already exists" || ok 'no earlier hook library'
test -d /mnt/app/root/lib-target || mkdir_lib=1

pf_count=0
for f in $(pf_list); do
 b=${f##*/}
 if grep -q "port = 6031" "$f"; then echo "note  $b already has a 6031 rule"; continue; fi
 n=$(build_pf "$f" "/tmp/altscreen-pf.$$.$b")
 case $n in
  1) if pfctl -n -f "/tmp/altscreen-pf.$$.$b" >/dev/null 2>&1; then ok "firewall $b: rule parses"; pf_count=$((pf_count+1)); else bad "firewall $b: pfctl rejected the new rule"; fi;;
  0) echo "note  $b has no carplay0 block, leaving it alone"; rm -f "/tmp/altscreen-pf.$$.$b";;
  *) bad "firewall $b: found $n block lines";;
 esac
done
test "$pf_count" -ge 1 && ok "$pf_count firewall file(s) to update" || bad 'no firewall file to update'

running=0
pidin ar > /tmp/altscreen-ps.$$ 2>/dev/null || :
if grep -q 'dio_manager' /tmp/altscreen-ps.$$; then running=1; fi
if test "$running" = 1; then
 if test "$action" = --install; then bad 'dio_manager is running: unplug the iPhone and try again'; else echo 'note  dio_manager is running: unplug the iPhone before --install'; fi
fi
df -k /mnt/app /mnt/system 2>/dev/null || :
test "$fail" = 0 || { echo 'Stopped: nothing was changed.'; exit 1; }
if test "$action" = --check; then echo 'All checks passed. Nothing was changed.'; exit 0; fi

backup=/mnt/app/root/altscreen-backups/$(date +%Y%m%d-%H%M%S)-$$
mount -uw /mnt/app; rw_app=1
mount -uw /mnt/system; rw_sys=1
mkdir -p "$backup" /mnt/app/root/mu1438-cluster /mnt/app/root/lib-target
cp "$0" "$backup/altscreen-mu1462-install.sh"
cp "$D/altscreen-sha256" "$backup/altscreen-sha256"
chmod 755 "$backup/altscreen-sha256"
: > "$backup/manifest"

# name dest mode newhash source
stage(){
 if test -e "$2"; then
  old=$("$tool" "$2")
  cp "$2" "$backup/$1.before"
  "$tool" "$old" "$backup/$1.before"
 else old=absent; fi
 mkdir -p "${2%/*}"
 cp "$5" "$2.altscreen-new"
 chmod "$3" "$2.altscreen-new"
 "$tool" "$4" "$2.altscreen-new"
 printf '%s|%s|%s|%s|%s\n' "$1" "$2" "$3" "$old" "$4" >> "$backup/manifest"
}
stage dio_manager "$BIN" 777 "$NEW_DIO" "$D/altscreen-dio_manager"
stage libaltscreen111.so "$LIB" 755 "$NEW_LIB" "$D/altscreen-libaltscreen111.so"
stage cluster_runtime "$RT" 755 "$NEW_RT" "$D/altscreen-cluster_runtime"
stage cluster_supervisor.sh "$SUP" 755 "$NEW_SUP" "$D/altscreen-cluster_supervisor.sh"
for t in /tmp/altscreen-pf.$$.*; do
 test -f "$t" || continue
 b=${t##*.$$.}
 if test -x "$PF_DIR/$b"; then m=755; else m=644; fi
 stage "$b" "$PF_DIR/$b" "$m" "$("$tool" "$t")" "$t"
done
while IFS='|' read -r name dest mode old new; do
 mv "$dest.altscreen-new" "$dest"
 "$tool" "$new" "$dest"
done < "$backup/manifest"
echo "Installed and verified. SAVE THIS PATH: $backup"
echo 'Restart the MMI while parked, then reconnect the iPhone by cable.'
