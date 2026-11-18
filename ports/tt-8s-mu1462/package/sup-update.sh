#!/bin/sh
# Replaces only the cluster supervisor with the latest (4776-byte) version.
#   sh /tmp/ns/sup-update.sh --parked     then unplug/replug the iPhone
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
test "${1:-}" = --parked || { echo 'Park safely and add --parked.'; exit 2; }
want=9175a6deaff6acf2c4f35b61b245476ba8827e7f4b74977b48cdafc2c7c6f57c
SUP=/mnt/app/root/mu1438-cluster/cluster_supervisor.sh
tool=/tmp/altscreen-sha256
test -x "$tool" || { echo 'Copy altscreen-sha256 to /tmp and chmod 755 it first.'; exit 1; }
test -f "$SUP" || { echo "$SUP not found - is the AltScreen port installed?"; exit 1; }
"$tool" "$want" "$D/altscreen-cluster_supervisor.sh" || { echo 'new supervisor missing or damaged'; exit 1; }
if "$tool" "$want" "$SUP" 2>/dev/null; then echo 'Already the latest supervisor. Nothing changed.'; exit 0; fi
rw=0
cleanup(){ if test "$rw" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi; }
trap cleanup 0
trap 'exit 1' 1 2 15
mount -uw /mnt/app; rw=1
cp "$SUP" "$SUP.prev"
cp "$D/altscreen-cluster_supervisor.sh" "$SUP.new"
chmod 755 "$SUP.new"
"$tool" "$want" "$SUP.new"
mv "$SUP.new" "$SUP"
"$tool" "$want" "$SUP"
echo "Supervisor updated (old copy kept as $SUP.prev). Unplug the iPhone, wait 10 s, replug."
