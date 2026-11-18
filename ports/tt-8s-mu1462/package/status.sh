#!/bin/sh
# Read-only: shows what is installed on flash right now. Changes nothing.
#   sh /tmp/ns/status.sh
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
tool=/tmp/altscreen-sha256
test -x "$tool" || { echo 'Copy altscreen-sha256 to /tmp and chmod 755 it first.'; exit 1; }
has(){ "$tool" "$1" "$2" >/dev/null 2>&1; }
LIB=/mnt/app/root/lib-target/libaltscreen111.so
SUP=/mnt/app/root/mu1438-cluster/cluster_supervisor.sh
RT=/mnt/app/root/mu1438-cluster/cluster_runtime
DIO=/mnt/app/eso/bin/apps/dio_manager
if has 115259ff6f90af800c8a06c3b0655b2a59cffe00990ad6e7f525cc45c73c9043 $DIO; then echo 'dio_manager : patched (AltScreen)'
elif has 1974dbd95df5c33f81da4d8cf2c77b791e5a6466aa65c7d1912ec9ebd8c140e3 $DIO; then echo 'dio_manager : STOCK'
else echo 'dio_manager : unknown'; fi
if has caf2fa48c341fd7bd1a860a1660c5eaad86b906c707a0abe3ff769a368855357 $RT; then echo 'runtime     : ok (stock)'
elif has 24bf08142cc43a18330550ca448f4635e10d5ab3ed38475e80d450a469991a7f $RT; then echo 'runtime     : ok (sport shift)'
else echo 'runtime     : missing/unknown'; fi
test -e /tmp/mu1438-cluster-sport && echo 'sport mark  : SET' || echo 'sport mark  : not set'
if has 9175a6deaff6acf2c4f35b61b245476ba8827e7f4b74977b48cdafc2c7c6f57c $SUP; then echo 'supervisor  : latest (4776)'
elif test -f $SUP; then echo 'supervisor  : OLDER - run sup-update.sh'
else echo 'supervisor  : missing'; fi
preset=unknown
while read n h; do has $h $LIB && preset=$n; done <<'LIST'
stock 9a8c01ec8323d5de7695e212ff6168b0bb7df99d8554c9971f5449c5a21d6256
low 050e3ff9d6bd7f95fd14c0359a19878b5c483dbeb316261703ec85dad9f78102
lowwide 534c7d26f794aa01838c8fd12e7dd4d5a595ae43453427a22269e7649503c57b
full a24b5cfd55b2956d956f9910c568b596cfa3a8e8161ccede62f5fe1179424d55
midhigh b94dfaaadb918ebd8338f80004b4652c0695f8c5f58c74eb800bffedefd315b7
mid 05209ae70acd69fae95cb9bf4bbc8a0c8cb1d01961f6e894287acfde5e98eeed
midtall db1727930b486a5457f8f34a5772433da83afe8e4c1870b301068d991fdd57f3
combo d829824e39a67dfbf51b879e0e656011031eff38ace42f8d6e4314d15a417fbb
combolow 7b299075442f676074fee6fc8a2ebab39b1b423bbad9276ec9202e93f4d57705
combowide 9481af4923f7fa407a2ac430e76b6a7b58c9f403bedc83a6c16401645c056b1d
h340 0614cf7106f33f94a7cb69dc2fd22e99b9a8cc17a3ed9b9830ed483a648b3c68
h325 60060eddcf6ac34d0c673ee36bc75ffbb17f48ae4920de29ab716867d435688a
h310 b2f9e468b7d3884fedf23e755fb33957de4c6c1dbd70c55992825fc0fe03d81b
up10 094d401059180f29b73591b715891e5ab9eb390470f2cfe992020dbbcc030394
up20 3b878e1414e2837dd2d28d180bd0fa855871c9cbabb33dca0e00ede2a83789ab
h320 36aedadb8e0680ad03a9a511f3844e629ca3b2ffaaf40c9745298f730fc0b3f8
sport ec62cc08181886c9aa28a891bb0ceb5e21f4baa4a556407545b68c4172dbbb75
auto 37ac2d2994bd09822d6b19cf7d54463e1f933ee9318aee8ed899d5a1716ea155
auto2 d8bad58e8101d23a01e12261147466edbc6022e664a19c0a6c3b0a49907b72b2
LIST
echo "hook preset : $preset"
test -e /mnt/app/eso/hmi/lsd/jars/tt-zoom.jar && echo 'zoom jar    : installed' || echo 'zoom jar    : not installed'
grep -q 'port = 6031' /mnt/system/etc/pf.conf 2>/dev/null && echo 'firewall    : rule present' || echo 'firewall    : rule MISSING'
